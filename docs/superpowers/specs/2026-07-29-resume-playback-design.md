# Resume playback after a safe-volume trip

## Context

When Sony's safe-volume budget expires, VolGuard restores the master level in ~46 ms but the
music stays dead. The user is left with correct volume and silence, and has to press play.

The cause is not the clamp and not VolGuard. `VolumeCtrlAlertActivity` requests
`AUDIOFOCUS_GAIN` when it starts; the player receives `AUDIOFOCUS_LOSS`, stops, and by
contract does not resume. Confirmed with the accessibility service disabled
(`Bound services:{}`), where `am start` on the alert alone reproduces it. See
`docs/safe-volume-mechanism.md`.

Goal: restore both conditions that existed before the trip — volume level and playback — in
a time the user does not notice.

## Success criteria

1. After a trip that interrupts playback, music is playing again without user action.
2. The audible gap is short enough to read as a hiccup rather than a stop. Judged by ear on
   the device; there is no threshold worth asserting from a log alone.
3. Nothing starts playing that was not playing before the trip.
4. VolGuard never competes with the user's own button press.

## Evidence this is achievable

Measured on the NW-A306. A `play` dispatched while the alert still held focus:

```
01:04:03.471  alert requestAudioFocus                    uid 10148
01:04:03.473  player onAudioFocusChange(-1) -> stops     uid 10135
01:04:03.646  Track[HighRes(Red)] Drop
01:04:03.721  play dispatched
01:04:03.780  player requestAudioFocus                   <- reclaims focus
01:04:03.781  alert onAudioFocusChange(-1)               <- alert loses it instead
01:04:03.965  old track destroyed
              state=PLAYING(3), dialog still on screen
```

The player takes focus back from the alert and resumes. The alert is a dialog and is
unaffected by losing focus. No permission beyond the notification access 1.6.0 already
requires.

The 307 ms gap above is an artefact of the test deliberately waiting 200 ms before
resuming. Driven from the callback the delay should be tens of milliseconds, inside the
~770 ms before the audio track is torn down.

## Design

### Trigger: the stop, not the accept

From the recorded trip:

```
23:01:51.945  VolGuard finishes accept + restore
23:01:51.980  alert takes focus                <- 35 ms later
23:01:51.982  player stops
```

Resuming after the accept would fire while music is still playing — a no-op — and the music
would then die 35 ms later. The resume must be driven by the playback-stopped event.

VolGuard already receives that event: the `MediaController.Callback` registered in 1.6.0
for playback detection. This adds one branch to it, no new plumbing.

### State captured

- `lastPlayingPkg` — package of the controller most recently seen playing, updated in the
  existing controller callback.
- `resumePkg` — set in `beginTrip()` to `lastPlayingPkg` when
  `isPlaybackActive() || lastSessionPlaying`, otherwise null. Captured before the alert can
  kill playback: on the `izm-confirm` path `beginTrip` runs ~95 ms ahead of the focus grab.

The package is stored rather than the `MediaController` reference, because the controller
can go stale across the alert's lifecycle. The controller is re-found in `watchedControllers`
at resume time.

`resumePkg` is cleared as soon as a resume is attempted, and again in `finishHandling()`, so
a trip that never interrupts playback leaves no stale state behind.

### Resume

The callback instance is shared across controllers, so `onPlaybackStateChanged(state)` does
not identify which controller fired. The check therefore goes through `resumePkg` rather
than through the callback argument:

In `controllerCallback.onPlaybackStateChanged`, when `alertActive` and `resumePkg != null`:
look up the controller whose package is `resumePkg` in `watchedControllers`; if
`isPlayingState(controller.getPlaybackState())` is false, call
`controller.getTransportControls().play()` once and log the outcome. Then set `resumePkg`
to null so it fires at most once per trip.

`isPlayingState()` already exists and defines "playing" as `STATE_PLAYING`, `BUFFERING`,
`FAST_FORWARDING` or `REWINDING`.

That is the whole mechanism.

### Deliberately excluded

- **No retry loop.** Either the resume is fast enough to be unnoticeable or it has failed
  visibly, in which case the user presses play. A retry landing seconds later is both
  noticeable and liable to collide with that press — one action plays, the other pauses.
- **No resume on dialog dismissal.** With the screen off, dismissal happens whenever the
  device is next woken, which is far outside any unnoticeable window.
- **No recency timers or windows.** `lastSessionPlaying` already flips only on real
  transitions, and the focus-loss callback arrives after `beginTrip`, so no timestamp
  heuristic is needed.
- **Track position** is left to the player. Not restored explicitly unless testing shows it
  is lost.

### Degradation

- No notification access: `watchedControllers` is empty, `resumePkg` stays null, nothing
  happens. Volume restore is unaffected.
- Playback was not running before the trip: `resumePkg` is null, nothing happens. This
  covers a trip that fires while paused, which is a normal case since Sony's timer runs on
  volume alone.
- `trip via dialog` fallback path: playback is already dead when VolGuard notices, so
  `resumePkg` is null and nothing happens. Chosen over guessing, because a user-paused
  player is indistinguishable from a dialog-killed one at that point.
- Missing controller or a throwing binder call is logged and skipped, matching the file's
  existing convention.

## Verification

Automated tests do not exist in this repo; verification is on-device.

1. Play music, `adb shell am start -n com.sony.walkman.VolumeCtrlAlert/.VolumeCtrlAlertActivity`,
   and **listen**. This is the acceptance test — criterion 2 is a judgement about sound.
2. Confirm from `adb logcat -s VolGuard:V` that the resume fired from the stop event, and
   measure the gap between `onAudioFocusChange(-1)` and the player's own
   `requestAudioFocus` in `adb logcat -b main`.
3. Trip while paused: pause, `am start` the alert, confirm nothing starts playing.
4. Revoke notification access, repeat step 1, confirm no resume and no crash.
5. Confirm the resumed track continues from its previous position rather than restarting.

A genuine firmware trip is not required to validate this. `am start` reproduces the exact
focus-grab sequence, which is the only part that matters here — confirmed by the control run
with VolGuard disabled.

## Risks

- The gap may still be audible. If it is, the fallback is not a longer retry but a different
  feature: steering trips into silence by tracking above-threshold time against the known
  1170-minute budget so the warning fires while nothing is playing. Out of scope here.
- `onPlaybackStateChanged` latency is unmeasured. If Sony's player reports the stop slowly,
  the gap grows. Step 2 measures it.
- Resuming re-acquires focus from the alert. The alert has no audio, so this is expected to
  be harmless, but it is a behaviour change to Sony's component and worth watching for
  side effects on the OK button and dismissal.
