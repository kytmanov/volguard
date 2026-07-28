# How Sony's safe-volume check actually works

Derived from `dexdump` of `/system_ext/priv-app/IzmAudioManager/IzmAudioManager.apk`
(class `com.sony.walkman.izmaudiomanager.volume.SafeVolume`), verified against live
`logcat` on an NW-A306. Written down because the previous understanding was wrong in
ways that led to a bad design.

## State machine

`SafeVolume` is INITIAL / INACTIVE / ACTIVE.

- **INACTIVE** is the normal state. A `java.util.Timer` accumulates exposure.
- **ACTIVE** is the confirm-required state: volume writes are rejected with
  `STATUS_SAFE_VOLUME` until the gate is cleared.

## What drives the timer

```java
SafeVolumeInactive.isTimerConditionActive() =
        mOutputId != NONE && mSafeVolumeThreshold < getRepresentativeVolume(mOutputId)
```

Two things follow, and both matter:

1. **There is no check that audio is playing.** The budget burns whenever an output
   is connected and the index is above the threshold — paused, screen off, in a bag.
2. **`TimeCounter.countUp()` adds `SystemClock.elapsedRealtime() - mStartCountTime`**,
   so it counts through deep sleep too. When the timer next fires after a sleep it
   adds the whole slept span at once.

For wired output the counted value is the Izm 0–120 master index
(`mDeviceVolume.getVolume()`), not Android's `STREAM_MUSIC` index. `STREAM_MUSIC` is
pinned at max on this device anyway, so there is no uncounted digital headroom.

`SAFE_VOLUME_THRESHOLDS` is a per-`OutputId` map supplied by the HAL, and `OutputId`
encodes the gain mode (`SE_GAIN_NORMAL`, `SE_GAIN_HIGH`, `BTL_*`), so the threshold
moves when `setGain` (txn 11) is used. Unexplored.

## Lowering the index is a real off switch

`SafeVolumeInactive.setVolume(i)` writes the level and then calls `updateTimer()`,
which stops the timer as soon as the condition goes false. `stopTimer()` does a final
`countUp()` first, so the time already accumulated is **banked, not lost** — there is
no way to game the counter by toggling, and equally no risk of corrupting it.

This is what VolGuard's idle suppression uses. Observed on device:

```
19:25:23  count up: 42438365      <- timer running at master 120
19:25:45  setVolume: 50
19:25:45  timer stop.             <- counter frozen; no further count up
19:27:27  setVolume: 120
19:27:27  timer start.            <- resumes from the banked total
```

Sony's clamp target (`mDefaultVolumeIndex`, ~50 here) is by definition at or below the
threshold, which is why VolGuard can suppress to it without discovering the threshold.

## The trip is conditional

```java
SafeVolumeActive.entry():
    if (mSafeVolumeThreshold < getRepresentativeVolume(mOutputId)) {
        setRepresentativeVolume(mOutputId, mDefaultVolumeIndex);  // clamp
        requestSafeVolumeConfirm();                                // dialog
    }
```

If the index is already at or below the threshold when the budget expires, there is no
clamp and no dialog — the state just goes ACTIVE silently.

## disableSafeVolume only resets from ACTIVE

```java
SafeVolumeActive.disableSafeVolume():   mTimeCounter.reset(); updateState(INACTIVE)
SafeVolumeInactive.disableSafeVolume(): return-void          // literal no-op
```

This is the correction that matters most. An earlier experiment called
`disableSafeVolume` (txn 10) pre-emptively — that is, from INACTIVE, where it does
nothing at all — saw the counter keep advancing, and concluded that the call "does not
stop the accumulator". The observation was right and the conclusion was backwards.

**Still unverified:** that VolGuard's reactive accept resets the counter in practice.
The code path says it must, because the accept happens while the state is ACTIVE, but
no real trip has been observed end to end since this was understood. On the test unit
the counter has never been seen to reset. Worth confirming before relying on it.

## The dialog cannot wake the screen

`VolumeCtrlAlert.apk` contains no `setTurnScreenOn`, `setShowWhenLocked`,
`FLAG_TURN_SCREEN_ON` or wake-lock use. With the screen off the warning is already
invisible, so an overlay to hide it buys very little. A stale `VolumeCtrlAlert`
activity can sit in the back stack and surface on the next wake — which is what
`am start`-based testing leaves behind.

## Detecting playback: do not use isMusicActive()

`AudioManager.isMusicActive()` is unusable on this device. Sony's offload output leaves the
music stream reported active indefinitely after the player is released — verified on device,
where it still returned `true` more than ten minutes after the last track ended and every
player had dropped out of `dumpsys audio`.

The failure mode is nasty: idle suppression works perfectly from a cold start, then stops
arming forever the moment a single track has played, with nothing in the log to explain it.

Use `AudioManager.getActivePlaybackConfigurations()` instead, filtered by
`AudioAttributes.getUsage()` so a notification blip does not count as listening. Released
players really do drop out of that list.

Related: do not cache screen state from `ACTION_SCREEN_ON`/`OFF` alone. A missed edge leaves
the cached flag stale and suppression never arms again. Read `PowerManager.isInteractive()` at
decision time and treat the broadcasts purely as a hint to re-evaluate. Whenever the idle check
cannot act, it should reschedule itself rather than return — dropping the thread of control is
what turns a transient condition into a permanent one.

## Testing

- `adb shell am start -n com.sony.walkman.VolumeCtrlAlert/.VolumeCtrlAlertActivity`
  shows the real dialog but bypasses the state machine entirely: it exercises
  dismissal only, never the trip, the clamp, or the counter reset. Do not read
  anything about prevention from it.
- Host-side `adb logcat -s SafeVolume:D` is the ground truth (`count up:`,
  `timer start.`, `timer stop.`, `setVolume:`).
- Reading those lines from inside the app does not work on this device: `READ_LOGS`
  granted with `pm grant` does put the app in gid 1007 (`log`), but logd still serves
  it nothing beyond the buffer header, even though the identical `logcat` invocation
  works from `adb shell`.
- `adb shell input keyevent 24/25` does not drive the Izm master, so the volume cannot
  be swept from adb — only a bound client can set it.
