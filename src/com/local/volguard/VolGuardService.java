package com.local.volguard;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Watches only Sony's "Check volume level" dialog
 * (com.sony.walkman.VolumeCtrlAlert). When it appears it:
 * 1) immediately sends AVC_CHECK_LEVEL_OK so IzmAudioManager.disableSafeVolume()
 *    runs without waiting for the OK button,
 * 2) restores Walkman master volume (0–120) via MasterVolumeService — the level
 *    Sony actually clamps, not just Android STREAM_MUSIC,
 * 3) waits out Sony's ~3s GONE→VISIBLE OK button and taps it to clear the UI.
 */
public class VolGuardService extends AccessibilityService {

    private static final String TAG = "VolGuard";
    private static final String ALERT_PKG = "com.sony.walkman.VolumeCtrlAlert";

    private static final String ACTION_CHECK_LEVEL_OK =
            "com.sony.walkman.VolumeCtrlAlert.AVC_CHECK_LEVEL_OK";
    private static final String PERM_MASTER_VOLUME =
            "com.sony.walkman.volumectrlpanel.PERM_MASTER_VOLUME";
    private static final String ACTION_MASTER_VOLUME_CHANGED =
            "com.sony.walkman.intent.action.MASTER_VOLUME_CHANGED";
    private static final String EXTRA_MASTER_VOLUME = "volume";
    private static final String MASTER_SERVICE_ACTION =
            "com.sony.walkman.volumectrlpanel.MasterVolumeService";
    private static final String MASTER_SERVICE_PKG =
            "com.sony.walkman.volumectrlpanel";
    private static final String MASTER_SERVICE_IFACE =
            "com.sony.walkman.volumectrlpanel.IMasterVolumeService";
    // IMasterVolumeService.Stub transaction codes (from VolumeCtrlPanel).
    private static final int TX_SET_MASTER_VOLUME = 3;
    private static final int TX_GET_MASTER_VOLUME = 4;
    private static final int TX_GET_MAX_VOLUME = 5;

    private static final int POLL_MS = 200;
    private static final int MAX_POLLS = 60;
    /** First restore attempt after accept; panel may still hold mSafeVolumeAlertFlag. */
    private static final int EARLY_RESTORE_DELAY_MS = 200;
    private static final int RESTORE_RETRY_MS = 150;
    private static final int RESTORE_MAX_ATTEMPTS = 10;
    private static final int RESTORE_DELAY_MS = 350;
    private static final int REARM_DELAY_MS = 600;
    /** Sudden drop at least this large is treated as Sony's clamp, not a user action. */
    private static final int CLAMP_DROP_MIN = 15;
    private static final int DEFAULT_MASTER_MAX = 120;
    /** Panel setMasterVolume statuses (MasterVolumeConstants). */
    private static final int STATUS_ERROR = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_SAFE_VOLUME = 2;

    private AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean alertActive = false;
    // Intended Walkman master volume (0–120), two-slot history.
    private int desiredMaster = -1;
    private int prevMaster = -1;

    private IBinder masterBinder;
    private boolean masterBound = false;

    private final ServiceConnection masterConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            masterBinder = service;
            masterBound = true;
            Log.i(TAG, "MasterVolumeService bound");
            try {
                int cur = binderGetMasterVolume();
                if (cur >= 0 && desiredMaster < 0) {
                    desiredMaster = cur;
                    prevMaster = cur;
                }
                Log.i(TAG, "master volume now " + cur + "; desired=" + desiredMaster);
            } catch (Exception e) {
                Log.w(TAG, "initial getMasterVolume failed", e);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            masterBinder = null;
            masterBound = false;
            Log.w(TAG, "MasterVolumeService disconnected");
        }
    };

    private final BroadcastReceiver volReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i == null) return;
            String action = i.getAction();
            if (ACTION_MASTER_VOLUME_CHANGED.equals(action)) {
                int val = i.getIntExtra(EXTRA_MASTER_VOLUME, -1);
                if (val < 0) return;
                if (alertActive) return;
                // Sony clamps with a large step to ~default before the dialog shows.
                if (desiredMaster >= 0 && desiredMaster - val >= CLAMP_DROP_MIN) {
                    Log.i(TAG, "ignoring clamp drop " + desiredMaster + "->" + val
                            + " (keeping desired)");
                    return;
                }
                prevMaster = (desiredMaster >= 0) ? desiredMaster : val;
                desiredMaster = val;
                return;
            }
        }
    };

    @Override protected void onServiceConnected() {
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_MASTER_VOLUME_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Sender requires receivers to hold PERM; we hold it and declare the perm.
            registerReceiver(volReceiver, filter, PERM_MASTER_VOLUME, null,
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(volReceiver, filter, PERM_MASTER_VOLUME, null);
        }

        bindMasterService();
        Log.i(TAG, "connected; desiredMaster=" + desiredMaster);
    }

    private void bindMasterService() {
        try {
            Intent i = new Intent(MASTER_SERVICE_ACTION);
            i.setPackage(MASTER_SERVICE_PKG);
            boolean ok = bindService(i, masterConn, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bind MasterVolumeService -> " + ok);
        } catch (Exception e) {
            Log.e(TAG, "bind MasterVolumeService failed", e);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !ALERT_PKG.contentEquals(pkg)) return;
        if (alertActive) return;
        alertActive = true;
        final int target = chooseTarget();
        Log.i(TAG, "alert appeared; target master volume=" + target
                + " (desired=" + desiredMaster + " prev=" + prevMaster + ")");
        acceptSafeVolume();
        handler.postDelayed(new Runnable() {
            @Override public void run() { restoreMasterVolume(target, 0); }
        }, EARLY_RESTORE_DELAY_MS);
        tryClickOk(0, target);
    }

    /**
     * Prefer remembered pre-clamp master level; fall back to previous sample, else max.
     */
    private int chooseTarget() {
        int max = binderGetMaxVolume();
        if (max <= 0) max = DEFAULT_MASTER_MAX;
        int cur = binderGetMasterVolume();
        int t = desiredMaster;
        if (t < 0) t = prevMaster;
        // If both history slots look like the clamp floor, aim for max.
        if (t >= 0 && cur >= 0 && t <= cur) {
            if (prevMaster > t) t = prevMaster;
        }
        if (t < 0 || (cur >= 0 && t <= cur)) t = max;
        return Math.min(Math.max(t, 0), max);
    }

    private void tryClickOk(final int attempt, final int target) {
        boolean clicked = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            clicked = clickFirstEnabledButton(root);
            root.recycle();
        }
        if (clicked) {
            Log.i(TAG, "OK clicked (attempt " + attempt + ")");
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    restoreMasterVolume(target, 0);
                    finishHandling();
                }
            }, RESTORE_DELAY_MS);
            return;
        }
        if (attempt >= MAX_POLLS) {
            Log.w(TAG, "OK button never became clickable; restoring master volume anyway");
            restoreMasterVolume(target, 0);
            finishHandling();
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { tryClickOk(attempt + 1, target); }
        }, POLL_MS);
    }

    private boolean clickFirstEnabledButton(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence cls = node.getClassName();
        boolean isButton = cls != null && cls.toString().contains("Button");
        if (isButton && node.isClickable() && node.isEnabled() && node.isVisibleToUser()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean r = clickFirstEnabledButton(child);
                child.recycle();
                if (r) return true;
            }
        }
        return false;
    }

    private void acceptSafeVolume() {
        try {
            Intent i = new Intent(ACTION_CHECK_LEVEL_OK);
            sendBroadcast(i, PERM_MASTER_VOLUME);
            Log.i(TAG, "sent " + ACTION_CHECK_LEVEL_OK);
        } catch (Exception e) {
            Log.e(TAG, "accept broadcast failed", e);
        }
    }

    /**
     * Restore Walkman master volume via Sony's panel service. Retries while the
     * panel still returns STATUS_SAFE_VOLUME (flag not cleared yet). Also nudges
     * STREAM_MUSIC as a secondary path.
     */
    private void restoreMasterVolume(final int target, final int attempt) {
        int max = binderGetMaxVolume();
        if (max <= 0) max = DEFAULT_MASTER_MAX;
        final int t = Math.min(Math.max(target, 0), max);
        try {
            int status = binderSetMasterVolume(t);
            int now = binderGetMasterVolume();
            Log.i(TAG, "setMasterVolume(" + t + ") status=" + status
                    + " now=" + now + " attempt=" + attempt);
            if (status == STATUS_SUCCESS) {
                desiredMaster = t;
                prevMaster = t;
            } else if (status == STATUS_SAFE_VOLUME && attempt < RESTORE_MAX_ATTEMPTS) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        restoreMasterVolume(t, attempt + 1);
                    }
                }, RESTORE_RETRY_MS);
                return;
            } else if (status == STATUS_ERROR && attempt < RESTORE_MAX_ATTEMPTS) {
                bindMasterService();
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        restoreMasterVolume(t, attempt + 1);
                    }
                }, RESTORE_RETRY_MS);
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "setMasterVolume failed", e);
            if (attempt < RESTORE_MAX_ATTEMPTS) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        restoreMasterVolume(t, attempt + 1);
                    }
                }, RESTORE_RETRY_MS);
                return;
            }
        }
        try {
            if (audio != null) {
                int sMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, sMax, 0);
            }
        } catch (Exception ignore) { }
    }

    private int binderGetMasterVolume() {
        return binderIntNoArg(TX_GET_MASTER_VOLUME, -1);
    }

    private int binderGetMaxVolume() {
        return binderIntNoArg(TX_GET_MAX_VOLUME, DEFAULT_MASTER_MAX);
    }

    private int binderSetMasterVolume(int volume) throws RemoteException {
        IBinder b = masterBinder;
        if (b == null) {
            Log.w(TAG, "master binder null; rebinding");
            bindMasterService();
            return 0;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MASTER_SERVICE_IFACE);
            data.writeInt(volume);
            b.transact(TX_SET_MASTER_VOLUME, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private int binderIntNoArg(int tx, int fallback) {
        IBinder b = masterBinder;
        if (b == null) return fallback;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MASTER_SERVICE_IFACE);
            b.transact(tx, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            Log.w(TAG, "binder tx " + tx + " failed", e);
            return fallback;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void finishHandling() {
        handler.postDelayed(new Runnable() {
            @Override public void run() { alertActive = false; }
        }, REARM_DELAY_MS);
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        try { unregisterReceiver(volReceiver); } catch (Exception ignore) { }
        try {
            if (masterBound) unbindService(masterConn);
        } catch (Exception ignore) { }
        masterBinder = null;
        masterBound = false;
        return super.onUnbind(intent);
    }
}
