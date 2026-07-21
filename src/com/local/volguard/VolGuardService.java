package com.local.volguard;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Undoes Sony's safe-volume clamp as early as the platform allows.
 *
 * The trip starts inside IzmAudioManager: it clamps master volume to the output
 * default, then calls requestSafeVolumeConfirm(). VolumeCtrlPanel hears that on a
 * listener, posts a Runnable that sets its mSafeVolumeAlertFlag and only then
 * launches the dialog. So the dialog window is the *last* observable step —
 * waiting for it (as 1.2.0 did) wastes ~770ms.
 *
 * Instead:
 * 1) register directly on IzmAudioManager's event listener to learn about the
 *    clamp the moment it happens (MASTER_VOLUME_CHANGED stays as a fallback),
 * 2) poll setMasterVolume(currentVolume) — a no-op write — until the panel
 *    answers STATUS_SAFE_VOLUME. That answer is exact proof the panel's Runnable
 *    has run, which is what makes accepting early safe: accepting before the flag
 *    is set would leave it stuck true and the volume floored,
 * 3) send AVC_CHECK_LEVEL_OK as a foreground broadcast (the background queue cost
 *    ~500ms) so disableSafeVolume() runs, then restore the pre-clamp master level,
 * 4) still tap the dialog's OK button once it appears — the activity only skips
 *    its AVC_NON_CHECK_LEVEL_OK (which re-launches itself) after a real click.
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

    private static final String IZM_PKG = "com.sony.walkman.izmaudiomanager";
    private static final String IZM_CLASS =
            "com.sony.walkman.izmaudiomanager.IzmAudioManager";
    private static final String IZM_SERVICE_ACTION =
            "com.sony.walkman.izmaudiomanager.IzmAudioManager";
    private static final String IZM_IFACE =
            "com.sony.walkman.izmaudiomanager.IIzmAudioManager";
    private static final String IZM_LISTENER_IFACE =
            "com.sony.walkman.izmaudiomanager.IIzmAudioManagerEventListener";
    // IIzmAudioManager.Stub transaction codes.
    private static final int TX_IZM_REGIST_LISTENER = 86;
    private static final int TX_IZM_UNREGIST_LISTENER = 87;
    // IIzmAudioManagerEventListener.Stub transaction codes (incoming).
    private static final int TX_ON_HEADSET_CHANGED = 1;
    private static final int TX_ON_SAFE_VOLUME_CONFIRM_REQUIRED = 3;
    private static final int TX_ON_VOLUME_STATE_CHANGED = 4;

    private static final int POLL_MS = 200;
    private static final int MAX_POLLS = 60;
    /** Probe cadence while waiting for the panel to raise mSafeVolumeAlertFlag. */
    private static final int GATE_POLL_MS = 20;
    private static final int GATE_MAX_ATTEMPTS = 50;
    private static final int RESTORE_RETRY_MS = 25;
    private static final int RESTORE_MAX_ATTEMPTS = 60;
    private static final int RESTORE_DELAY_MS = 350;
    private static final int REARM_DELAY_MS = 600;
    /** Sudden drop at least this large is treated as Sony's clamp, not a user action. */
    private static final int CLAMP_DROP_MIN = 15;
    private static final int DEFAULT_MASTER_MAX = 120;
    /** Panel setMasterVolume statuses (MasterVolumeConstants). */
    private static final int STATUS_ERROR = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_SAFE_VOLUME = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean alertActive = false;
    // Latest user-intended Walkman master volume (0–120), updated on normal changes.
    private int desiredMaster = -1;
    private int prevMaster = -1;
    // Explicit snapshot of master volume immediately before Sony's forced clamp.
    // This is the only target we restore to when the dialog appears.
    private int preClampMaster = -1;

    private IBinder masterBinder;
    private boolean masterBound = false;

    private IBinder izmBinder;
    private boolean izmBound = false;

    // Per-trip state.
    private int restoreTarget = -1;
    private volatile boolean restoreDone = false;
    private boolean okTapStarted = false;

    /**
     * IIzmAudioManagerEventListener. registListener carries no permission check,
     * so a third-party app can hear the clamp directly instead of inferring it.
     * Callbacks may be synchronous on IzmAudioManager's dispatch thread — return
     * immediately and do the work on our own looper.
     */
    private final Binder izmListener = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case TX_ON_SAFE_VOLUME_CONFIRM_REQUIRED:
                    data.enforceInterface(IZM_LISTENER_IFACE);
                    if (reply != null) reply.writeNoException();
                    handler.post(new Runnable() {
                        @Override public void run() { beginTrip("izm-confirm"); }
                    });
                    return true;
                case TX_ON_VOLUME_STATE_CHANGED:
                case TX_ON_HEADSET_CHANGED:
                    data.enforceInterface(IZM_LISTENER_IFACE);
                    if (reply != null) reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    private final ServiceConnection izmConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            izmBinder = service;
            izmBound = true;
            Log.i(TAG, "IzmAudioManager bound; registListener -> " + izmRegisterListener());
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            izmBinder = null;
            izmBound = false;
            Log.w(TAG, "IzmAudioManager disconnected");
        }
    };

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
                // Remember the pre-clamp level and do not overwrite desiredMaster with the floor.
                if (desiredMaster >= 0 && desiredMaster - val >= CLAMP_DROP_MIN) {
                    preClampMaster = desiredMaster;
                    Log.i(TAG, "clamp detected: preClamp=" + preClampMaster
                            + " loweredTo=" + val);
                    return;
                }
                prevMaster = (desiredMaster >= 0) ? desiredMaster : val;
                desiredMaster = val;
                return;
            }
        }
    };

    @Override protected void onServiceConnected() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_MASTER_VOLUME_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Sender requires receivers to hold PERM; we hold it and declare the perm.
            registerReceiver(volReceiver, filter, PERM_MASTER_VOLUME, null,
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(volReceiver, filter, PERM_MASTER_VOLUME, null);
        }

        izmListener.attachInterface(null, IZM_LISTENER_IFACE);
        bindMasterService();
        bindIzmService();
        Log.i(TAG, "connected; desiredMaster=" + desiredMaster);
    }

    private void bindIzmService() {
        try {
            Intent i = new Intent(IZM_SERVICE_ACTION);
            i.setComponent(new ComponentName(IZM_PKG, IZM_CLASS));
            boolean ok = bindService(i, izmConn, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bind IzmAudioManager -> " + ok);
        } catch (Exception e) {
            Log.e(TAG, "bind IzmAudioManager failed", e);
        }
    }

    private boolean izmRegisterListener() {
        return izmListenerTx(TX_IZM_REGIST_LISTENER);
    }

    private boolean izmListenerTx(int tx) {
        IBinder b = izmBinder;
        if (b == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IZM_IFACE);
            data.writeStrongBinder(izmListener);
            b.transact(tx, data, reply, 0);
            reply.readException();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "izm listener tx " + tx + " failed", e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
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

        if (!alertActive) {
            // No confirm callback reached us (Izm bind failed?). 1.2.0 behaviour.
            beginTrip("dialog");
        } else if (!restoreDone) {
            // Watchdog: the dialog is up but the accept never took hold. Worst case
            // this is exactly what 1.2.0 did, so it can only help.
            Log.w(TAG, "dialog visible but restore incomplete; re-accepting");
            acceptSafeVolume();
            restoreMasterVolume(restoreTarget, 0);
        }

        if (!okTapStarted) {
            okTapStarted = true;
            tryClickOk(0, restoreTarget);
        }
    }

    /**
     * Begin handling one safe-volume trip. The clamp has already happened by now;
     * what remains is to accept (clearing both SafeVolume ACTIVE and the panel's
     * flag) and put the master level back.
     */
    private void beginTrip(String source) {
        if (alertActive) return;
        alertActive = true;
        restoreDone = false;
        okTapStarted = false;
        restoreTarget = choosePreClampTarget();
        Log.i(TAG, "trip via " + source + "; restore target=" + restoreTarget
                + " (preClamp=" + preClampMaster
                + " desired=" + desiredMaster
                + " prev=" + prevMaster + ")");
        gatePoll(0);
    }

    /**
     * Wait for VolumeCtrlPanel to raise mSafeVolumeAlertFlag before accepting.
     *
     * Accepting earlier is unsafe: the panel sets that flag from a posted Runnable,
     * and AVC_CHECK_LEVEL_OK arriving first would clear it just before the Runnable
     * sets it again — leaving it true with nothing left to clear it, so the volume
     * would stay at the floor for good.
     *
     * The probe writes the volume that is already in effect, so it is a no-op if the
     * flag is not up yet. It must not write the *target*: with the flag down the call
     * reaches Izm, and SafeVolume is still ACTIVE, so a value above the threshold
     * would clamp again and raise a second dialog.
     */
    private void gatePoll(final int attempt) {
        int status;
        try {
            int cur = binderGetMasterVolume();
            status = binderSetMasterVolume(cur >= 0 ? cur : 0);
        } catch (Exception e) {
            status = STATUS_ERROR;
        }
        if (status == STATUS_SAFE_VOLUME) {
            Log.i(TAG, "panel flag up after " + attempt + " polls; accepting");
            acceptSafeVolume();
            restoreMasterVolume(restoreTarget, 0);
            return;
        }
        if (attempt >= GATE_MAX_ATTEMPTS) {
            Log.w(TAG, "panel flag never observed; accepting anyway");
            acceptSafeVolume();
            restoreMasterVolume(restoreTarget, 0);
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { gatePoll(attempt + 1); }
        }, GATE_POLL_MS);
    }

    /**
     * Volume to restore: the master level in effect immediately before Sony
     * lowered it. Never the post-clamp floor. Max only if we have no history.
     */
    private int choosePreClampTarget() {
        int max = binderGetMaxVolume();
        if (max <= 0) max = DEFAULT_MASTER_MAX;
        int cur = binderGetMasterVolume();

        int t = -1;
        if (preClampMaster >= 0) {
            t = preClampMaster;
        } else if (desiredMaster >= 0 && (cur < 0 || desiredMaster > cur)) {
            // desired still holds pre-drop if clamp broadcast was missed.
            t = desiredMaster;
        } else if (prevMaster >= 0 && (cur < 0 || prevMaster > cur)) {
            t = prevMaster;
        }

        if (t < 0) {
            Log.w(TAG, "no pre-clamp level known; falling back to max " + max);
            t = max;
        }
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
            // The panel receives this on a background-registered receiver; without
            // this flag delivery sat on the background queue for ~500ms.
            i.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
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
                preClampMaster = -1; // consumed
                restoreDone = true;
                return;
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
        // Deliberately no STREAM_MUSIC fallback here. Sony clamps the 0–120 master
        // level, not STREAM_MUSIC, so raising the stream does not undo the clamp —
        // it only makes everything permanently louder than the user asked for.
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
        try { izmListenerTx(TX_IZM_UNREGIST_LISTENER); } catch (Exception ignore) { }
        try {
            if (masterBound) unbindService(masterConn);
        } catch (Exception ignore) { }
        try {
            if (izmBound) unbindService(izmConn);
        } catch (Exception ignore) { }
        masterBinder = null;
        masterBound = false;
        izmBinder = null;
        izmBound = false;
        return super.onUnbind(intent);
    }
}
