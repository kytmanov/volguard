# VolGuard

Tiny accessibility service for Sony NW-A300 series. It handles the EU "Check volume
level" dialog for you and puts the volume back where you had it.

No root. [v1.3.1](../../releases/tag/v1.3.1)

## Disclaimer

Use at your own risk. No warranty. Loud volume can damage hearing. Not affiliated
with Sony.

## Problem

On EU firmware, once you pass a certain volume Sony drops the volume to about 50
and shows a confirmation dialog. The OK button stays greyed out for three seconds.
You wait, tap OK, then turn the volume back up. Every time.

The check lives inside Sony's own volume stack. Without root you cannot remove it,
only react to it.

## What you get

- The volume comes back on its own, to the exact level you had. If you were at
  120 you get 120 back, not a default.
- You do not have to tap anything. The dialog closes itself.
- The dialog still appears. Removing it needs root. It clears after about three
  and a half seconds, and the volume is correct long before that.

## How it works

VolGuard listens to Sony's audio service directly, so it hears the volume drop as
it happens instead of waiting for the dialog to show up. It sends the same accept
signal the OK button sends, writes your old volume back, then taps OK once Sony
enables the button.

It only reacts to `com.sony.walkman.VolumeCtrlAlert`, and only ever presses that
dialog's own OK button.

## Timing (A306)

Four real clamps, at levels 80, 85 and 120, timed from Sony's volume drop. The
volume was back in 44-70 ms, and the dialog closed itself after about 3.5 s.
v1.2.0 took about 1.4 s to restore.

The 3.5 s is Sony's fixed three second delay before the OK button becomes usable.

Safe volume trips at master 80 on this unit, not 120.

## Install

Grab `volguard.apk` from [Releases](../../releases):

```sh
adb install volguard.apk
adb shell settings put secure enabled_accessibility_services com.local.volguard/com.local.volguard.VolGuardService
adb shell settings put secure accessibility_enabled 1
```

Or enable **Settings → Accessibility → VolGuard** by hand. Use `-s <serial>` if
you have more than one adb device.

## Build

Android SDK build-tools 34 + platform 34, **JDK 17** (newer JDKs break `d8`):

```sh
ANDROID_HOME=/path/to/android-sdk JAVA_HOME=/path/to/jdk17 bash build.sh
```

## License

[MIT](LICENSE).
