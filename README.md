# VolGuard - Sony NW-A306 Volume cap fix

Sony NW-A300 players sold in the EU interrupt loud listening with a "Check volume
level" warning. The volume drops, a dialog appears, and the music stops until you
confirm and turn the volume back up.

VolGuard handles all of it for you. No root needed.

Latest release: [v1.7.0](../../releases/tag/v1.7.0)

## Requirements

- Sony NW-A300 series player, tested on the NW-A306
- A computer with `adb`, to install and switch it on
- Wired headphones. Bluetooth and USB are untested

## Install

Download `volguard.apk` from [Releases](../../releases), then:

```sh
adb install volguard.apk
adb shell settings put secure enabled_accessibility_services com.local.volguard/com.local.volguard.VolGuardService
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener com.local.volguard/com.local.volguard.VolGuardNotificationListener
```

Or on the player: **Settings → Accessibility → VolGuard**, then
**Settings → Notifications → Device & app notifications → VolGuard → allow**.

Both are needed. Accessibility lets VolGuard close the dialog. Notification access is
the only way to tell whether music is playing on this player. VolGuard does not read
your notifications.

Android sometimes switches accessibility services off after an app update. If VolGuard
goes quiet, turn it back on.

## What to expect

Warnings become rare. When one does arrive, you do nothing and it plays out like this:

```
0.0 s        the volume drops, the music stops, the dialog appears
0.05 s       your volume is back at the exact level you had
0.4 - 1.2 s  the music is playing again
3.5 s        the dialog closes itself
```

The music takes about a second to return because the player will not accept a play
command until it has finished stopping. That part is not VolGuard's to speed up.

Your volume is never lowered while you are listening.

The warning cannot be switched off for good. VolGuard makes it infrequent and takes
care of it when it comes.

## What VolGuard does

The player counts the time your volume spends above a set level, and warns you once the
total reaches its limit. That counter ignores whether anything is playing, and it keeps
running while the player sleeps. A paused player in your bag with the volume up uses the
allowance as fast as real listening does.

So while the screen is off and nothing is playing, VolGuard turns the volume down to the
player's own safe level. The counter stops, and your level comes back the moment you
wake the player or press play. This only ever happens in silence, so you do not hear it.

When a warning fires anyway, VolGuard confirms it, restores your volume, starts the
music again, and taps OK as soon as the player allows it.

Turning the volume down while idle pauses the counter. It does not rewind it. Only
confirming a warning resets the counter, and VolGuard's confirmation resets it exactly
as yours would.

[How it works](docs/how-it-works.md) has the details.

## If something is wrong

| What you see | What to do |
|---|---|
| Nothing happens at all | Turn VolGuard back on under Accessibility |
| Stopped working after an update | Android switched the service off; turn it back on |
| Volume stays low after a warning | Set your volume once with VolGuard running, so it knows your level |
| Warnings as often as before | The idle saving needs the screen off, and works on wired output only |
| Volume dropped in the middle of a song | Grant notification access, and update to 1.7.0 |
| Music quiet after the app crashed | Turn the screen on; your level is restored |

To see what VolGuard is doing:

```sh
adb logcat -s VolGuard:I
```

## Version history

| Version | What changed |
|---|---|
| 1.7.0 | The music starts itself again after a warning |
| 1.6.0 | Never turns the volume down while music is playing. Press play and your level returns instantly, screen still off. Needs notification access |
| 1.5.0 | Turns the volume down while the player is idle, so the allowance stops draining and warnings become rare |
| 1.3.1 | Restores your exact level rather than an approximate one |
| 1.3.0 | Volume back in 0.15 s instead of 1.4 s |
| 1.2.0 | Restores the Walkman volume itself, not just Android's |
| 1.1.0 | Confirms the warning straight away instead of waiting for the OK button |
| 1.0.0 | Closes the dialog and restores the volume |

## Build

Needs the Android SDK, build-tools and platform 34, and JDK 17. Newer JDKs break the
build.

```sh
ANDROID_HOME=/path/to/android-sdk PATH=/path/to/jdk17/bin:$PATH bash build.sh
```

Produces `volguard.apk`.

## Disclaimer

Use at your own risk. No warranty. Loud music damages hearing. Not affiliated with Sony.

## License

[MIT](LICENSE).
