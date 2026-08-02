The music starts itself again after a warning. Includes 1.6.0, which was never published.

**What's new**
- VolGuard presses play after a warning, usually within a second. Music you paused yourself is never started.
- Fixed: the volume could drop in the middle of a song. Listening with the screen off, VolGuard read playback state from a source that always reported nothing playing, and lowered the volume about 30 s in.
- Pressing play restores your level in about 40 ms with the screen off. You no longer have to wake the player.
- Confirmed VolGuard causes no extra warnings. When it confirms one for you, Sony's exposure counter resets exactly as it would if you tapped OK yourself.

**Update**
```sh
adb install -r volguard.apk
```

Coming from 1.5.0 or earlier, also grant notification access. It is the only way to read playback state on this player:

```sh
adb shell cmd notification allow_listener com.local.volguard/com.local.volguard.VolGuardNotificationListener
```

Or on the player: **Settings → Notifications → Device & app notifications → VolGuard → Allow**.

VolGuard does not read your notifications. Android puts playback state behind this permission; the listener itself is empty. Without it, your volume is still restored, but the music will not restart and the idle saving rarely applies.

Accessibility usually survives an update. If VolGuard goes quiet, re-enable it under **Settings → Accessibility → VolGuard**.

**Known issues**
- The music takes 0.35 to 1.2 s to come back. Sony's player refuses a play command until it has finished stopping. VolGuard adds about 50 ms on top.
- The idle saving needs the screen off and works on wired output only.
- Sony counts only the time your volume spends above a threshold, and that threshold is high. At level 71 on the test unit the counter never moved, so nothing accumulates and the idle saving has nothing to save. It earns its keep only if you listen loud.

Sony NW-A300 series (Android 14), tested on NW-A306. No root.
Loud music damages hearing. Use at your own risk.
