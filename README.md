# VolGuard

Accessibility service for **Sony NW-A300** players (tested on NW-A306) with EU-style
“Check volume level” firmware.

VolGuard makes that warning rare by stopping Sony's exposure timer while you are not
listening, and cleans it up when it does fire — accepting the safety gate, restoring
**Walkman master volume** (0–120) to the level you had before the drop, and dismissing
the dialog. **No root.**

Latest: [v1.6.0](../../releases/tag/v1.6.0)

## Disclaimer

Use at your own risk. No warranty. High volume can damage hearing. Not affiliated
with Sony.

## The problem

Sony runs an exposure timer. Whenever headphones are connected **and** master
volume is above a fixed threshold, it accumulates time; once the total reaches the
firmware limit it:

1. Drops master volume (often to ~50)
2. Shows “Check volume level”
3. Keeps OK unusable for about **three seconds**
4. Expects you to confirm, then turn the volume back up

The timer does not check whether anything is playing, and it counts through standby.
So the budget burns just as fast while the player sits paused in your bag as it does
while you listen. That is why the warning shows up sooner than your actual listening
time would suggest.

## What VolGuard does

- **Stops the budget burning while you are not listening.** With the screen off and
  nothing playing, it holds master volume at Sony’s own safe level, which stops the
  timer, and puts your exact level back the moment the screen wakes or playback
  starts. Nothing is audible, because it only ever happens during silence.
- Restores the **exact** pre-drop master level when a warning does fire (if you were
  at 120, you get 120)
- Accepts Sony’s safe-volume gate so that restore can stick
- Taps OK for you once Sony enables the button, so the dialog leaves cleanly
- Does **not** stop the warning forever — it makes it much rarer, and handles it
  when it comes

## What to expect

| | |
|--|--|
| Warnings | Much rarer, because idle time no longer costs budget |
| Volume back | Typically tens of milliseconds after the clamp (see [Timing](#timing-nw-a306)) |
| Dialog | Still appears; fully gone after ~3.5 s (Sony’s OK delay) |
| Hands-free | Yes, once accessibility is enabled |
| Volume while listening | Never touched — suppression only runs with nothing playing |
| Permanent silence of the check | No — the budget runs out eventually; VolGuard handles that trip |

Notes:

- The threshold depends on unit and output. What matters is that Sony counts *time
  spent above it*, not loudness — so the only thing that slows the budget down is
  spending less time with the volume up.
- **Idle suppression pauses the budget; it does not refund it.** The counter lives in
  firmware storage no app can write, so suppression only stops it advancing — Sony
  banks the time already counted. Accepting a real warning is what resets it, exactly
  as tapping OK yourself would. Both halves of that are now measured on an NW-A306: a
  real trip reset the counter from 70,224,368 ms to ~103,000 ms, so letting VolGuard
  handle warnings does not make them come back sooner.
- **The budget is 19.5 hours** (1170 minutes) above the threshold on the test unit, and
  the threshold is above master 71 there — at 71 the timer never runs at all, so the
  warning can never fire and idle suppression saves nothing. It earns its keep only when
  you listen above the threshold. Both figures are per-unit and per-output; measure your
  own with `adb logcat -s SafeVolume:D` before assuming them.
- VolGuard remembers your last chosen master level so a clamp after reboot can
  still restore correctly.
- Idle suppression needs the screen off. If you leave the player awake and paused
  with the volume up, the budget still burns.

### Known limitation: wired output

Idle suppression is verified on the 3.5 mm output. On Bluetooth and USB, Sony measures
the **Android media volume** rather than the Walkman master
(`OutputId.nonObservable()` → `getMediaVolume()`), while VolGuard lowers the master —
so suppression may do nothing there. Untested; the reactive handling is unaffected.

## How it works

### Idle suppression

Sony's timer runs on one condition: an output is connected and master volume is
above the threshold. Writing a lower level makes Sony stop the timer itself, and it
banks the time already counted rather than discarding it. So VolGuard waits 30 s
after the screen goes off with nothing playing, drops the level to Sony's own safe
value, and puts your level back on screen-on, playback start, or if you touch the
volume yourself. If VolGuard is killed while a level is owed back, it restores on
next start.

### Reacting to the warning

1. Sony drops volume and starts the confirmation flow.
2. VolGuard notices immediately (it does not wait for the dialog to finish drawing).
3. It waits until Sony’s volume panel has entered “safe volume locked” state, then
   sends the same accept signal as the OK button.
4. It writes your previous master level back and checks that the level actually
   landed.
5. When the OK button becomes usable (~3 s later), it taps only that button so the
   dialog closes without reopening.

```
clamp → wait for panel lock → accept gate → restore exact master → tap OK
```

### Technical detail

Sony path:

1. `IzmAudioManager` / SafeVolume clamps master volume and calls
   `requestSafeVolumeConfirm()`.
2. `VolumeCtrlPanel` sets `mSafeVolumeAlertFlag` and starts
   `com.sony.walkman.VolumeCtrlAlert/.VolumeCtrlAlertActivity`.
3. While the flag is set, panel `setMasterVolume` returns status **2** (blocked).
4. OK sends `com.sony.walkman.VolumeCtrlAlert.AVC_CHECK_LEVEL_OK` (requires
   `PERM_MASTER_VOLUME`). That runs `disableSafeVolume()` and clears the flag.
5. Dismiss without OK sends `AVC_NON_CHECK_LEVEL_OK`, which **re-launches** the
   dialog.

VolGuard path:

1. Binds `IzmAudioManager` and registers an event listener
   (`onSafeVolumeConfirmRequired`) so the clamp is seen as it happens. Fallback:
   dialog window events and `MASTER_VOLUME_CHANGED`.
2. **Gate:** probes `setMasterVolume(current)` until status is SAFE_VOLUME — proof
   the panel flag is up. Accepting earlier can clear the flag just before the panel
   sets it, leaving volume stuck low.
3. **Accept:** calls `disableSafeVolume` on Izm, then sends `AVC_CHECK_LEVEL_OK` as a
   **foreground** broadcast (background delivery was ~500 ms slower).
4. **Restore:** writes the remembered pre-clamp level via Izm’s 0–120 `setVolume`,
   optionally syncs the panel, and only stops when `getVolume` equals the target.
5. **Dismiss:** accessibility is scoped to `com.sony.walkman.VolumeCtrlAlert`. OK is
   matched by `btn_confirm_check` (not “any button on screen”). Polling continues
   until the alert window is actually gone.

What it deliberately does **not** do: root/LSPosed patches, STREAM_MUSIC-only
restore (that does not undo the master clamp), or force-dismiss before OK in a way
that triggers re-show.

## Timing (NW-A306)

Device: NW-A306.

| Metric | Result | Measured on |
|--------|--------|-------------|
| Idle restore, playback start → level written | **44 ms** | v1.6.0 |
| Volume restored to pre-clamp level, **real trip** | **46 ms** | v1.6.0 |
| Volume restored to pre-clamp level | **~44–70 ms** | v1.3.1 |
| Dialog dismissed (OK usable + tap) | **~3.5 s** | v1.3.1 |
| v1.2.0 restore (same class of test) | ~1.36–1.42 s | v1.2.0 |

For the clamp rows, **t = 0** is Sony’s clamp / confirm (not dialog `Displayed`). The
clamp-handling code is unchanged since v1.3.1. The v1.6.0 clamp row is from a genuine
firmware-driven trip — the exposure budget actually running out, not `am start` — measured
from `SafeVolume: requestSafeVolumeConfirm` to `SafeVolume: setVolume: 120`. The idle-restore
figure is the gap between the media-key press and `restore exact OK`.

Interpretation:

- Restore is fast because VolGuard reacts on Izm’s confirm path, not after the
  dialog window is shown.
- Dismiss stays ~3.5 s because Sony keeps OK `GONE` for a hard-coded ~3000 ms after
  `onResume`. Skipping that without a real OK risks `AVC_NON_CHECK_LEVEL_OK` and a
  re-launch.
- By the time you notice the dialog, volume should already be correct.

Other units and outputs may differ; treat these as an A306 reference, not a
guarantee.

## Install

Download `volguard.apk` from [Releases](../../releases).

**adb**

```sh
adb install volguard.apk
adb shell settings put secure enabled_accessibility_services com.local.volguard/com.local.volguard.VolGuardService
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener com.local.volguard/com.local.volguard.VolGuardNotificationListener
```

Use `adb -s <serial>` if more than one device is connected.

**Manual**

Settings → Accessibility → **VolGuard** → enable, then
Settings → Notifications → Device & app notifications → **VolGuard** → allow.

Android may turn accessibility services off after app updates; re-enable if it
stops working.

### Why notification access

Idle suppression has to know whether music is actually playing, and on this device
Sony's player is invisible to every other playback API — the audio runs on a native
offload track the platform's player list never sees. The only source that reports it
truthfully is the media session, and reading that requires notification access.

VolGuard reads no notifications; the listener component exists solely as the token that
`MediaSessionManager` demands. Skip the grant and everything else still works — idle
suppression just falls back to a conservative check and will rarely engage. It will
never lower the volume while you are listening either way. The app's main screen shows
which mode you are in.

## Troubleshooting

| Symptom | Likely cause | What to try |
|---------|--------------|-------------|
| Nothing happens | Service disabled | Re-enable under Accessibility |
| Volume stays at the floor | Accept/restore failed, or no remembered level | Keep the service on while you set volume once; check logs |
| Dialog never closes | Another dialog on top, or old build | Clear USB/system prompts; use 1.5.0+ |
| Stopped working right after an update | `install -r` left the service unbound | Re-set `enabled_accessibility_services` (see Install); writing `""` returns `Bad arguments`, so set the value directly |
| Warnings as often as before | Output is Bluetooth/USB, or the screen rarely goes off | See [Known limitation](#known-limitation-wired-output) |
| Dialog returns after long listening | Sony's exposure budget ran out again | Expected; VolGuard handles the next trip |
| Player quiet after a crash | Suppression was not restored | Turn the screen on; VolGuard restores on next start |
| Volume drops mid-song with the screen off | Pre-1.6.0 bug: playback was undetectable | Update to 1.6.0+ and grant notification access |
| Suppression never engages | Notification access not granted | Grant it (see [Install](#install)); check `mediaSessions=` in the log |

Logs:

```sh
adb logcat -s VolGuard:I
```

Useful lines: `trip via`, `restore target=`, `restore exact OK: master=`, `idle:`.

To check idle suppression is really stopping Sony's counter, watch Sony's own tag:

```sh
adb logcat -s SafeVolume:D
```

`timer stop.` should follow VolGuard's `idle: suppressed`, and `count up:` should
stop advancing until the level is restored.

If suppression never arms, `adb logcat -s VolGuard:D` adds a line every 30 s naming
the reason (`idle: waiting (screenOn=… musicActive=…)`).

## Build

Android SDK **build-tools + platform 34**, **JDK 17** (newer JDKs break `d8`).

`build.sh` calls `javac` and `keytool` directly, so JDK 17 has to be on `PATH` —
setting `JAVA_HOME` alone is not enough:

```sh
ANDROID_HOME=/path/to/android-sdk PATH=/path/to/jdk17/bin:$PATH bash build.sh
```

Produces `volguard.apk` in the repo root.

## License

[MIT](LICENSE).
