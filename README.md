# VolGuard

Accessibility service for **Sony NW-A300** players (tested on NW-A306) with EU-style
“Check volume level” firmware.

When Sony clamps volume and shows that dialog, VolGuard accepts the safety gate,
restores **Walkman master volume** (0–120) to the level you had before the drop, and
dismisses the dialog. **No root.**

Latest: [v1.3.1](../../releases/tag/v1.3.1)

## Disclaimer

Use at your own risk. No warranty. High volume can damage hearing. Not affiliated
with Sony.

## The problem

On EU firmware, past a threshold Sony:

1. Drops master volume (often to ~50)
2. Shows “Check volume level”
3. Keeps OK unusable for about **three seconds**
4. Expects you to confirm, then turn the volume back up

That loop is inside Sony’s volume stack. Without root you cannot remove it — only
react after it fires.

## What VolGuard does

- Restores the **exact** pre-drop master level (if you were at 120, you get 120)
- Accepts Sony’s safe-volume gate so that restore can stick
- Taps OK for you once Sony enables the button, so the dialog leaves cleanly
- Does **not** stop the dialog from appearing (that needs root)

## What to expect

| | |
|--|--|
| Volume back | Typically tens of milliseconds after the clamp (see [Timing](#timing-nw-a306)) |
| Dialog | Still appears; fully gone after ~3.5 s (Sony’s OK delay) |
| Hands-free | Yes, once accessibility is enabled |
| Permanent silence of the check | No — Sony can rearm later; VolGuard handles the next trip |

Notes:

- First-time / rearm threshold depends on unit and output. On the measured A306 it
  often trips around **master 80**, not only at 120.
- After a successful accept, volume above the threshold is free until Sony’s rules
  re-enable the check (first-time flag and/or long listening timer).
- VolGuard remembers your last chosen master level so a clamp after reboot can
  still restore correctly.

## How it works

### Plain terms

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

Device: NW-A306. Version: **v1.3.1**.  
**t = 0** is Sony’s clamp / confirm (not dialog `Displayed`).

| Metric | Result |
|--------|--------|
| Volume restored to pre-clamp level | **~44–70 ms** |
| Dialog dismissed (OK usable + tap) | **~3.5 s** |
| v1.2.0 restore (same class of test) | ~1.36–1.42 s |

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
```

Use `adb -s <serial>` if more than one device is connected.

**Manual**

Settings → Accessibility → **VolGuard** → enable.

Android may turn accessibility services off after app updates; re-enable if it
stops working.

## Troubleshooting

| Symptom | Likely cause | What to try |
|---------|--------------|-------------|
| Nothing happens | Service disabled | Re-enable under Accessibility |
| Volume stays at the floor | Accept/restore failed, or no remembered level | Keep the service on while you set volume once; check logs |
| Dialog never closes | Another dialog on top, or old build | Clear USB/system prompts; use 1.3.1+ |
| Dialog returns after long listening | Sony rearmed safe volume | Expected; VolGuard should handle the next trip |

Logs:

```sh
adb logcat -s VolGuard:I
```

Useful lines: `trip via`, `restore target=`, `restore exact OK: master=`.

## Build

Android SDK **build-tools + platform 34**, **JDK 17** (newer JDKs break `d8`):

```sh
ANDROID_HOME=/path/to/android-sdk JAVA_HOME=/path/to/jdk17 bash build.sh
```

Produces `volguard.apk` in the repo root.

## License

[MIT](LICENSE).
