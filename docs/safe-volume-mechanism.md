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

**Verified on device.** A real trip was finally driven end to end (see "A real trip,
measured"): the counter read `70,224,368` on the poll that tripped and `103,158` on the
next tick after the accept. VolGuard's accept resets the counter exactly as a manual OK
tap would, so handling trips automatically does **not** make them fire more often — the
standing worry that it might is settled.

The reset comes from the direct `disableSafeVolume` (txn 10) call, which VolGuard issues
*before* the broadcast and while the state is still ACTIVE: `SafeVolume: disableSafeVolume`
and `ACTIVE -> INACTIVE` are logged 1 ms apart, ahead of `AVC_CHECK_LEVEL_OK` going out.

## A real trip, measured

Driven with the silent exposure rig on an NW-A306 at master 120, screen off. Device clock:

```
23:01:51.749  count up: 70224368          <- the poll that decided isTimeOver()
23:01:51.820  timer stop.
23:01:51.825  INACTIVE -> ACTIVE
23:01:51.858  notifyVolumeStateChanged    <- the clamp; VolGuard later reads cur=50
23:01:51.860  requestSafeVolumeConfirm
23:01:51.883  VolGuard: preClamp from desiredMaster=120 (cur=50)
23:01:51.885  VolGuard: trip via izm-confirm             <- 25 ms after the confirm
23:01:51.887  VolGuard: panel flag up after 0 polls
23:01:51.898  ACTIVE -> INACTIVE          <- accept landed, counter reset
23:01:51.901  VolGuard: sent AVC_CHECK_LEVEL_OK
23:01:51.906  setVolume: 120              <- level restored, 46 ms after the confirm
23:01:51.941  timer start.                <- 120 is above threshold again
23:01:51.945  VolGuard: restore exact OK: master=120
```

- **The exposure limit is 1170 minutes (19.5 h).** The poll at `70,164,368` did not trip and
  the poll at `70,224,368` did, so the limit is in `(70164368, 70224368]` ms. The only whole
  minute in that window is 1170, which matches the `SAFE_VOLUME_TIME_LIMIT_MINUTES` name.
  Round-hour values 12 h through 19 h were each excluded by watching the counter pass them.
- **Restore latency: 46 ms** from `requestSafeVolumeConfirm` to the level being written, 85 ms
  to the verified read-back. Consistent with the README's 44–70 ms from v1.3.1.
- `panelStatus=2` (STATUS_SAFE_VOLUME) on the first restore while `nowIzm=120` — the panel
  rejected the write and the Izm path took it anyway. This is why restore goes through Izm
  `setVolume` rather than the panel.
- **The dialog stops playback — not the clamp, and not VolGuard.** `VolumeCtrlAlertActivity`
  requests `AUDIOFOCUS_GAIN` (`req=1`, `USAGE_MEDIA/CONTENT_TYPE_MUSIC`) when it starts. The
  player receives `AUDIOFOCUS_LOSS (-1)`, drops its track and does not auto-resume, which is
  the "pause-without-resume" seen in earlier experiments.

  ```
  23:01:51.828  izm_master_volume=50        <- the clamp; playback continues
  23:01:51.906  setVolume: 120              <- VolGuard's restore; still playing
  23:01:51.980  requestAudioFocus() uid 10148  callingPack=com.sony.walkman.VolumeCtrlAlert
  23:01:51.982  onAudioFocusChange(-1) -> PlaybackService
  23:01:52.750  Track[HighRes(Red)] has been destroyed
  ```

  An earlier reading of this blamed the clamp. That was wrong: playback survives the clamp
  and the restore, and dies only when the dialog takes focus. Confirmed by a control run with
  the accessibility service disabled (`Bound services:{}`) — `am start` on the alert alone
  reproduces the identical sequence, so no VolGuard version can be responsible.

  Consequence worth acting on: **a real warning always kills playback.** VolGuard restores the
  volume but leaves the music stopped. Since 1.6.0 holds a `MediaController` for playback
  detection, `getTransportControls().play()` puts it back — see the next section for how fast
  that can be, which is not as fast as it looks.
- **With the screen off the dialog never becomes foreground**, so `tryClickOk` exhausts its
  polls and logs `alert never became foreground`. The activity stays in the back stack —
  `visible=true visibleRequested=false`, `launchedFromPackage=com.sony.walkman.volumectrlpanel`
  — and surfaces on the next wake, where VolGuard picks it up as `trip via dialog` and
  dismisses it (`OK clicked`, `alert dismissed`) about 3.2 s later. So a screen-off trip costs
  the user one dialog on their next wake, already accepted and self-dismissing.

  This is the one place the current design is worse than a screen-on trip: the alert holds
  audio focus for as long as it sits undismissed — three minutes in the recorded run, against
  a few seconds when someone is looking at the screen. It does not change *whether* playback
  stops, since the focus loss is immediate and permanent, but it is an argument for dismissing
  the alert without waiting for it to become foreground.

## How fast playback can be resumed — the player sets the floor, not us

Sony's player ignores a `play` until it has finished handling the focus loss. Publishing the
stopped `PlaybackState` to its `MediaSession` is what marks that moment, and once it has passed
the player acts in **~50 ms**. Before it, a play is not honoured, whoever sends it.

Measured on NW-A306, `am start` on the alert, VolGuard 1.6.0+resume. Times are milliseconds
after the player's `onAudioFocusChange(-1)`:

| run | play dispatched at | state published at | player reclaims focus | track destroyed |
|---|---|---|---|---|
| A | 405, 643 (timers), 1094 (stop event) | 1094 | **1127** | 1039 |
| B | 346 (stop event), 401, 638 (timers) | 346 | **401** | 1035 |
| C | ~250 (`input keyevent 126`, no VolGuard) | — | **1156** | 1076 |

Read across the rows: the reclaim tracks the publish (+33 ms, +55 ms), not the dispatch. In
run B a play at 346 ms worked while the same 401 ms and 638 ms dispatches in run A did nothing
— the difference is that B's player had already published. Run C rules out the transport-control
API being at fault: a media *key* event behaves identically.

**Sony's focus-loss handling latency is 350–1210 ms and it varies run to run** (n=8 across this
table and the 1.6.0 baseline; roughly one run in five is fast). That is the whole of the gap.
The resume rides ~50 ms behind it and cannot be moved forward.

Disproven, do not retry without new evidence:

- **Timers scheduled from `beginTrip`** (attempts at +150/+400 ms, ahead of the stop event).
  Built and measured; the early dispatches are simply not honoured. Reverted.
- **Media key event instead of `getTransportControls().play()`** — run C above.
- **Blaming the audio-track teardown.** `Track ... Destroy` lands ~1035 ms out in every run and
  for three runs it sat suspiciously close to the reclaim; run B separates them (destroy 1035,
  reclaim 401). Teardown is not the gate.

So the stop-event trigger already resumes as early as this device permits, and the remaining
gap is not addressable from outside Sony's player. Making a warning genuinely unnoticeable
needs the trip to happen while nothing is playing — the exposure-budget steering described
above — not a faster resume.

## The dialog cannot wake the screen

`VolumeCtrlAlert.apk` contains no `setTurnScreenOn`, `setShowWhenLocked`,
`FLAG_TURN_SCREEN_ON` or wake-lock use. With the screen off the warning is already
invisible, so an overlay to hide it buys very little. A stale `VolumeCtrlAlert`
activity can sit in the back stack and surface on the next wake — which is what
`am start`-based testing leaves behind.

## Detecting playback: no single platform API works

Both obvious answers are wrong on this device, and they are wrong in opposite directions.
Getting this right needs all three sources below.

### Sony's music is invisible to `getActivePlaybackConfigurations()`

This is what 1.5.0 shipped, and it dropped the volume mid-song. Sampled simultaneously
while a track was audibly playing:

| Source | Says |
|---|---|
| `dumpsys media_session` | `com.sony.walkman.highresmediaplayer` → `state=PLAYING(3)` |
| `dumpsys media.audio_flinger` | offload thread `AudioOut_135`, `1 Tracks of which 1 are active`, client pid 2395, `Usg=1` |
| `dumpsys audio` → `players:` | **nothing** — two idle `SoundPool` entries |

The audio runs on a native offload AudioTrack that is never registered with the platform's
`PlaybackActivityMonitor`, so it never enters the player list. What *does* enter the list is
a ~1 second `android.media.AudioTrack` that Sony's player creates at playback start and at
every track change, then releases.

That blip is why the API looks correct under test: a player really does appear the instant
you press play — this is what 1.5.0's "restore 3–6 ms after the audio track starts"
measured — and then vanishes while the song continues. Thirty seconds after the screen goes
off, suppression fires on top of live audio.

**The blip is a valid trigger and an invalid state.** Use `onPlaybackConfigChanged` to mean
"re-evaluate now"; never read an empty player list as silence.

### `isMusicActive()` never misses audio, but lags

`AudioManager.isMusicActive()` sees the offload track, so it has no false negatives. It was
observed still returning `true` more than ten minutes after playback ended, which makes it
useless as a positive signal — as a sole source it works from a cold start and then never
arms again. It is sound as a veto only.

(Measured later: pausing tears the `AudioOut_135` thread down entirely, so the stickiness
may be narrower than that first observation suggested. Not worth relying on either way.)

### The MediaSession is the only source that distinguishes playing from paused

Verified on device: `PLAYING(3)` while playing, `PAUSED(2)` immediately on pause, `position`
advancing. Reading it requires `MediaSessionManager.getActiveSessions()`, which accepts only
callers holding `MEDIA_CONTENT_CONTROL` (signature|privileged — unavailable to a sideloaded
app) or an **enabled notification listener**. Hence `VolGuardNotificationListener`, which
exists purely as the `ComponentName` token and handles no notifications.

`com.sony.songpal.localplayer.playbackservice.playstatechanged` is not an alternative: it is
sent with an explicit `cmp=` targeting Sony's own widgets, so no third party can receive it.

### What VolGuard does

```
configs say playing            -> playing        (catches other apps, and Sony's start blip)
else session available         -> trust it       (the only play/pause authority)
else                           -> isMusicActive() (conservative veto; may never arm)
```

An **empty session list is "unavailable", not "idle"**. A player that drops its session while
its audio track keeps writing would otherwise read as silence — the same mistake, one layer up.

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
  be swept from adb — only a bound client can set it. Raising the level for a test is
  therefore a manual step on the device. Note that a hardware volume change does not
  produce a `SafeVolume: setVolume:` line the way a binder write does; `timer start.` is
  the only confirmation that the level went above the threshold.

### Silent exposure rig

`isTimerConditionActive()` never checks playback, so the budget can be burned at full rate
with **nothing playing and no sound at all**: headphones plugged in but off the ears, master
raised above the threshold, screen kept awake (`adb shell svc power stayon true`) so idle
suppression never arms. This is the only way to reach a genuine trip without hours of loud
listening, and it carries no hearing risk until the clamp itself fires.

Measured with that rig on the NW-A306:

- The polling timer ticks **once per 60 s**, logging `count up: <total ms>`; each tick adds
  ~60,000 ms. The value is the cumulative total, not a delta.
- **The timer does not run at master 71.** No `timer start.` or `count up:` appears anywhere
  at that level with headphones connected, and the timer arms the moment the level is
  raised. So the threshold on this unit/output is above 71 — meaning at 71 the budget never
  burns, the dialog can never fire, and idle suppression buys nothing. It is insurance for
  listening above the threshold, not a general saving. Worth knowing before attributing any
  value to it.
- Restore `svc power stayon` and drop the master back to a normal level afterwards. A rig
  left at maximum is a real hazard the next time the headphones go on.
