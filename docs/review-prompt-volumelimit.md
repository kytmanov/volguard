# Review Prompt — Make Sony's Volume-Limit Warning Unnoticeable (VolGuard)

You are reviewing an Android app, **VolGuard**, at `/Users/alexander/Projects/volguard`.
A prior investigation (findings below) established what is and isn't possible. Your job is to
**critically review the goal, the evidence, and the remaining approaches, then recommend the
best path** — not to accept the prior conclusions at face value. Push back where the evidence
is thin.

## The goal (what "done" means)

On a Sony NW-A306 Walkman, listening to music at high volume, the EU safe-volume system
periodically interrupts with a "Check volume level" warning (clamps volume + shows a dialog).
Make this **completely unnoticeable to the user**:

1. **Zero human action** — the user never has to tap anything, ever.
2. **No visible dialog** — the warning must not appear on screen (or be gone before it can be
   perceived).
3. **No audible disruption** — no volume dip, pause, or stutter the user would notice during
   playback.

Constraints: **no root**, **no LSPosed/Xposed**, one-time `adb` setup is acceptable. Do not
break the device (it is the owner's daily player) — every device-state change must be
reversible and every risky binder call must target a *named, understood* method (never
brute-probe unknown transactions on the vendor audio HAL — that can bootloop or corrupt
amplifier calibration).

## What the app does today (working baseline: v1.3.1, reactive)

Whole app is two Java files: `src/com/local/volguard/VolGuardService.java` (an
`AccessibilityService`, ~770 lines) + `MainActivity.java`. Build with
`bash build.sh` (needs **JDK 17** at `/opt/homebrew/Cellar/openjdk@17/*/…/Home`;
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`). No Gradle, no tests, no emulator.

It binds two private Sony services and acts as a raw-binder client:
- `com.sony.walkman.izmaudiomanager` → `IIzmAudioManager` (interface token
  `com.sony.walkman.izmaudiomanager.IIzmAudioManager`)
- `com.sony.walkman.volumectrlpanel` → `IMasterVolumeService`

On a clamp it: registers an (unprotected) Izm event listener to hear the clamp, disables the
current SafeVolume gate, sends `AVC_CHECK_LEVEL_OK` as a foreground broadcast, restores the
exact pre-clamp master volume (0–120 scale) in ~44–70 ms, and taps Sony's own OK button once
it becomes tappable (~3.5 s — Sony hard-codes the button `GONE` for 3000 ms). This already
achieves near-zero human action, but the **dialog is still visible for ~3.5 s** and the
**volume clamps then restores** (a brief audible artifact).

## Verified mechanism (from decompiling `/system_ext/priv-app/IzmAudioManager/` with `dexdump`)

- The dialog is **SafeVolume** (`...izmaudiomanager.volume.SafeVolume`), fired by
  `onSafeVolumeConfirmRequired`, gated by `IzmVolumeManager.isSafeVolumeEnabled()` which reads
  the persisted property `SAFE_VOLUME_ENABLED`.
- It is **cumulative-exposure**, not an instantaneous threshold: a polling timer
  (`startPollingTimer`, `mTimeCounter`) accumulates loud-listening time while volume is above
  `SAFE_VOLUME_THRESHOLD_*`; when `isTimeOver()` (`mTimeCounter.getTotalTimeMsec()` ≥
  `SAFE_VOLUME_TIME_LIMIT_MINUTES`) it fires. Live log line: `D SafeVolume: count up: <ms>`.
  Takes **hours** of loud listening to trigger naturally.
- The tunables (`SAFE_VOLUME_TIME_LIMIT_MINUTES`, `SAFE_VOLUME_ALERT_TIME_DEBUG`) live in a
  **HIDL HAL** `izm.android.properties.V1_0.IProperties` (SELinux-locked); the accumulator is
  in Sony's private SharedPrefs `izm_audio_preferences`. **Both non-root-writable.**
- Verified `IIzmAudioManager` txn codes: `setVolume=3`, `getVolume=4`, `getMaxVolume=5`,
  `setEnabledAvls=7`, `getEnabledAvls=8`, `getAvlsThreshold=9`, `disableSafeVolume=10`,
  `registListener=86`, `unregistListener=87`. **AVLS is a separate user max-volume limiter,
  not the dialog driver** (device shows `settings system avls = 0` yet still clamps).

## Approaches already tried and DISPROVEN (do not repeat without new evidence)

1. **Pre-emptive `disableSafeVolume` (txn 10), held/re-asserted** — FAILS. Proven on device:
   the exposure counter (`count up`) kept advancing while the app called `disableSafeVolume`
   during loud playback, so it clears the confirm gate but does **not** stop the accumulator.
   Worse, it **regressed** playback to pause-without-resume. (This build was reverted.)
2. **`setEnabledAvls(false)` (txn 7)** — wrong subsystem; AVLS ≠ SafeVolume.
3. **Write `SAFE_VOLUME_ENABLED=false`** — the real switch, but HIDL/SharedPrefs, non-root.
4. **`pm disable-user` the `VolumeCtrlAlert` dialog package/Activity** — BLOCKED:
   `SecurityException: Shell cannot change component state` (protected system component); the
   `enable` counterpart is equally blocked, so a successful disable couldn't be rolled back.
5. **AOSP `settings put global audio_safe_volume_state`** — already `1` (DISABLED); Sony's
   stack is independent, so it's a no-op.

**Prior verdict: non-root *prevention* of the dialog is not achievable.** The reactive
dismiss+restore is the only viable non-root path. Review this verdict critically, then focus
on making the reactive path *unnoticeable*.

## Test harness (no waiting hours, no hearing risk)

- **Force the dialog instantly:** `adb shell am start -n
  com.sony.walkman.VolumeCtrlAlert/.VolumeCtrlAlertActivity`. This shows the real dialog and
  drives the full reactive path in ~60 ms. Use it to iterate on dismissal/overlay. (It
  bypasses the accumulator, so it does not test the trigger — only the handling.)
- **Watch the exposure counter live:** `adb logcat` and grep `SafeVolume` / `VolGuard`.
  `SafeVolume: count up: <ms>` shows the accumulator advancing; `timer polling start./stop.`
  shows arming.
- **Clean app-off baseline:** `adb shell settings put secure enabled_accessibility_services ""`
  (save and restore the original value: `com.local.volguard/com.local.volguard.VolGuardService`).
- Device currently connected (adb serial `1017620`); its SafeVolume counter is saturated from
  testing, so the dialog fires quickly right now — a convenient (temporary) fast-repro state.

## What to actually review / decide

Given prevention is out, the goal reduces to **hiding the reactive handling**. Assess:

1. **Cosmetic overlay (V-1):** a `TYPE_APPLICATION_OVERLAY` full-screen view drawn the instant
   the alert window appears and torn down when it disappears, so the ~3.5 s dialog is never
   seen while accept+restore run underneath. Needs `SYSTEM_ALERT_WINDOW` (one Settings toggle,
   grantable via `adb appops`/`pm grant`). Evaluate: is the overlay reliably up before the
   dialog is perceptible? Does it cover status bar / all insets? Any focus-stealing issues?
2. **Audible-artifact minimization:** the clamp lowers volume for tens of ms before restore.
   Is that perceptible? Can the restore be made click-free (e.g. avoid the clamp being heard)?
   Does the Walkman player *pause* on clamp, and can that be avoided?
3. **The open question that matters most for "unnoticeable":** does the exposure counter ever
   **reset**, and does our broadcast-based accept reset it the way a real OK tap would? If a
   genuine tap resets the counter but our `AVC_CHECK_LEVEL_OK` broadcast does not, the app may
   be causing the dialog to re-fire *more often*, not less. Investigate in the decompile
   (`SafeVolume` accept/reset path) and on device (does `count up` reset after our accept vs a
   manual tap?). This may be the difference between "occasional" and "constant" interruptions.
4. **One-time-`adb` levers not yet exhausted:** e.g. `appops`, `pm suspend`, or granting the
   app `WRITE_SECURE_SETTINGS` — any non-root way to reduce dialog frequency or reset the
   counter. Reconfirm C-1's block (was only tested via `pm disable-user`).
5. **Whether the goal is even fully reachable non-root**, and if not, state the honest ceiling.

## Resources

- Persistent findings memory:
  `/Users/alexander/.claude/projects/-Users-alexander-Projects-volguard/memory/avls-prevention-vector.md`
- Full brainstorm + approach list:
  `/Users/alexander/.claude/plans/review-the-app-brainstorm-effervescent-karp.md`
- Decompiled txn map + logs were in a session scratchpad (not persisted); re-derive with
  `adb pull /system_ext/priv-app/IzmAudioManager/IzmAudioManager.apk` + `dexdump -d`.

## Deliverable

A prioritized recommendation: which of (1)–(4) to implement, in what order, to get closest to
zero-action + invisible + inaudible; the expected ceiling non-root; concrete on-device
verification steps for each; and any risks to the device or the user's hearing. Do not write
code until the approach is agreed. Keep the working 1.3.1 reactive baseline intact as fallback.
