# TunaReceive

Play your PC's games on an Android TV box, phone or tablet, streamed from [TunaStream](https://github.com/bigtunafish9943/TunaStream). Made first for the NVIDIA Shield TV.

**Install:** download the APK ending in `arm64-v8a` from the [latest release](https://github.com/bigtunafish9943/TunaReceive/releases/latest) and open it on the device. After that, TunaReceive updates itself.

The app id is `com.tunastream.receive`, so it installs next to Moonlight and Artemis.

## What it adds

### A stream that stays smooth on Wi-Fi

Paired with TunaStream, TunaReceive watches every frame and tells the PC how the stream is going, so the stream
adjusts while you play instead of freezing when the Wi-Fi gets busy.

- **It eases off before anything is lost.** Frames that start arriving late are the first sign of a struggling
  connection, well before packets go missing. TunaReceive spots them and the PC lowers the bitrate straight away,
  then raises it again when the connection clears.
- **The picture size follows, without a freeze.** When the bitrate has dropped too far for a sharp 4K picture,
  the PC switches to 1440p or 1080p between two frames and the TV stretches it to fill the screen. It returns to
  full size when there is room. *Test a smaller picture* in the quick menu shows you what that looks like.
- **TV game mode.** While a game is on screen, the TV is asked to skip its picture processing (HDMI ALLM), which
  takes noticeable delay out of many TVs. It can be turned off in Settings.
- **Sound that doesn't drop out.** The sound buffer starts as small as it can and grows a little each time a
  packet arrives late, so a clean connection keeps the lowest delay and an uneven one stops going silent.
- **Even frame pacing** by default, and a dropped stream reconnects by itself.
- **How the last stream went**, in plain words, from the PC's menu: frame rate, frames that arrived late, lost
  video, delay, and the PC's side of the same stream. The last five streams are kept. TunaStream's home page shows
  the TV's view of the stream next to its own figures.

Needs TunaStream 0.1.105 or later; split-frame encoding and the rest of the PC's side are described in
[TunaStream's README](https://github.com/bigtunafish9943/TunaStream#smooth-on-wi-fi).

### Rumble that works

- **8BitDo Ultimate 2 Wireless through its 2.4G adapter.** Android reads this controller's buttons but can't rumble
  it. During a stream TunaReceive takes the adapter over and drives it directly, following the same report layout
  SDL uses, so rumble, motion controls and the battery level all work, and the controller still drives the menus.
  Rumble is refreshed five times a second, so a long rumble doesn't fade out early. Needs controller firmware 1.03
  or later.
- **Controllers in Xbox mode** get the start-up message Windows' own driver sends, which some adapters (8BitDo's
  among them) wait for before sending any input.
- **Check my controller** in Settings shows what the controller is, how it is connected and whether it can rumble,
  with the fix when something's wrong. On the PC, *Rumble now* on TunaStream's Troubleshooting page buzzes it
  directly.

### Easy from the couch

- **Setup on first start.** A few questions, answered with the controller, choose the picture size, frame rate,
  picture quality, controller and sound. On a 4K TV with 5 GHz Wi-Fi it suggests 1440p, which TVs upscale well
  and Wi-Fi carries more reliably. Settings opens on the everyday choices; the rest are a switch away.
- **Straight to the games.** With one paired PC it opens that PC's games on start, waking the PC if it's asleep.
- **Connection test.** After pairing, it measures how fast the PC can send to the device and suggests a picture
  quality with headroom.
- **Couch menu.** Hold the controller's Home button for the quick menu.
- **From the couch:** repair the PC's virtual screen, and receive files sent with TunaStream's *Send to device*.
- **Pop-ups you can read**, with clear Yes and No buttons on the Shield.
- **Five colour themes** (Deep Sea, Coral Reef, Kelp Forest, Midnight, Golden Hour). By default TunaReceive wears
  whichever one the PC uses; turn that off in Settings to pick its own.
- **Updates itself** from this repo's releases: only the APK for the device, only when its SHA-256 matches
  GitHub's published checksum, and Android only installs it when it is signed with the same key.
- **Game covers** on the tiles: TunaStream finds Steam's cover for each Steam game, and the pictures refresh by
  themselves every few days.
- **Controller battery** in the quick menu's title, and on TunaStream's home page.
- **Stream stats in plain words.**

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
