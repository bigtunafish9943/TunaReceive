# Status

Phase 1, nearly done. Updated 2026-11-24.

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
- Colours, theme and wording match TunaStream. Defaults suit a TV: 1080p, the stream's own screen, balanced frame pacing.
- Updater (`utils/TunaUpdater.java`): checks this repo's latest release on start (at most every six hours) and from Settings. Installs only the exact APK for the device's ABI (or universal), only when its SHA-256 matches GitHub's published digest, and through Android's package installer, which also refuses any APK not signed with the same key.
- First-run setup (`preferences/SetupActivity.java`), also under Settings > Run setup again. Picks resolution, frame rate, bitrate (from the detected connection), controller and sound.
- Settings opens on the everyday choices; "Show all settings" reveals the rest.
- Signing key made and the four Actions secrets set (2026-11-24). The key is not in git; the owner keeps the only copy.

## Not done

- No APK has been installed on a Shield from this repo.
- Not paired with TunaStream. Controller not checked.
- Artemis features that should already speak TunaStream's language are not verified on a device: virtual display request, clipboard sync, server commands, per-device permissions. Do not patch around them on the client. If one fails on the Shield, log it here.
- Phase 2 (8BitDo Ultimate 2 Wireless rumble) has not started. Check whether Artemis already claims that adapter before writing a driver.
- Other languages still say Artemis in places. English launcher name does not.
- In-app help links still open the Moonlight wiki. That is upstream documentation, not the app name.
- `ArtemisStreaming` clipboard identifier is unchanged on purpose. Clipboard sync with the host depends on it.

## Next

1. Install the first signed release that APK on the Shield beside Moonlight. Pair with TunaStream. Confirm the controller.
2. Write the Shield result here, with logcat if something fails.
