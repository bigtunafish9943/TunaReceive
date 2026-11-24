# TunaReceive

Android and Android TV client for [TunaStream](https://github.com/bigtunafish9943/TunaStream). The first device is an NVIDIA Shield TV Pro. Phones come second.

This is a fork of [Artemis](https://github.com/ClassicOldSong/moonlight-android) by ClassicOldSong, which is Moonlight for Android extended for an Apollo-style host. TunaStream grew out of Apollo, so those client features stay as Artemis wrote them. GPL-3.0. See `NOTICE` and `LICENSE.txt`.

The app id is `com.tunastream.receive`. It installs next to Moonlight and next to Artemis. Java packages stay `com.limelight` so upstream merges stay possible.

## What it adds

- **Setup on first start.** A few questions, answered with the controller, choose the picture size, frame rate,
  picture quality, controller and sound. Settings opens on the everyday choices; the rest are a switch away.
- **Updates itself** from this repo's releases: only the APK for the device, only when its SHA-256 matches
  GitHub's published checksum, and Android only installs it when it is signed with the same key.
- **Straight to the games.** With one paired PC it opens that PC's games on start, waking the PC if it's asleep.
- **Connection test.** After pairing, it measures how fast the PC can send to the device and suggests a picture
  quality with headroom (needs TunaStream's `/tuna/speedtest`).
- **Couch menu.** Hold the controller's Home button for the quick menu.
- **Stream stats in plain words**, and TunaStream's look: navy, cyan, the tuna mark.

## What this repo is not

Do not put TunaStream changes here. If the client needs something from the PC, write it in `docs/host-requests.md`. Claude maintains TunaStream.

Do not commit a keystore, a signing password, or a GitHub token.

## Build

Submodules are required (`moonlight-common-c` and enet).

```
git clone --recurse-submodules https://github.com/bigtunafish9943/TunaReceive.git
cd TunaReceive
./gradlew :app:assembleNonRoot_gameRelease
```

The Shield APK is the `arm64-v8a` split. CI renames it to `TunaReceive-<version>-arm64-v8a.apk`.

A release signature comes from GitHub Actions secrets, not from this tree. The one-time command is in `docs/signing.md`. Until those secrets exist, CI can still build an installable test APK. That test signature will not update over the real release. Uninstall the test build first.

## Status

`docs/status.md` is the phase note. It is not a claim that a Shield has already paired.

## Credits

TunaReceive is built on the work of others, and keeps their copyright notices and license:

- [Artemis](https://github.com/ClassicOldSong/moonlight-android) by ClassicOldSong, which TunaReceive began as a
  copy of: virtual display requests, clipboard sync, server commands and many client improvements come from it.
- [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android), which Artemis is built on, by
  [Cameron Gutman](https://github.com/cgutman), [Diego Waxemberg](https://github.com/dwaxemberg),
  [Aaron Neyer](https://github.com/Aaronneyer), [Andrew Hennessy](https://github.com/yetanothername) and the
  Moonlight contributors. Moonlight was started as a project at [MHacks](http://mhacks.org) by students at
  [Case Western](http://case.edu).

TunaReceive is free software under the GPL-3.0; see `LICENSE.txt` and `NOTICE`.
