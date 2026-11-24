# TunaReceive

Play your PC's games on an Android TV box, phone or tablet, streamed from [TunaStream](https://github.com/bigtunafish9943/TunaStream). Made first for the NVIDIA Shield TV.

**Install:** download the APK ending in `arm64-v8a` from the [latest release](https://github.com/bigtunafish9943/TunaReceive/releases/latest) and open it on the device. After that, TunaReceive updates itself.

The app id is `com.tunastream.receive`, so it installs next to Moonlight and Artemis.

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

## Build

Submodules are required (`moonlight-common-c` and enet).

```
git clone --recurse-submodules https://github.com/bigtunafish9943/TunaReceive.git
cd TunaReceive
./gradlew :app:assembleNonRoot_gameRelease
```

The Shield APK is the `arm64-v8a` split. CI renames it to `TunaReceive-<version>-arm64-v8a.apk`.

Releases are signed in GitHub Actions; how to set up signing for your own copy is in `docs/signing.md`. Without signing secrets, CI builds a test APK with a throwaway key, which can't update over a release.

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
