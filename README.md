# VolGuard

Tiny accessibility service for Sony NW-A300 series. When the EU "Check volume level"
dialog shows up, it dismisses it and puts master volume back where it was.

No root. [v1.2.0](../../releases/tag/v1.2.0)

## Disclaimer

Use at your own risk. No warranty. Loud volume can damage hearing. Not affiliated
with Sony.

## Problem

On EU firmware, past a certain volume Sony shows a confirmation dialog and drops
master volume (usually to ~50). You have to wait for OK, tap it, then crank the
volume back up. Every time. The check is baked into Sony's volume stack; without
root you can't remove it, only work around it.

## What it does

Watches only `com.sony.walkman.VolumeCtrlAlert`. On appear:

1. Sends the same accept broadcast as the OK button (`AVC_CHECK_LEVEL_OK`)
2. Restores master volume (0–120) to the level from before the drop
3. Taps OK once Sony enables the button (~3s) so the dialog goes away

You'll still see the dialog briefly. Volume comes back in about 1.3–1.4s on my
A306. Fully removing the dialog needs root.

## Timing (A306, v1.2.0)

Five real clamps, hands-off after trigger. Time is from Sony's clamp until master
volume is back at the pre-drop level:

| # | Before | Dropped to | Restored | ms |
|--:|-------:|-----------:|---------:|---:|
| 1 | 80 | 50 | 80 | 1368 |
| 2 | 80 | 50 | 80 | 1357 |
| 3 | 80 | 50 | 80 | 1418 |
| 4 | 80 | 50 | 80 | 1403 |
| 5 | 80 | 50 | 80 | 1358 |

min / median / max: **1357 / 1368 / 1418 ms**

First-time safe volume often trips around ~80 on this unit, not 120. Restore
target is whatever you had before the drop.

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
