# VolGuard

Tiny accessibility service for Sony NW-A300 series. When the EU "Check volume level"
dialog shows up, it dismisses it and puts master volume back where it was.

No root. [v1.3.0](../../releases/tag/v1.3.0)

## Disclaimer

Use at your own risk. No warranty. Loud volume can damage hearing. Not affiliated
with Sony.

## Problem

On EU firmware, past a certain volume Sony shows a confirmation dialog and drops
master volume (usually to ~50). You have to wait for OK, tap it, then crank the
volume back up. Every time. The check is baked into Sony's volume stack; without
root you can't remove it, only work around it.

## What it does

Registers on `IzmAudioManager`'s event listener, so it learns about the clamp the
moment Sony makes it rather than waiting for the dialog to draw. On a clamp:

1. Waits for `VolumeCtrlPanel` to raise its safe-volume flag, so accepting can't
   race ahead of it and leave the flag stuck (which would floor the volume)
2. Sends the same accept broadcast as the OK button (`AVC_CHECK_LEVEL_OK`), as a
   foreground broadcast — on the normal queue it sat for ~500ms
3. Restores master volume (0–120) to the level from before the drop
4. Taps OK so the dialog goes away without re-launching itself

The dialog still appears — removing it needs root. But volume is back before it
finishes drawing, so in practice you see the dialog, not the volume drop.

## Timing (A306, v1.3.0)

Three real clamps, hands-off after trigger. t=0 is Sony's clamp
(`requestSafeVolumeConfirm`):

| # | clamp seen | volume restored | dialog dismissed |
|--:|-----------:|----------------:|-----------------:|
| 1 |  13 ms |  139 ms |  3663 ms |
| 2 |   9 ms |  127 ms |  3573 ms |
| 3 |   6 ms |  124 ms |  3612 ms |

Volume is back in **~130ms**; v1.2.0 took 1357-1418 ms. The dialog itself reports
`Displayed +800ms`, so the level is restored before it is on screen.

Dismissal stays at ~3.6s because Sony keeps the OK button `GONE` for a hard-coded
3000ms after the dialog resumes. Nothing short of root changes that — but by then
the volume has been correct for three and a half seconds.

After the accept, safe volume is INACTIVE: verified taking master 80 -> 120 with
no clamp and no dialog.

Safe volume trips at master 80 on this unit, not 120. Restore target is whatever
you had before the drop.

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
