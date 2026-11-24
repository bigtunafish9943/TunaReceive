# TunaReceive

Android and Android TV client for [TunaStream](https://github.com/bigtunafish9943/TunaStream). The first device is an NVIDIA Shield TV Pro. Phones come second.

This is a fork of [Artemis](https://github.com/ClassicOldSong/moonlight-android) by ClassicOldSong, which is Moonlight for Android extended for an Apollo-style host. TunaStream grew out of Apollo, so those client features stay as Artemis wrote them. GPL-3.0. See `NOTICE` and `LICENSE.txt`.

The app id is `com.tunastream.receive`. It installs next to Moonlight and next to Artemis. Java packages stay `com.limelight` so upstream merges stay possible.

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
