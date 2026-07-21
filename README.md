# VolGuard

Auto-dismisses Sony Walkman's "Check volume level" dialog and restores the music
volume it lowers — on NW-A300-series players, without root.

## ⚠️ Disclaimer — use at your own risk

**By installing and running VolGuard you accept all risk.** It is provided "AS IS",
with no warranty of any kind. The author is **NOT responsible for anything** that
results from its use — including hearing damage, device malfunction, data loss, or
any other harm. If you do not accept this, do not install it.

VolGuard auto-dismisses a volume-safety confirmation. **Prolonged high-volume
listening can permanently damage your hearing.**

Not affiliated with or endorsed by Sony. "Walkman" and model names are trademarks
of Sony, used only to describe compatibility.

## The problem

On Sony NW-A300-series players (EU firmware), raising volume past a threshold
triggers a "Check volume level" dialog that you must acknowledge. The dialog also
drops media volume to ~50% each time. The gate is built into Sony's volume panel
and audio engine; it **cannot be removed without root** (its state lives in the
engine's private storage, thresholds are read-only system properties, and the
disable hook is a signature-protected internal API).

## What VolGuard does

A tiny accessibility service, scoped to **only** the `com.sony.walkman.VolumeCtrlAlert`
dialog. When that dialog appears it:

1. immediately sends Sony’s accept broadcast (`AVC_CHECK_LEVEL_OK`) so the audio
   engine clears the safe-volume gate without waiting for the OK button,
2. restores **Walkman master volume** (0–120, via Sony’s `MasterVolumeService`) to
   **exactly the level you had before Sony lowered it** (tracked from volume
   changes; large forced drops are treated as clamps and ignored for the target),
3. waits out Sony’s delayed OK button (the control is `View.GONE` for ~3 seconds),
   then taps OK to dismiss the dialog UI.

You may still see the dialog on screen briefly; master volume is restored as soon
as the panel accepts the broadcast (typically ~1s). Completely removing the dialog
still needs root.

It needs no internet permission and watches no other app. It declares Sony’s
normal `PERM_MASTER_VOLUME` so it can send the accept broadcast and talk to the
volume panel service.

## Compatibility

- Sony NW-A306 / NW-A300 series, Android 14 (API 34).
- Non-root.

## Install (prebuilt APK)

Download `volguard.apk` from [Releases](../../releases), then:

```sh
adb install volguard.apk
adb shell settings put secure enabled_accessibility_services com.local.volguard/com.local.volguard.VolGuardService
adb shell settings put secure accessibility_enabled 1
```

(If multiple adb devices are attached, add `-s <serial>` to each command.)

You can also enable it manually under **Settings → Accessibility → VolGuard**.

## Build from source

Requires the Android SDK build-tools (34.0.0) + platform 34, and **JDK 17**
(newer JDKs crash `d8`). Then:

```sh
ANDROID_HOME=/path/to/android-sdk JAVA_HOME=/path/to/jdk17 bash build.sh
```

This produces `volguard.apk` (signed with a generated debug key).

## License

[MIT](LICENSE).
