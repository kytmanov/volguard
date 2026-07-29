# Early Safe-Volume Accept Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When Sony’s “Check volume level” dialog appears, immediately send the same accept broadcast OK would send (clearing the safe-volume gate and allowing volume restore), then still auto-tap OK after Sony’s ~3s UI delay so the dialog dismisses.

**Architecture:** Keep the existing accessibility-only service. On dialog detect: (1) `sendBroadcast(AVC_CHECK_LEVEL_OK, PERM_MASTER_VOLUME)`, (2) restore STREAM_MUSIC early, (3) poll/tap OK as today for UI cleanup. Do **not** re-arm `alertActive` until OK is clicked or the poll ceiling is hit—early restore must not clear the in-dialog lock.

**Tech Stack:** Java 11, Android API 34, plain `build.sh` (aapt2/d8/apksigner), no new dependencies.

## Global Constraints

- No new packages, libraries, or build tools beyond the existing Android SDK build-tools + JDK 17 used by `build.sh`.
- Surgical changes only: touch manifest, service, README, version metadata; no refactors of unrelated code.
- Permission `com.sony.walkman.volumectrlpanel.PERM_MASTER_VOLUME` is `prot=normal` (install-time grant); declare it, do not request at runtime.
- Match Sony’s sender call: `sendBroadcast(intent, "com.sony.walkman.volumectrlpanel.PERM_MASTER_VOLUME")`.
- Preserve current fallback: if OK never appears, still restore volume and re-arm.
- Device target: Sony NW-A300 series, non-root.
- Hearing-safety disclaimer in README stays; wording may note the dialog UI can still show briefly.

## File map

| File | Role |
|------|------|
| `AndroidManifest.xml` | Declare `PERM_MASTER_VOLUME` |
| `src/com/local/volguard/VolGuardService.java` | Early accept broadcast + early restore + re-arm timing fix |
| `src/com/local/volguard/MainActivity.java` | Optional one-line user-facing description update |
| `README.md` | Document new behavior and residual 3s UI overlay |
| `build.sh` | Bump `--version-code` / `--version-name` for release |

No new source files required. No automated unit-test harness exists; verification is build + device adb checks.

---

### Task 1: Manifest permission + version bump

**Files:**
- Modify: `AndroidManifest.xml`
- Modify: `build.sh` (version only)

**Interfaces:**
- Consumes: none
- Produces: install-time grant of `com.sony.walkman.volumectrlpanel.PERM_MASTER_VOLUME` for package `com.local.volguard`; APK version `2` / `1.1.0`

- [ ] **Step 1: Add uses-permission next to the existing audio permission**

In `AndroidManifest.xml`, change the permission block to:

```xml
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
    <!-- Required to send Sony's accept broadcast (sender must hold this; prot=normal). -->
    <uses-permission android:name="com.sony.walkman.volumectrlpanel.PERM_MASTER_VOLUME"/>
```

- [ ] **Step 2: Bump version in `build.sh`**

Find the `aapt2 link` line and set:

```bash
  --min-sdk-version 26 --target-sdk-version 34 --version-code 2 --version-name 1.1.0 \
```

- [ ] **Step 3: Commit**

```bash
git add AndroidManifest.xml build.sh
git commit -m "$(cat <<'EOF'
chore: declare PERM_MASTER_VOLUME and bump to 1.1.0

Needed so VolGuard can send Sony's AVC_CHECK_LEVEL_OK accept broadcast.
EOF
)"
```

---

### Task 2: Early accept broadcast + restore re-arm fix

**Files:**
- Modify: `src/com/local/volguard/VolGuardService.java`

**Interfaces:**
- Consumes: dialog package `com.sony.walkman.VolumeCtrlAlert` (unchanged)
- Produces:
  - `private static final String ACTION_CHECK_LEVEL_OK = "com.sony.walkman.VolumeCtrlAlert.AVC_CHECK_LEVEL_OK"`
  - `private static final String PERM_MASTER_VOLUME = "com.sony.walkman.volumectrlpanel.PERM_MASTER_VOLUME"`
  - `private static final int EARLY_RESTORE_DELAY_MS = 100`
  - `private void acceptSafeVolume()` — sends accept broadcast
  - `private void restoreVolume(int target)` — sets stream volume only (no re-arm)
  - `private void finishHandling()` — schedules `alertActive = false` after `REARM_DELAY_MS`
- Behavior contract on alert:
  1. `alertActive = true`
  2. `target = chooseTarget()`
  3. `acceptSafeVolume()`
  4. post `restoreVolume(target)` after `EARLY_RESTORE_DELAY_MS`
  5. `tryClickOk(0, target)` (still polls for UI OK)
  6. On OK click or max polls: `restoreVolume(target)` again, then `finishHandling()`

- [ ] **Step 1: Update class javadoc and constants**

Replace the class-level comment and add constants after `ALERT_PKG`:

```java
/**
 * Watches only Sony's "Check volume level" dialog
 * (com.sony.walkman.VolumeCtrlAlert). When it appears it:
 * 1) immediately sends AVC_CHECK_LEVEL_OK (same signal as tapping OK) so
 *    IzmAudioManager.disableSafeVolume() runs without waiting,
 * 2) restores STREAM_MUSIC early (volume was forced down to ~default),
 * 3) waits out Sony's ~3s GONE→VISIBLE OK button and taps it to clear the UI.
 */
```

```java
    private static final String ACTION_CHECK_LEVEL_OK =
            "com.sony.walkman.VolumeCtrlAlert.AVC_CHECK_LEVEL_OK";
    private static final String PERM_MASTER_VOLUME =
            "com.sony.walkman.volumectrlpanel.PERM_MASTER_VOLUME";

    private static final int POLL_MS = 200;     // how often to look for the OK button
    private static final int MAX_POLLS = 60;     // ~12s ceiling (Sony delays OK a few s)
    private static final int EARLY_RESTORE_DELAY_MS = 100;
    private static final int RESTORE_DELAY_MS = 350; // after OK click, settle then restore
    private static final int REARM_DELAY_MS = 600;
```

- [ ] **Step 2: Add `acceptSafeVolume()` and split re-arm out of restore**

Add methods (place above `restoreVolume` / replace existing `restoreVolume`):

```java
    /**
     * Mirrors VolumeCtrlAlertActivity's OK path: notify the volume panel so it
     * calls IzmAudioManager.disableSafeVolume() and clears mSafeVolumeAlertFlag.
     * Does not dismiss the dialog UI (OK stays GONE for ~3s).
     */
    private void acceptSafeVolume() {
        try {
            Intent i = new Intent(ACTION_CHECK_LEVEL_OK);
            sendBroadcast(i, PERM_MASTER_VOLUME);
            Log.i(TAG, "sent " + ACTION_CHECK_LEVEL_OK);
        } catch (Exception e) {
            Log.e(TAG, "accept broadcast failed", e);
        }
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
        }
    }

    private void finishHandling() {
        handler.postDelayed(new Runnable() {
            @Override public void run() { alertActive = false; }
        }, REARM_DELAY_MS);
    }
```

- [ ] **Step 3: Wire alert handler — early accept + early restore + poll OK**

Replace `onAccessibilityEvent` body after package checks with:

```java
        if (alertActive) return; // already handling this dialog
        alertActive = true;
        final int target = chooseTarget();
        Log.i(TAG, "alert appeared; target volume=" + target);
        acceptSafeVolume();
        handler.postDelayed(new Runnable() {
            @Override public void run() { restoreVolume(target); }
        }, EARLY_RESTORE_DELAY_MS);
        tryClickOk(0, target);
```

- [ ] **Step 4: Change `tryClickOk` success/failure to re-restore + finishHandling**

Replace the clicked / max-polls branches:

```java
        if (clicked) {
            Log.i(TAG, "OK clicked (attempt " + attempt + ")");
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    restoreVolume(target); // idempotent; covers late drops
                    finishHandling();
                }
            }, RESTORE_DELAY_MS);
            return;
        }
        if (attempt >= MAX_POLLS) {
            Log.w(TAG, "OK button never became clickable; restoring volume anyway");
            restoreVolume(target);
            finishHandling();
            return;
        }
```

Leave the poll `postDelayed(... tryClickOk ...)` unchanged.

- [ ] **Step 5: Build**

```bash
ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools" \
JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo "$JAVA_HOME")" \
bash build.sh
```

Expected: `Built: .../volguard.apk` with exit 0.

- [ ] **Step 6: Commit**

```bash
git add src/com/local/volguard/VolGuardService.java
git commit -m "$(cat <<'EOF'
feat: accept safe-volume gate immediately, then dismiss OK UI

Send AVC_CHECK_LEVEL_OK as soon as the dialog appears so
disableSafeVolume runs without waiting for Sony's 3s GONE button.
Still poll/tap OK to clear the overlay; re-arm only after that.
EOF
)"
```

---

### Task 3: Docs + MainActivity copy

**Files:**
- Modify: `README.md`
- Modify: `src/com/local/volguard/MainActivity.java` (status text only)

**Interfaces:**
- Consumes: behavior from Task 2
- Produces: user-facing description that matches reality (early gate clear; residual UI wait)

- [ ] **Step 1: Update README “What VolGuard does”**

Replace the numbered list under `## What VolGuard does` with:

```markdown
A tiny accessibility service, scoped to **only** the `com.sony.walkman.VolumeCtrlAlert`
dialog. When that dialog appears it:

1. immediately sends Sony’s accept broadcast (`AVC_CHECK_LEVEL_OK`) so the audio
   engine clears the safe-volume gate without waiting for the OK button,
2. restores the music volume the dialog dropped (to your pre-drop level, or max),
3. waits out Sony’s delayed OK button (the control is `View.GONE` for ~3 seconds),
   then taps OK to dismiss the dialog UI.

You may still see the dialog on screen for those few seconds; the volume dip should
be much shorter than waiting for OK before restoring. Completely removing the
dialog still needs root.

It needs no internet permission and watches no other app. It declares Sony’s
normal `PERM_MASTER_VOLUME` only so it can send the accept broadcast the stock
OK button would send.
```

- [ ] **Step 2: Update MainActivity help text**

In `MainActivity.java`, set the TextView string to:

```java
        tv.setText("VolGuard auto-dismisses Sony's \"Check volume level\" dialog "
                + "and restores the music volume it lowers.\n\n"
                + "It accepts the safe-volume gate immediately, then taps OK once "
                + "Sony enables the button (~3s) so the dialog can close.\n\n"
                + "If it ever stops working, enable VolGuard below under Accessibility.");
```

- [ ] **Step 3: Commit**

```bash
git add README.md src/com/local/volguard/MainActivity.java
git commit -m "$(cat <<'EOF'
docs: describe early safe-volume accept and residual OK wait
EOF
)"
```

---

### Task 4: Device verification + PR

**Files:**
- None (verification + git/gh only)
- Built artifact: `volguard.apk`

**Interfaces:**
- Consumes: Tasks 1–3 on a connected NW-A300 series device
- Produces: passing manual checks; open PR against `master`

- [ ] **Step 1: Install and enable on device**

```bash
DEV=$(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /:/ {print $1; exit}')
# if multiple devices, set DEV= to the Walkman serial explicitly
adb -s "$DEV" install -r volguard.apk
adb -s "$DEV" shell settings put secure enabled_accessibility_services \
  com.local.volguard/com.local.volguard.VolGuardService
adb -s "$DEV" shell settings put secure accessibility_enabled 1
adb -s "$DEV" shell dumpsys package com.local.volguard | grep -A2 PERM_MASTER_VOLUME
```

Expected: `PERM_MASTER_VOLUME: granted=true`.

- [ ] **Step 2: Log-based verification when dialog is triggered**

Trigger the real dialog (raise Walkman master volume past the safety threshold with headphones / SE path as you normally would). Then:

```bash
adb -s "$DEV" logcat -d | grep -E 'VolGuard|disableSafeVolume|AVC_CHECK'
```

Expected sequence (order within ~few hundred ms of dialog appear):

1. `VolGuard: alert appeared; target volume=...`
2. `VolGuard: sent com.sony.walkman.VolumeCtrlAlert.AVC_CHECK_LEVEL_OK`
3. `SafeVolume: disableSafeVolume` (or `IzmAudioManager: disableSafeVolume`)
4. `VolGuard: restored music volume to ...`
5. ~3s later: `VolGuard: OK clicked (attempt ...)`
6. second restore log (idempotent)

Also confirm subjectively: loudness returns before or without needing to wait for the OK button press.

- [ ] **Step 3: Regression — Back still does not dismiss; OK still required for UI**

Optional: temporarily comment nothing; with VolGuard running, if dialog is visible, Back should not be needed (service taps OK). Without early accept (old APK), quiet period lasts full 3s; with new APK, quiet period should shrink.

- [ ] **Step 4: Open PR**

```bash
git push -u origin HEAD
gh pr create --title "feat: accept safe-volume gate immediately" --body "$(cat <<'EOF'
## Summary
- On dialog appear, send Sony’s `AVC_CHECK_LEVEL_OK` broadcast (requires normal `PERM_MASTER_VOLUME`) so `disableSafeVolume()` runs without waiting for the OK button.
- Restore STREAM_MUSIC early; keep polling/tapping OK after Sony’s ~3s `View.GONE` delay to clear the UI.
- Re-arm only after OK (or poll timeout), so early restore does not drop the in-dialog lock.

## Background
Device reverse-engineering on NW-A306 showed OK is GONE for a hardcoded 3000ms; coordinate taps and Back fail early; a third-party app holding `PERM_MASTER_VOLUME` can deliver the same accept broadcast OK would send.

## Test plan
- [x] `bash build.sh` succeeds
- [ ] Install on NW-A300; permission granted
- [ ] Trigger check-volume dialog; logcat shows accept + `disableSafeVolume` before OK click
- [ ] Volume dip is shorter; dialog still auto-dismisses after ~3s
- [ ] Service still scoped to `com.sony.walkman.VolumeCtrlAlert` only

EOF
)"
```

---

## Self-review

1. **Spec coverage:** Early broadcast ✓, early restore ✓, still tap OK ✓, permission ✓, re-arm timing ✓, docs ✓, PR ✓.
2. **Placeholders:** None intentionally left.
3. **Consistency:** Action/permission string constants match decompiled Sony names exactly; `finishHandling` is the only re-arm path after Task 2.

## Out of scope (do not do in this PR)

- Master-volume AIDL / binding to `IzmAudioManager` (STREAM_MUSIC restore already ships and works for current users).
- Force-dismissing the dialog before 3s (would risk `AVC_NON_CHECK_LEVEL_OK` re-show if done wrong).
- Root / LSPosed / patching system APKs.
- Automated emulator tests (no emulator for Walkman volume stack).
