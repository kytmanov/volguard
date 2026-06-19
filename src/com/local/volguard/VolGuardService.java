package com.local.volguard;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Watches only Sony's "Check volume level" dialog
 * (com.sony.walkman.VolumeCtrlAlert). When it appears, it waits out Sony's
 * delayed-OK button, taps OK, then restores the music volume the dialog forced
 * down to ~50%. Fully autonomous; no recurring user action.
 */
public class VolGuardService extends AccessibilityService {

    private static final String TAG = "VolGuard";
    private static final String ALERT_PKG = "com.sony.walkman.VolumeCtrlAlert";

    // Broadcast + extras for stream-volume changes (stable framework constants).
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";

    private static final int POLL_MS = 200;     // how often to look for the OK button
    private static final int MAX_POLLS = 60;     // ~12s ceiling (Sony delays OK a few s)
    private static final int RESTORE_DELAY_MS = 350;
    private static final int REARM_DELAY_MS = 600;

    private AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean alertActive = false;
    // Two-slot history of the user's intended music volume, newest = desiredVol.
    private int desiredVol = -1;
    private int prevVol = -1;

    private final BroadcastReceiver volReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!VOLUME_CHANGED.equals(i.getAction())) return;
            if (i.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return;
            int val = i.getIntExtra(EXTRA_VALUE, -1);
            if (val < 0) return;
            if (alertActive) return; // ignore the dialog's forced drop
            prevVol = (desiredVol >= 0) ? desiredVol : val;
            desiredVol = val;
        }
    };

    @Override protected void onServiceConnected() {
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        desiredVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        prevVol = desiredVol;
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(volReceiver, filter);
        }
        Log.i(TAG, "connected; desiredVol=" + desiredVol);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !ALERT_PKG.contentEquals(pkg)) return;
        if (alertActive) return; // already handling this dialog
        alertActive = true;
        int target = chooseTarget();
        Log.i(TAG, "alert appeared; target volume=" + target);
        tryClickOk(0, target);
    }

    /** Pick the volume to restore to: the user's pre-drop level, else max. */
    private int chooseTarget() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int t = desiredVol;
        if (t <= cur) t = prevVol;   // desiredVol may have caught the forced drop
        if (t <= cur) t = max;       // can't tell -> give them loud
        if (t < 0) t = max;
        return Math.min(t, max);
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
                @Override public void run() { restoreVolume(target); }
            }, RESTORE_DELAY_MS);
            return;
        }
        if (attempt >= MAX_POLLS) {
            Log.w(TAG, "OK button never became clickable; restoring volume anyway");
            restoreVolume(target);
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { tryClickOk(attempt + 1, target); }
        }, POLL_MS);
    }

    /** DFS for the first enabled, clickable, visible Button; click it. */
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

    private void restoreVolume(int target) {
        try {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int t = Math.min(Math.max(target, 0), max);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, t, 0);
            desiredVol = t;
            prevVol = t;
            Log.i(TAG, "restored music volume to " + t + "/" + max);
        } catch (Exception e) {
            Log.e(TAG, "restore failed", e);
        } finally {
            // Re-arm only after the forced-drop event has surely passed.
            handler.postDelayed(new Runnable() {
                @Override public void run() { alertActive = false; }
            }, REARM_DELAY_MS);
        }
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        try { unregisterReceiver(volReceiver); } catch (Exception ignore) { }
        return super.onUnbind(intent);
    }
}
