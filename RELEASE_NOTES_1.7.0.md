**A volume warning no longer leaves you in silence.**

Sony's "Check volume level" dialog takes audio focus when it appears, which stops your music for good — so even after VolGuard put the volume back, you had to reach for the play button. It now presses play for you, usually within about a second.

The second is Sony's, not VolGuard's: its player refuses a play command until it has finished stopping, which takes it anywhere from 0.35 to 1.2 seconds. VolGuard adds about 50 ms on top of whatever Sony takes. This was measured on the device and every faster approach was ruled out — detail in [`docs/safe-volume-mechanism.md`](https://github.com/kytmanov/volguard/blob/master/docs/safe-volume-mechanism.md).

Music you paused yourself is never started.

## Action required if you are updating from 1.5.0 or earlier

VolGuard needs **notification access** from 1.6.0 on. It is the only way to read media playback state on this device.

```sh
adb shell cmd notification allow_listener com.local.volguard/com.local.volguard.VolGuardNotificationListener
```

Or on the device: **Settings → Notifications → Device & app notifications → VolGuard → Allow**.

**VolGuard does not read your notifications.** The permission exists purely because Android gates playback state behind it; the listener component is empty and handles nothing. The app's main screen shows which mode you're in.

**If you skip it**, VolGuard still restores your volume, but it can't tell playing from paused — so the battery-of-warnings saving rarely kicks in, and the music will not restart itself after a warning.

## Updating

```sh
adb install -r volguard.apk
adb shell cmd notification allow_listener com.local.volguard/com.local.volguard.VolGuardNotificationListener
```

Accessibility usually survives an update; if VolGuard goes quiet, re-enable it under **Settings → Accessibility → VolGuard**.

## Also new since 1.5.0

1.6.0 was tagged but never published, so these ship here for the first time.

- **Fixed: VolGuard could turn your volume down in the middle of a song.** Listening with the screen off, it could mistake playback for silence and drop the volume about 30 seconds later. On this firmware Sony's playback is invisible to the usual Android "is anything playing?" check, so VolGuard was asking a source that always answered "nothing". It now asks one that answers correctly — this is what notification access buys.
- **Instant recovery.** If VolGuard lowered the volume while you were idle, pressing play restores your level in about 40 ms, screen still off. You no longer have to wake the device.
- **Verified against a real warning.** Sony's warning was triggered for real, not simulated, and handled end to end: volume restored 46 ms after it fired.
- **Confirmed VolGuard doesn't cause extra warnings.** When it accepts a warning for you, Sony's exposure timer resets exactly as if you had tapped OK yourself. This had been an open worry; it is now measured and settled.

## Worth knowing

Sony's warning counts only time spent **above a volume threshold**, and that threshold is higher than you might expect. On the test unit (NW-A306), listening at 71 never advanced the timer at all — meaning the warning could never fire there, and the idle saving had nothing to save. It earns its keep only if you listen loud.

The threshold, and the 19.5-hour budget, are specific to that unit and its headphone output. Yours may differ.

---

Requires a Sony NW-A300 series player (tested on NW-A306). No root.
