package com.local.volguard;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

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
    private static final String OK_BUTTON_ID =
            "com.sony.walkman.VolumeCtrlAlert:id/btn_confirm_check";

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
    private static final int TX_IZM_SET_VOLUME = 3;
    private static final int TX_IZM_GET_VOLUME = 4;
    private static final int TX_IZM_DISABLE_SAFE_VOLUME = 10;
    private static final int TX_IZM_REGIST_LISTENER = 86;
    private static final int TX_IZM_UNREGIST_LISTENER = 87;
    // IIzmAudioManagerEventListener.Stub transaction codes (incoming).
    private static final int TX_ON_HEADSET_CHANGED = 1;
    private static final int TX_ON_SAFE_VOLUME_CONFIRM_REQUIRED = 3;
    private static final int TX_ON_VOLUME_STATE_CHANGED = 4;

    /** Sony reveals OK ~3s after onResume; poll tightly so the tap is prompt. */
    private static final int POLL_MS = 100;
    private static final int MAX_POLLS = 100;
    /** Probe cadence while waiting for the panel to raise mSafeVolumeAlertFlag. */
    private static final int GATE_POLL_MS = 20;
    private static final int GATE_MAX_ATTEMPTS = 50;
    private static final int RESTORE_RETRY_MS = 25;
    private static final int RESTORE_MAX_ATTEMPTS = 60;
    private static final int RESTORE_DELAY_MS = 350;
    private static final int REARM_DELAY_MS = 600;
    private static final String PREFS = "volguard";
    private static final String KEY_LAST_MASTER = "last_master";
    private static final String KEY_CLAMP_FLOOR = "clamp_floor";
    private static final String KEY_SUPPRESS_RESTORE = "suppress_restore";
    /**
     * Where Sony clamps to on a trip (mDefaultVolumeIndex). It is by definition
     * at or below the safe-volume threshold, which is what makes it a correct
     * suppression target without having to discover the threshold itself.
     * Replaced by the real value the first time we see a clamp.
     */
    private static final int DEFAULT_CLAMP_FLOOR = 50;
    /**
     * Idle has to hold this long before we touch the volume. Kept short on
     * purpose: this is a Handler delay, so it does not fire once the device is in
     * deep sleep, and missing the window costs the whole sleep period of budget.
     * Screen-off is already the debounce — a gap between tracks does not turn the
     * screen off — so a long delay would buy nothing and lose the overnight case.
     */
    private static final int SUPPRESS_DELAY_MS = 30000;
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
    // Survives process death. Sony can clamp at boot, before this service is even
    // started, and then nothing in memory remembers the level the user was at.
    private int lastKnownMaster = -1;
    private SharedPreferences prefs;

    private IBinder masterBinder;
    private boolean masterBound = false;

    private IBinder izmBinder;
    private boolean izmBound = false;

    // Idle suppression: Sony's exposure timer runs on master level alone, so it
    // keeps burning while paused and asleep. Holding the level down while nothing
    // is playing stops it, inaudibly.
    private android.media.AudioManager audioManager;
    private boolean suppressed = false;
    /** Level to put back when idle ends; -1 when not suppressed. */
    private int suppressRestoreLevel = -1;
    /** The level we wrote, so our own MASTER_VOLUME_CHANGED is not read as intent. */
    private int suppressedLevel = -1;
    private int clampFloor = DEFAULT_CLAMP_FLOOR;

    // Per-trip state.
    private int restoreTarget = -1;
    private volatile boolean restoreDone = false;
    private boolean okTapStarted = false;
    private boolean alertSeen = false;

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
            recoverSuppression();
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
                    // Do not persist this one: if Sony already clamped before we
                    // started, cur is the floor and would overwrite the real level.
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
            if (ACTION_MASTER_VOLUME_CHANGED.equals(i.getAction())) {
                int val = i.getIntExtra(EXTRA_MASTER_VOLUME, -1);
                if (val >= 0) noteMasterVolume(val);
            }
        }
    };

    /**
     * Idle suppression.
     *
     * SafeVolume's exposure timer runs on one condition only — an output is
     * connected and the master index is above its threshold. It never checks
     * whether audio is playing, and it accumulates with elapsedRealtime(), so the
     * budget burns just as fast paused, screen-off and in standby as it does
     * during listening. Holding the level at Sony's own clamp floor while nothing
     * is playing stops the timer (setVolume calls updateTimer, which cancels it
     * once the level is no longer above the threshold) and banks the time already
     * accumulated rather than losing it.
     *
     * Nothing is audible because this only ever runs while nothing is playing, and
     * nothing is visible because it only ever runs while the screen is off.
     */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i == null || i.getAction() == null) return;
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                endSuppression("screen on", true);
            }
            // Either edge is a reason to re-evaluate; suppressIfIdle reads the
            // real screen state itself rather than trusting a cached flag.
            scheduleIdleCheck();
        }
    };

    /**
     * Read the screen state fresh every time it is needed.
     *
     * A cached boolean fed only by ACTION_SCREEN_ON/OFF goes stale the moment one
     * of those broadcasts is missed — and then suppression never arms again, with
     * nothing in the log to say why. This is that bug's fix.
     */
    private boolean isScreenOn() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Exception e) {
            return true;
        }
    }

    private final android.media.AudioManager.AudioPlaybackCallback playbackCallback =
            new android.media.AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(
                java.util.List<android.media.AudioPlaybackConfiguration> configs) {
            if (isMusicActive()) endSuppression("playback started", true);
            // Re-arm unconditionally. isMusicActive() keeps reporting true for a
            // moment after playback really stops, so the stop event itself can
            // read as active — and once the player is released no further event
            // arrives to correct it. Scheduling either way makes this self-healing.
            scheduleIdleCheck();
        }
    };

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() { suppressIfIdle(); }
    };

    /**
     * Whether anything media-like is really playing.
     *
     * Deliberately not AudioManager.isMusicActive(): on this device Sony's
     * offload output leaves the music stream reported active indefinitely after
     * the player is released, so once a single track had played that call never
     * returned false again and suppression could never arm. The active
     * playback-configuration list tracks real players, which do drop out.
     *
     * Usage is filtered so a notification blip does not count as listening.
     */
    private boolean isMusicActive() {
        try {
            if (audioManager == null) return true;
            for (android.media.AudioPlaybackConfiguration c
                    : audioManager.getActivePlaybackConfigurations()) {
                android.media.AudioAttributes attrs = c.getAudioAttributes();
                if (attrs == null) return true;
                int usage = attrs.getUsage();
                if (usage == android.media.AudioAttributes.USAGE_MEDIA
                        || usage == android.media.AudioAttributes.USAGE_UNKNOWN
                        || usage == android.media.AudioAttributes.USAGE_GAME) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // Assume playing: a wrong "idle" would drop the volume mid-song, a
            // wrong "busy" only costs some budget.
            return true;
        }
    }

    private void scheduleIdleCheck() {
        handler.removeCallbacks(idleCheck);
        if (suppressed || alertActive) return;
        handler.postDelayed(idleCheck, SUPPRESS_DELAY_MS);
    }

    private void suppressIfIdle() {
        if (suppressed || alertActive) return;
        // Keep looking while the device is idle-but-not-yet-suppressible. Giving
        // up here is what previously stranded the service: the events that would
        // have restarted the check had already been and gone.
        boolean screen = isScreenOn();
        boolean music = isMusicActive();
        if (screen || music) {
            // Debug level: this repeats every SUPPRESS_DELAY_MS for as long as the
            // device is in use, which would drown the info log. Visible with
            // `adb logcat -s VolGuard:D` when suppression is not arming.
            Log.d(TAG, "idle: waiting (screenOn=" + screen + " musicActive=" + music + ")");
            scheduleIdleCheck();
            return;
        }

        int cur = izmGetVolume();
        if (cur < 0) cur = binderGetMasterVolume();
        if (cur < 0) {
            Log.w(TAG, "idle: volume unreadable; not suppressing");
            return;
        }
        if (cur <= clampFloor) return; // already below the threshold, nothing to do
        Log.i(TAG, "idle: screen off, nothing playing, master=" + cur);

        suppressRestoreLevel = cur;
        suppressedLevel = clampFloor;
        suppressed = true;
        // Persist before writing: if we die between the two, recovery must still
        // know a level is owed back.
        if (prefs != null) prefs.edit().putInt(KEY_SUPPRESS_RESTORE, cur).apply();

        if (izmSetVolume(clampFloor)) {
            Log.i(TAG, "idle: suppressed " + cur + " -> " + clampFloor);
        } else {
            Log.w(TAG, "idle: suppress write failed; restoring");
            endSuppression("suppress failed", true);
        }
    }

    /**
     * @param restore put the level back. False when the user has just set a level
     *                themselves, since overwriting it would fight them.
     */
    private void endSuppression(String reason, boolean restore) {
        handler.removeCallbacks(idleCheck);
        if (!suppressed) return;
        int target = suppressRestoreLevel;
        suppressed = false;
        suppressRestoreLevel = -1;
        suppressedLevel = -1;
        // When we still owe a level back, the record has to outlive this call:
        // restoreMasterVolume clears it once the level has actually landed, so a
        // process death mid-restore is still recoverable.
        if (prefs != null && !(restore && target >= 0)) {
            prefs.edit().remove(KEY_SUPPRESS_RESTORE).apply();
        }

        if (restore && target >= 0) {
            Log.i(TAG, "idle: ending (" + reason + "); restoring " + target);
            // Reuses the trip restore: it re-asserts through Izm, verifies the
            // level actually landed, and clears a SafeVolume gate if one is up.
            restoreMasterVolume(target, 0);
        } else {
            Log.i(TAG, "idle: ending (" + reason + "); leaving level alone");
        }
    }

    /**
     * Put back a level we lowered but never restored, because the service died
     * while suppressed. Without this the user would find the player quiet and
     * have no idea why.
     */
    private void recoverSuppression() {
        if (prefs == null || suppressed) return;
        int owed = prefs.getInt(KEY_SUPPRESS_RESTORE, -1);
        if (owed < 0) return;
        prefs.edit().remove(KEY_SUPPRESS_RESTORE).apply();
        int cur = izmGetVolume();
        // Only restore if the level really is still down. If the user has since
        // set their own, theirs wins.
        if (cur >= 0 && cur > clampFloor) {
            Log.i(TAG, "idle: stale suppression, level already " + cur + "; not restoring");
            return;
        }
        Log.w(TAG, "idle: recovering unrestored suppression -> " + owed);
        restoreMasterVolume(owed, 0);
    }

    /** Sony's clamp target, which is always at or below the safe-volume threshold. */
    private void rememberClampFloor(int floor) {
        if (floor <= 0 || floor == clampFloor || prefs == null) return;
        clampFloor = floor;
        prefs.edit().putInt(KEY_CLAMP_FLOOR, floor).apply();
        Log.i(TAG, "learned clamp floor: " + floor);
    }

    /**
     * Track user-intended master and catch Sony's clamp step.
     *
     * Even while a trip is active, still record a clamp-shaped drop if preClamp
     * is unknown — 1.3.0 could miss MASTER_VOLUME_CHANGED when izm-confirm set
     * alertActive first, then restore to the wrong level.
     */
    private void noteMasterVolume(int val) {
        if (val < 0) return;

        if (suppressed) {
            // Our own write echoes back as a change; ignore it. Anything else is
            // the user reaching for the volume, which outranks idle suppression —
            // and their new value is their intent, so do not undo it.
            if (val == suppressedLevel) return;
            endSuppression("user volume change", false);
        }

        int baseline = desiredMaster >= 0 ? desiredMaster
                : (prevMaster >= 0 ? prevMaster : lastKnownMaster);
        boolean clampDrop = baseline >= 0 && baseline - val >= CLAMP_DROP_MIN;

        if (clampDrop) {
            if (preClampMaster < 0 || baseline > preClampMaster) {
                preClampMaster = baseline;
                Log.i(TAG, "clamp detected: preClamp=" + preClampMaster
                        + " loweredTo=" + val
                        + " alertActive=" + alertActive);
            }
            return; // never learn the floor as desired
        }

        if (alertActive) return;

        prevMaster = (desiredMaster >= 0) ? desiredMaster : val;
        desiredMaster = val;
        rememberMaster(val);
        // A level set while the screen is off (or one we just restored) is worth
        // suppressing again once things settle.
        scheduleIdleCheck();
    }

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

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        lastKnownMaster = prefs.getInt(KEY_LAST_MASTER, -1);
        clampFloor = prefs.getInt(KEY_CLAMP_FLOOR, DEFAULT_CLAMP_FLOOR);

        audioManager = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, screenFilter);
        try {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler);
        } catch (Exception e) {
            Log.w(TAG, "playback callback registration failed", e);
        }

        izmListener.attachInterface(null, IZM_LISTENER_IFACE);
        bindMasterService();
        bindIzmService();
        Log.i(TAG, "connected; desiredMaster=" + desiredMaster
                + " lastKnownMaster=" + lastKnownMaster
                + " screenOn=" + isScreenOn() + " clampFloor=" + clampFloor);
        scheduleIdleCheck();
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
     * flag) and put the master level back to the exact pre-clamp master value.
     */
    private void beginTrip(String source) {
        if (alertActive) return;
        // A trip outranks idle suppression: hand the real level back to the trip
        // machinery, which is what knows how to restore it through a clamp.
        boolean wasSuppressed = suppressed;
        if (suppressed) {
            int owed = suppressRestoreLevel;
            endSuppression("trip started", false);
            if (owed >= 0) preClampMaster = owed;
        }
        // Snapshot before alertActive blocks normal tracking. Clamp may already
        // have lowered getMasterVolume(); desired/prev still hold the pre-drop level.
        capturePreClampIfNeeded();
        alertActive = true;
        restoreDone = false;
        okTapStarted = false;
        alertSeen = false;
        restoreTarget = choosePreClampTarget();
        // Whatever Sony dropped us to during a trip is mDefaultVolumeIndex, which
        // is the level idle suppression should use. Skip it when we were the ones
        // holding the level down, or we would just learn our own suppression value.
        if (!wasSuppressed) {
            int floorNow = izmGetVolume();
            if (floorNow > 0 && restoreTarget > floorNow) rememberClampFloor(floorNow);
        }
        Log.i(TAG, "trip via " + source + "; restore target=" + restoreTarget
                + " (preClamp=" + preClampMaster
                + " desired=" + desiredMaster
                + " prev=" + prevMaster
                + " lastKnown=" + lastKnownMaster + ")");
        gatePoll(0);
    }

    /** If we have not yet seen a clamp-shaped drop, infer pre-clamp from history. */
    private void capturePreClampIfNeeded() {
        if (preClampMaster >= 0) return;
        int cur = binderGetMasterVolume();
        if (cur < 0) cur = izmGetVolume();
        // Most recent wins, not loudest. The pre-clamp level is the last value the
        // user chose; picking the maximum of the three would restore *above* it —
        // e.g. a 120 -> 85 slider move leaves prevMaster=120 with desiredMaster=85,
        // and a clamp at 85 must come back to 85.
        //
        // The `> cur` guard is what makes falling through safe: desiredMaster is
        // seeded from the current level at bind, so it holds the post-clamp floor
        // when Sony clamped before this process started. A floor never passes the
        // guard, so selection moves on to the next source.
        int best = -1;
        String src = null;
        if (desiredMaster >= 0 && (cur < 0 || desiredMaster > cur)) {
            best = desiredMaster;
            src = "desiredMaster";
        } else if (prevMaster >= 0 && (cur < 0 || prevMaster > cur)) {
            best = prevMaster;
            src = "prevMaster";
        } else if (lastKnownMaster >= 0 && (cur < 0 || lastKnownMaster > cur)) {
            best = lastKnownMaster;
            src = "lastKnownMaster";
        }
        if (best >= 0) {
            preClampMaster = best;
            Log.i(TAG, "preClamp from " + src + "=" + preClampMaster
                    + " (cur=" + cur + ")");
        }
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
            if (cur < 0) {
                // The probe is only safe because it rewrites the level already in
                // effect. Without a reading there is no such value — writing 0 here
                // would mute the device, and with the flag still down it would reach
                // Izm and stick. Skip this pass instead.
                status = STATUS_ERROR;
            } else {
                status = binderSetMasterVolume(cur);
            }
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
     * Exact master level from immediately before Sony clamped (e.g. 120 → 120), or
     * -1 when it cannot be determined. Never the post-clamp floor.
     *
     * -1 means "do not touch the volume" and must stay negative all the way to the
     * caller: clamping it into range would turn an unknown level into 0 and mute the
     * device, which is the opposite of leaving things alone.
     */
    private int choosePreClampTarget() {
        int max = binderGetMaxVolume();
        if (max <= 0) max = DEFAULT_MASTER_MAX;
        capturePreClampIfNeeded();

        int t = preClampMaster;
        if (t < 0) {
            int cur = binderGetMasterVolume();
            if (cur < 0) cur = izmGetVolume();
            if (cur < 0) {
                Log.w(TAG, "no pre-clamp level and no readable volume; not touching it");
                return -1;
            }
            Log.w(TAG, "no pre-clamp level known; leaving volume at " + cur);
            t = cur;
        }
        return Math.min(Math.max(t, 0), max);
    }

    /** Persist the user's level so a clamp before our next start can be undone. */
    private void rememberMaster(int volume) {
        if (volume < 0 || volume == lastKnownMaster || prefs == null) return;
        lastKnownMaster = volume;
        prefs.edit().putInt(KEY_LAST_MASTER, volume).apply();
    }

    /**
     * Tap the dialog's OK button, then keep watching until the dialog is actually
     * gone. A successful performAction is not proof of dismissal, and Sony keeps the
     * button GONE for ~3s after onResume, so the first few passes find nothing.
     *
     * getRootInActiveWindow() is not restricted to the packages this service
     * subscribes to, so the root's package must be checked: without that we would
     * happily click the first Button in whatever app happens to be foreground.
     */
    private void tryClickOk(final int attempt, final int target) {
        AccessibilityNodeInfo root = findAlertRoot();
        boolean alertUp = root != null;
        if (alertUp) {
            alertSeen = true;
            if (clickOkButton(root)) Log.i(TAG, "OK clicked (attempt " + attempt + ")");
        }
        if (root != null) root.recycle();

        if (alertSeen && !alertUp) {
            Log.i(TAG, "alert dismissed after " + attempt + " polls");
            restoreMasterVolume(target, 0);
            finishHandling();
            return;
        }
        if (attempt >= MAX_POLLS) {
            Log.w(TAG, alertSeen
                    ? "alert still up after " + attempt + " polls; giving up on tap"
                    : "alert never became foreground; giving up on tap");
            restoreMasterVolume(target, 0);
            finishHandling();
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { tryClickOk(attempt + 1, target); }
        }, POLL_MS);
    }

    /**
     * Root of Sony's alert, or null if it is not on screen. The active window is
     * checked first, then every window: another dialog (a USB-mode prompt, a
     * notification) can sit on top of the alert and take focus, and the alert still
     * needs its OK tapped in that case.
     */
    private AccessibilityNodeInfo findAlertRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (isAlert(root)) return root;
        if (root != null) root.recycle();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (isAlert(r)) return r;
                if (r != null) r.recycle();
            }
        } catch (Exception ignore) { }
        return null;
    }

    private boolean isAlert(AccessibilityNodeInfo node) {
        return node != null && node.getPackageName() != null
                && ALERT_PKG.contentEquals(node.getPackageName());
    }

    /** Matches Sony's OK button by id so no other control can be hit. */
    private boolean clickOkButton(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable() && node.isEnabled() && node.isVisibleToUser()) {
            String id = node.getViewIdResourceName();
            CharSequence cls = node.getClassName();
            // Prefer the id; fall back to class because ids are only reported when
            // the service asks for them. Safe either way: this only ever walks a
            // tree already confirmed to be Sony's alert window.
            boolean isOk = OK_BUTTON_ID.equals(id)
                    || (id == null && cls != null && cls.toString().contains("Button"));
            if (isOk) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean r = clickOkButton(child);
                child.recycle();
                if (r) return true;
            }
        }
        return false;
    }

    private void acceptSafeVolume() {
        // Sync clear of SafeVolume ACTIVE so a restore above threshold cannot
        // re-clamp while the panel broadcast is still in flight.
        izmDisableSafeVolume();
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
     * Restore to the exact pre-clamp master level (120 restores to 120, not ~80).
     *
     * 1.3.0 stopped on panel status==SUCCESS without checking the resulting level,
     * so a write that still hit the floor (or a cap) counted as done. We write via
     * Izm setVolume (true 0–120 path) and only finish when getVolume == target.
     */
    private void restoreMasterVolume(final int target, final int attempt) {
        if (target < 0) {
            // Unknown level. Writing anything here would be a guess; 0 would mute.
            Log.w(TAG, "no restore target; leaving volume untouched");
            return;
        }
        int max = binderGetMaxVolume();
        if (max <= 0) max = DEFAULT_MASTER_MAX;
        final int t = Math.min(Math.max(target, 0), max);
        try {
            if (attempt == 0 || attempt % 4 == 0) izmDisableSafeVolume();

            boolean izmOk = izmSetVolume(t);
            int nowIzm = izmGetVolume();

            int status = STATUS_ERROR;
            int nowPanel = -1;
            try {
                status = binderSetMasterVolume(t);
                nowPanel = binderGetMasterVolume();
            } catch (Exception e) {
                Log.w(TAG, "panel setMasterVolume failed", e);
                if (masterBinder == null) bindMasterService();
            }

            int now = nowIzm >= 0 ? nowIzm : nowPanel;
            Log.i(TAG, "restore target=" + t
                    + " izmOk=" + izmOk + " nowIzm=" + nowIzm
                    + " panelStatus=" + status + " nowPanel=" + nowPanel
                    + " attempt=" + attempt);

            if (now == t) {
                desiredMaster = t;
                prevMaster = t;
                rememberMaster(t);
                preClampMaster = -1;
                restoreDone = true;
                // The level is back, so nothing is owed any more.
                if (prefs != null) prefs.edit().remove(KEY_SUPPRESS_RESTORE).apply();
                Log.i(TAG, "restore exact OK: master=" + now);
                return;
            }

            if (attempt < RESTORE_MAX_ATTEMPTS) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        restoreMasterVolume(t, attempt + 1);
                    }
                }, RESTORE_RETRY_MS);
                return;
            }
            Log.w(TAG, "restore gave up: wanted " + t
                    + " got izm=" + nowIzm + " panel=" + nowPanel);
        } catch (Exception e) {
            Log.e(TAG, "restoreMasterVolume failed", e);
            if (attempt < RESTORE_MAX_ATTEMPTS) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        restoreMasterVolume(t, attempt + 1);
                    }
                }, RESTORE_RETRY_MS);
            }
        }
    }

    private boolean izmDisableSafeVolume() {
        IBinder b = izmBinder;
        if (b == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IZM_IFACE);
            b.transact(TX_IZM_DISABLE_SAFE_VOLUME, data, reply, 0);
            reply.readException();
            Log.i(TAG, "izm disableSafeVolume ok");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "izm disableSafeVolume failed", e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean izmSetVolume(int volume) {
        IBinder b = izmBinder;
        if (b == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IZM_IFACE);
            data.writeInt(volume);
            b.transact(TX_IZM_SET_VOLUME, data, reply, 0);
            reply.readException();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "izm setVolume(" + volume + ") failed", e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private int izmGetVolume() {
        IBinder b = izmBinder;
        if (b == null) return -1;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IZM_IFACE);
            b.transact(TX_IZM_GET_VOLUME, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            Log.w(TAG, "izm getVolume failed", e);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
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
        // Give the level back before going away, so a disable/uninstall never
        // leaves the player quiet.
        endSuppression("service stopping", true);
        try { unregisterReceiver(volReceiver); } catch (Exception ignore) { }
        try { unregisterReceiver(screenReceiver); } catch (Exception ignore) { }
        try {
            if (audioManager != null) audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        } catch (Exception ignore) { }
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
