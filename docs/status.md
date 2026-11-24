# Status

Phase 1, not done. Updated 2026-11-24.

## Base

Fork of Artemis (`ClassicOldSong/moonlight-android`), branch `moonlight-noir`. Not stock Moonlight. The GitHub repo is already named `bigtunafish9943/TunaReceive`. Nothing was renamed on GitHub.

## Done in the tree

- Release application id is `com.tunastream.receive`. Root flavor is `com.tunastream.receive.root`. Neither is `com.limelight`.
- Launcher name is TunaReceive. Debug builds use a `.debug` suffix so they do not overwrite a release install.
- Launcher icons and the Android TV banner use the TunaStream mark on `#041431`. Accent used for the banner wordmark is `#0cc8f6`.
- English update line points at this repo's releases, not Artemis.
- Performance-log share no longer addresses `artemistics.logs@gmail.com`. The file stays on the device unless someone picks where to send it.
- Virtual-display error no longer tells the user to install Apollo. The request itself is unchanged.
- CI workflow builds an APK on push. A GitHub Release is published only from `main` or `moonlight-noir`, and only when the signing secrets exist.
- Java package names are still `com.limelight`.

## Not done

- No APK has been installed on a Shield from this repo.
- Not paired with TunaStream. Controller not checked.
- Artemis features that should already speak TunaStream's language are not verified on a device: virtual display request, clipboard sync, server commands, per-device permissions. Do not patch around them on the client. If one fails on the Shield, log it here.
- Phase 2 (8BitDo Ultimate 2 Wireless rumble) has not started. Check whether Artemis already claims that adapter before writing a driver.
- Other languages still say Artemis in places. English launcher name does not.
- In-app help links still open the Moonlight wiki. That is upstream documentation, not the app name.
- `ArtemisStreaming` clipboard identifier is unchanged on purpose. Clipboard sync with the host depends on it.
- Signing secrets are not created. No keystore is in git.

## Next

1. Owner creates the keystore and the four Actions secrets (`docs/signing.md`).
2. Let Actions build `TunaReceive-*-arm64-v8a.apk`.
3. Install that APK on the Shield beside Moonlight. Pair with TunaStream. Confirm the controller.
4. Write the Shield result here, with logcat if something fails.
