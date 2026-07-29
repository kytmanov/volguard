# How VolGuard works

Implementation notes for VolGuard itself. Sony's side — the state machine, what drives the
exposure timer, and the approaches that turned out to be dead ends — is in
[safe-volume-mechanism.md](safe-volume-mechanism.md).

## Idle suppression

Sony's timer runs on one condition: an output is connected and master volume is above the
threshold. Writing a lower level makes Sony stop the timer itself, and it banks the time
already counted rather than discarding it. So VolGuard waits 30 s after the screen goes off
with nothing playing, drops the level to Sony's own safe value, and puts your level back on
screen-on, playback start, or if you touch the volume yourself. If VolGuard is killed while a
level is owed back, it restores on next start.

Suppression pauses the counter; it does not refund it. The counter lives in firmware storage
no app can write, so suppression only stops it advancing — Sony banks the time already
counted. Accepting a real warning is what resets it, exactly as tapping OK yourself would.
Both halves of that are measured on an NW-A306: a real trip reset the counter from
70,224,368 ms to ~103,000 ms, so letting VolGuard handle warnings does not make them come back
sooner.

The budget is **19.5 hours** (1170 minutes) above the threshold on the test unit, and the
threshold is above master 71 there — at 71 the timer never runs at all, so the warning can
never fire and idle suppression saves nothing. It earns its keep only when you listen above
the threshold. Both figures are per-unit and per-output; measure your own with
`adb logcat -s SafeVolume:D` before assuming them.

VolGuard remembers your last chosen master level so a clamp after reboot can still restore
correctly.

### Known limitation: wired output

Idle suppression is verified on the 3.5 mm output. On Bluetooth and USB, Sony measures the
**Android media volume** rather than the Walkman master (`OutputId.nonObservable()` →
`getMediaVolume()`), while VolGuard lowers the master — so suppression may do nothing there.
Untested; the reactive handling is unaffected.

## Reacting to the warning

1. Sony drops volume and starts the confirmation flow.
2. VolGuard notices immediately (it does not wait for the dialog to finish drawing).
3. It waits until Sony's volume panel has entered "safe volume locked" state, then sends the
   same accept signal as the OK button.
4. It writes your previous master level back and checks that the level actually landed.
5. It puts playback back, which Sony's dialog stopped by taking audio focus.
6. When the OK button becomes usable (~3 s later), it taps only that button so the dialog
   closes without reopening.

```
clamp → wait for panel lock → accept gate → restore exact master → resume playback → tap OK
```

## Technical detail

Sony path:

1. `IzmAudioManager` / SafeVolume clamps master volume and calls
   `requestSafeVolumeConfirm()`.
2. `VolumeCtrlPanel` sets `mSafeVolumeAlertFlag` and starts
   `com.sony.walkman.VolumeCtrlAlert/.VolumeCtrlAlertActivity`.
3. While the flag is set, panel `setMasterVolume` returns status **2** (blocked).
4. OK sends `com.sony.walkman.VolumeCtrlAlert.AVC_CHECK_LEVEL_OK` (requires
   `PERM_MASTER_VOLUME`). That runs `disableSafeVolume()` and clears the flag.
5. Dismiss without OK sends `AVC_NON_CHECK_LEVEL_OK`, which **re-launches** the dialog.
6. `VolumeCtrlAlertActivity` requests `AUDIOFOCUS_GAIN` on launch, so the player is handed
   `AUDIOFOCUS_LOSS` and stops for good.

VolGuard path:

1. Binds `IzmAudioManager` and registers an event listener (`onSafeVolumeConfirmRequired`) so
   the clamp is seen as it happens. Fallback: dialog window events and
   `MASTER_VOLUME_CHANGED`.
2. **Gate:** probes `setMasterVolume(current)` until status is SAFE_VOLUME — proof the panel
   flag is up. Accepting earlier can clear the flag just before the panel sets it, leaving
   volume stuck low.
3. **Accept:** calls `disableSafeVolume` on Izm, then sends `AVC_CHECK_LEVEL_OK` as a
   **foreground** broadcast (background delivery was ~500 ms slower).
4. **Restore:** writes the remembered pre-clamp level via Izm's 0–120 `setVolume`, optionally
   syncs the panel, and only stops when `getVolume` equals the target.
5. **Resume:** replays `play()` on the `MediaController` of whichever player was running when
   the trip began. Driven by the playback-stopped event, or straight from the trip when the
   stop has already happened. See
   [safe-volume-mechanism.md](safe-volume-mechanism.md) for why it cannot be made faster.
6. **Dismiss:** accessibility is scoped to `com.sony.walkman.VolumeCtrlAlert`. OK is matched by
   `btn_confirm_check` (not "any button on screen"). Polling continues until the alert window
   is actually gone.

What it deliberately does **not** do: root/LSPosed patches, STREAM_MUSIC-only restore (that
does not undo the master clamp), or force-dismiss before OK in a way that triggers re-show.

## Timing (NW-A306)

| Metric | Result | Measured on |
|--------|--------|-------------|
| Playback resumed after a warning | **~0.4–1.2 s** (Sony's floor, not ours) | v1.7.0 |
| Idle restore, playback start → level written | **44 ms** | v1.6.0 |
| Volume restored to pre-clamp level, **real trip** | **46 ms** | v1.6.0 |
| Volume restored to pre-clamp level | **~44–70 ms** | v1.3.1 |
| Dialog dismissed (OK usable + tap) | **~3.5 s** | v1.3.1 |
| v1.2.0 restore (same class of test) | ~1.36–1.42 s | v1.2.0 |

For the clamp rows, **t = 0** is Sony's clamp / confirm (not dialog `Displayed`). The
clamp-handling code is unchanged since v1.3.1. The v1.6.0 clamp row is from a genuine
firmware-driven trip — the exposure budget actually running out, not `am start` — measured
from `SafeVolume: requestSafeVolumeConfirm` to `SafeVolume: setVolume: 120`. The idle-restore
figure is the gap between the media-key press and `restore exact OK`.

Interpretation:

- Restore is fast because VolGuard reacts on Izm's confirm path, not after the dialog window
  is shown.
- Dismiss stays ~3.5 s because Sony keeps OK `GONE` for a hard-coded ~3000 ms after
  `onResume`. Skipping that without a real OK risks `AVC_NON_CHECK_LEVEL_OK` and a re-launch.
- By the time you notice the dialog, volume should already be correct.
- The resume is the one slow number here, and none of it is VolGuard's: Sony's player ignores
  a play command until it has finished handling the focus loss, which takes it 350–1210 ms.
  VolGuard adds ~50 ms on top. Measured, with the alternatives ruled out, in
  [safe-volume-mechanism.md](safe-volume-mechanism.md).

Other units and outputs may differ; treat these as an A306 reference, not a guarantee.

## Logs

```sh
adb logcat -s VolGuard:I
```

Useful lines: `trip via`, `restore target=`, `restore exact OK: master=`, `idle:`.

To check idle suppression is really stopping Sony's counter, watch Sony's own tag:

```sh
adb logcat -s SafeVolume:D
```

`timer stop.` should follow VolGuard's `idle: suppressed`, and `count up:` should stop
advancing until the level is restored.

If suppression never arms, `adb logcat -s VolGuard:D` adds a line every 30 s naming the reason
(`idle: waiting (screenOn=… musicActive=…)`).

Two developer-facing symptoms that used to live in the README's troubleshooting table:

- Service unbound after `install -r` — re-set `enabled_accessibility_services`. Writing `""`
  returns `Bad arguments`, so set the value directly.
- Suppression never engaging — check `mediaSessions=` in the connect log line.
