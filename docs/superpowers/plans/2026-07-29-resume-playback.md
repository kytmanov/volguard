# Resume Playback After A Safe-Volume Trip — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When Sony's safe-volume warning kills playback, get the music playing again fast enough that the user does not notice.

**Architecture:** Sony's `VolumeCtrlAlertActivity` takes `AUDIOFOCUS_GAIN` when it launches, so the player receives `AUDIOFOCUS_LOSS` and stops. VolGuard already restores the volume in ~46 ms; this adds playback. `beginTrip()` records which player was playing, and the `MediaController.Callback` that 1.6.0 already registers gains one branch that calls `getTransportControls().play()` the moment the stop arrives. No retry, no new permissions, no new fields beyond two strings.

**Tech Stack:** Java 11 → d8 → Android 14 (API 34). Single-file service at `src/com/local/volguard/VolGuardService.java`. Build with `bash build.sh`. No Gradle.

## Global Constraints

- **No new dependencies.** Standard library and existing Android APIs only.
- **No new permissions.** This rides on the notification access 1.6.0 already requires.
- **Java 11 source level** (`build.sh` passes `-source 11 -target 11`). No `var`, no switch expressions.
- **Match the file's existing style:** fully-qualified `android.media.session.*` names inline (the file has no imports for them), `try { … } catch (Exception ignore) { }` around binder calls, `Log.i`/`Log.w` with the `VolGuard` tag.
- **This repo has no test framework and no automated tests.** Every task's test cycle is an on-device verification with an exact command and expected log output. Do not add a test framework; that is a dependency decision for the repo owner.
- **A real firmware trip is not needed.** `am start` on the alert reproduces the exact focus-grab sequence — confirmed by a control run with the accessibility service disabled.

---

### Task 1: Capture which player was playing when the trip starts

**Files:**
- Modify: `src/com/local/volguard/VolGuardService.java` (fields near line 162; `beginTrip` at line 830; `finishHandling` at line 1257)

**Interfaces:**
- Consumes: existing `watchedControllers` (line 158), `isPlayingState(PlaybackState)` (line 532), `isPlaybackActive()`, `lastSessionPlaying` (line 162), `alertActive`.
- Produces: `private String resumePkg` — package name of the player to resume, or null. `private String playingPackage()` — returns the package of a controller currently reporting playback, else null. Task 2 consumes both.

- [ ] **Step 1: Add the two fields**

Directly after `private boolean lastSessionPlaying = false;` (line 162):

```java
    /** Package last seen playing, so a trip can tell what to put back. */
    private String lastPlayingPkg;
    /** Player to resume for the trip in progress; null means do nothing. */
    private String resumePkg;
```

- [ ] **Step 2: Add the lookup helper**

Immediately after the `isPlayingState` method (ends line 543, just before
`listenerComponent()` at line 545):

```java
    /** Package of a controller reporting playback right now, or null. */
    private String playingPackage() {
        for (android.media.session.MediaController c : watchedControllers) {
            try {
                if (isPlayingState(c.getPlaybackState())) return c.getPackageName();
            } catch (Exception ignore) { }
        }
        return null;
    }
```

- [ ] **Step 3: Keep `lastPlayingPkg` fresh from the existing callback**

In `controllerCallback.onPlaybackStateChanged` (line 355), insert immediately after
`boolean playing = isPlayingState(state);`:

```java
            if (playing) {
                String p = playingPackage();
                if (p != null) lastPlayingPkg = p;
            }
```

The callback instance is shared across controllers, so the argument cannot say which one
fired — hence the scan rather than reading the package off the callback.

- [ ] **Step 4: Capture at trip start**

In `beginTrip`, immediately after `alertSeen = false;` (line 846) — keeping it with the
other per-trip flag resets, and before `restoreTarget = choosePreClampTarget();`:

```java
        // Snapshot before Sony's alert takes audio focus and stops the player. On the
        // izm-confirm path this runs ~95 ms ahead of that, so the session still reads
        // as playing. On the dialog fallback path playback is already dead and this is
        // null, which is deliberate: a user-paused player is indistinguishable from a
        // dialog-killed one, so we decline to guess.
        resumePkg = (isPlaybackActive() || lastSessionPlaying) ? lastPlayingPkg : null;
```

- [ ] **Step 5: Clear it when the trip ends**

In `finishHandling`, inside the posted Runnable, immediately after `alertActive = false;`:

```java
                resumePkg = null;
```

- [ ] **Step 6: Add the capture to the trip log line**

In `beginTrip`, change the existing `Log.i(TAG, "trip via " + source + …)` call by appending
to its final string concatenation, so the line ends:

```java
                + " lastKnown=" + lastKnownMaster + " resumePkg=" + resumePkg + ")");
```

- [ ] **Step 7: Build**

Run: `bash build.sh`
Expected: `Built: /Users/alexander/Projects/volguard/volguard.apk`, no javac errors.

- [ ] **Step 8: Verify capture on device — playing**

```bash
adb install -r volguard.apk
adb shell settings put secure enabled_accessibility_services com.local.volguard/com.local.volguard.VolGuardService
adb shell settings put secure accessibility_enabled 1
adb shell input keyevent 224      # wake
adb shell input keyevent 126      # play
adb logcat -c
adb shell am start -n com.sony.walkman.VolumeCtrlAlert/.VolumeCtrlAlertActivity
sleep 3
adb logcat -d -s VolGuard:V | grep "trip via"
```

Expected: the `trip via` line ends with `resumePkg=com.sony.walkman.highresmediaplayer)`.

- [ ] **Step 9: Verify capture on device — paused**

```bash
adb shell input keyevent 127      # pause
sleep 2
adb logcat -c
adb shell am start -n com.sony.walkman.VolumeCtrlAlert/.VolumeCtrlAlertActivity
sleep 3
adb logcat -d -s VolGuard:V | grep "trip via"
```

Expected: the line ends with `resumePkg=null)`. This is the guard that stops VolGuard
starting music you deliberately paused — a trip while paused is a normal case, because
Sony's timer runs on volume alone.

- [ ] **Step 10: Commit**

```bash
git add src/com/local/volguard/VolGuardService.java
git commit -m "feat: capture which player was playing when a trip starts

Records resumePkg in beginTrip, before Sony's alert takes audio focus and stops
the player. Null on the dialog fallback path and when nothing was playing, so a
user-paused player is never mistaken for a dialog-killed one."
```

---

### Task 2: Resume playback the instant it stops

**Files:**
- Modify: `src/com/local/volguard/VolGuardService.java` (`controllerCallback.onPlaybackStateChanged` at line 355)

**Interfaces:**
- Consumes: `resumePkg` and `playingPackage()` from Task 1; existing `watchedControllers`, `isPlayingState`, `alertActive`.
- Produces: `private void resumePlayback()` — fires `play()` once on the captured player. Nothing else consumes it.

- [ ] **Step 1: Add the resume method**

Immediately after `playingPackage()` from Task 1:

```java
    /**
     * Put playback back after Sony's alert stopped it.
     *
     * VolumeCtrlAlertActivity requests AUDIOFOCUS_GAIN when it launches, so the player is
     * handed AUDIOFOCUS_LOSS and stops without resuming. Measured on device: a play issued
     * while the alert still holds focus makes the player reclaim focus and continue, and
     * the alert — being a dialog — is unaffected by losing it.
     *
     * Fires once, immediately, and only from the stop event. There is deliberately no
     * retry: either this is fast enough to go unnoticed, or playback has visibly stopped
     * and the user reaches for the button, in which case a late automatic play would
     * collide with their press and pause the track instead.
     */
    private void resumePlayback() {
        String pkg = resumePkg;
        resumePkg = null;
        if (pkg == null) return;
        for (android.media.session.MediaController c : watchedControllers) {
            try {
                if (!pkg.equals(c.getPackageName())) continue;
                if (isPlayingState(c.getPlaybackState())) return;
                c.getTransportControls().play();
                Log.i(TAG, "resumed playback for " + pkg);
                return;
            } catch (Exception e) {
                Log.w(TAG, "resume playback failed for " + pkg, e);
                return;
            }
        }
        Log.w(TAG, "resume playback: no controller for " + pkg);
    }
```

- [ ] **Step 2: Call it from the stop transition**

In `onPlaybackStateChanged`, the existing de-duplication gate at line 371 already lets a real
playing→stopped transition through. Immediately after `lastSessionPlaying = playing;`
(line 372), insert:

```java
            // Sony's alert has just killed playback mid-trip. Put it straight back.
            if (!playing && alertActive) resumePlayback();
```

Placement matters: this must sit **after** `lastSessionPlaying = playing;` so the transition
gate has already been passed, and **before** the existing `if (playing) endSuppression(...)`
line so the ordering of the untouched suppression path is unchanged.

- [ ] **Step 3: Build**

Run: `bash build.sh`
Expected: `Built: …/volguard.apk`, no javac errors.

- [ ] **Step 4: The acceptance test — listen to it**

```bash
adb install -r volguard.apk
adb shell input keyevent 224
adb shell input keyevent 126
sleep 2
adb logcat -c
adb shell am start -n com.sony.walkman.VolumeCtrlAlert/.VolumeCtrlAlertActivity
sleep 4
adb shell dumpsys media_session | grep -m1 -o "state=[A-Z]*([0-9])"
adb logcat -d -s VolGuard:V | grep -E "trip via|resumed playback"
```

Expected: `state=PLAYING(3)`, and a `resumed playback for com.sony.walkman.highresmediaplayer`
line. **Put the headphones on and run it again — the deciding question is whether it sounds
like a hiccup or like a stop.** That judgement is the acceptance criterion; no log value
substitutes for it.

- [ ] **Step 5: Measure the gap**

```bash
adb logcat -d -b main | grep -E "onAudioFocusChange|requestAudioFocus" | head -6
```

Expected: the alert's `requestAudioFocus`, then `onAudioFocusChange(-1)` to the player, then
the player's own `requestAudioFocus` reclaiming it. Record the milliseconds between the
`-1` and the player's request — that is the silence. For reference, a deliberately delayed
manual test measured 307 ms; driven from the callback this should be far smaller.

- [ ] **Step 6: Verify it does nothing when paused**

```bash
adb shell input keyevent 127
sleep 2
adb logcat -c
adb shell am start -n com.sony.walkman.VolumeCtrlAlert/.VolumeCtrlAlertActivity
sleep 4
adb shell dumpsys media_session | grep -m1 -o "state=[A-Z]*([0-9])"
adb logcat -d -s VolGuard:V | grep -c "resumed playback"
```

Expected: `state=PAUSED(2)` and a count of `0`. Nothing was playing, so nothing starts.

- [ ] **Step 7: Verify it degrades without notification access**

```bash
adb shell cmd notification disallow_listener com.local.volguard/com.local.volguard.VolGuardNotificationListener
adb shell input keyevent 126
sleep 2
adb logcat -c
adb shell am start -n com.sony.walkman.VolumeCtrlAlert/.VolumeCtrlAlertActivity
sleep 4
adb logcat -d -s VolGuard:V | grep -E "resume playback|resumed playback" | head -3
adb shell cmd notification allow_listener com.local.volguard/com.local.volguard.VolGuardNotificationListener
```

Expected: no crash, and no `resumed playback` line — `watchedControllers` is empty so
`resumePkg` was never captured. Volume restore must still work as before.

- [ ] **Step 8: Verify track position is not lost**

```bash
adb shell input keyevent 126
sleep 20
adb shell dumpsys media_session | grep -m1 -o "position=[0-9]*"
adb shell am start -n com.sony.walkman.VolumeCtrlAlert/.VolumeCtrlAlertActivity
sleep 5
adb shell dumpsys media_session | grep -m1 -o "position=[0-9]*"
```

Expected: the second position is greater than the first, not reset to a low number. If the
track restarts from zero, stop and report it — the spec leaves position to the player, and
that assumption would be wrong.

- [ ] **Step 9: Commit**

```bash
git add src/com/local/volguard/VolGuardService.java
git commit -m "feat: resume playback after Sony's safe-volume alert stops it

Sony's alert takes AUDIOFOCUS_GAIN on launch, so the player gets AUDIOFOCUS_LOSS
and stops without resuming. The resume is driven by the playback-stopped event
rather than by the accept: VolGuard finishes accept+restore 35 ms before the alert
takes focus, so resuming after the accept would be a no-op on still-playing music.

Fires once, no retry — a late automatic play would collide with the user's own
button press and pause the track instead."
```

---

### Task 3: Document and release

**Files:**
- Modify: `docs/safe-volume-mechanism.md` (the "A real trip, measured" section)
- Modify: `README.md` (the "What VolGuard does" list and the Timing table)
- Modify: `build.sh` (line 25, `--version-code` and `--version-name`)

**Interfaces:**
- Consumes: the measured gap from Task 2 Step 5.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Update the mechanism doc**

In `docs/safe-volume-mechanism.md`, find the paragraph ending
"`getTransportControls().play()` after dismissal would close that gap." and replace that
sentence with:

```markdown
  VolGuard closes this: `beginTrip` records which player was playing, and the MediaSession
  callback resumes it the instant the stop arrives. Measured gap between the player's
  `AUDIOFOCUS_LOSS` and it reclaiming focus: REPLACE_WITH_MEASURED_MS ms.
```

Replace `REPLACE_WITH_MEASURED_MS` with the number recorded in Task 2 Step 5. Do not commit
the placeholder.

- [ ] **Step 2: Update the README feature list**

In `README.md`, in the `## What VolGuard does` bullet list, add after the
"Restores the **exact** pre-drop master level…" bullet:

```markdown
- **Puts the music back.** Sony's warning dialog takes audio focus and stops playback;
  VolGuard restores both the level and the playback, so a warning does not leave you with
  correct volume and silence.
```

- [ ] **Step 3: Add the timing row**

In `README.md`, in the Timing table, add after the `Idle restore…` row:

```markdown
| Playback resumed after a warning | **REPLACE_WITH_MEASURED_MS ms** | v1.7.0 |
```

Use the same measured number. Do not commit the placeholder.

- [ ] **Step 4: Bump the version**

In `build.sh` line 25, change `--version-code 7 --version-name 1.6.0` to
`--version-code 8 --version-name 1.7.0`. New feature, so minor bump.

- [ ] **Step 5: Build and confirm the version**

```bash
bash build.sh
adb install -r volguard.apk
adb shell dumpsys package com.local.volguard | grep -m1 versionName
```

Expected: `versionName=1.7.0`.

- [ ] **Step 6: Commit**

```bash
git add docs/safe-volume-mechanism.md README.md build.sh
git commit -m "docs: VolGuard 1.7.0 — resume playback after a warning"
```

---

## Notes for the implementer

- **Do not add a retry loop.** It was considered and rejected on purpose: a resume landing
  seconds later is both noticeable and liable to collide with the user's own play press,
  which would pause the track. If the single attempt proves unreliable, report it rather
  than papering over it with retries.
- **Do not resume on dialog dismissal.** With the screen off, dismissal happens whenever the
  device is next woken — minutes later in a recorded run.
- **If the gap turns out to be audible**, the answer is not more retries but a different
  feature: steering trips into silence by tracking above-threshold time against the known
  1170-minute budget so warnings fire while nothing is playing. Out of scope here; report
  the measurement and stop.
- Full background: `docs/superpowers/specs/2026-07-29-resume-playback-design.md`.
