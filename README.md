# TunaReceive

Play your PC's games on an Android TV box, phone or tablet, streamed from [TunaStream](https://github.com/bigtunafish9943/TunaStream). Made first for the NVIDIA Shield TV.

This is TunaReceive 1.0, the first complete version, played on every day on an NVIDIA Shield TV with an 8BitDo
controller. Other devices have had little testing yet.

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
- **59.94 and 119.88 FPS.** Most TVs really refresh at 59.94 Hz, so a 60 FPS stream has to drop a frame every
  few seconds, which shows as a small hitch when panning. Picking the TV's own rate removes it, and TunaStream
  makes its virtual screen run at exactly that rate. The check before each game suggests it when the TV has no
  true 60 Hz mode.
- **Sharpen the picture** (experimental, off unless turned on). Light, Medium or Strong contrast-adaptive
  sharpening, applied at the stream's own size before the TV scales it up, so 720p to 1440p streams look less
  soft without bright outlines. Skipped for HDR and 4K, and falls back to the normal picture if the TV's
  graphics can't run it.
- **A check before each game.** About a second measuring the connection to the PC, then suggestions that would
  play better on this TV: picture size, frame rate, bitrate, adjusting to the Wi-Fi, and HDR or 10-bit off where
  the TV can't use them. Each is a tick box: *Use checked*, *Use all* or *Keep mine*; *Skip* starts at once. A
  suggestion turned down isn't repeated for a week. Settings > Check settings before each game.
- **Stream details over the game.** View + R3 (or the quick menu) shows picture size and codec, frame rate set,
  now and on average and what the game draws on the PC, delay, how steady the stream is, bitrate, the TV's Wi-Fi
  and the PC's side, updated every second. TunaStream's phone page shows the same.
- **How the last stream went**, in plain words, from *PC tools*: frame rate, frames that arrived late, lost
  video, delay, and the PC's side of the same stream. The last five streams are kept. TunaStream's home page shows
  the TV's view of the stream next to its own figures.

Works with TunaStream; everything above needs TunaStream 1.0. Split-frame encoding and the rest of the PC's side are described in
[TunaStream's README](https://github.com/bigtunafish9943/TunaStream#smooth-on-wi-fi).

### Rumble that works

- **8BitDo Ultimate 2 Wireless through its 2.4G adapter.** Android reads this controller's buttons but can't rumble
  it. During a stream TunaReceive takes the adapter over and drives it directly, following the same report layout
  SDL uses, so rumble, motion controls and the battery level all work, and the controller still drives the menus.
  Rumble is refreshed five times a second, so a long rumble doesn't fade out early. Needs controller firmware 1.03
  or later.
- **Controllers in Xbox mode** get the start-up message Windows' own driver sends, which some adapters (8BitDo's
  among them) wait for before sending any input.
- **Fix stick drift** (off unless turned on). When a worn stick rests near the centre for a moment, its resting
  spot becomes the new centre, so the camera stops creeping. Unlike a bigger deadzone, small movements still
  count and full tilt still reaches the edge.
- **Check my controller** in Settings shows what the controller is, how it is connected and whether it can rumble,
  with the fix when something's wrong. On the PC, *Rumble now* on TunaStream's Troubleshooting page buzzes it
  directly.

### Fun on the couch

- **Screenshots and instant replay.** View + R1 saves a screenshot, here in Pictures/TunaReceive and on the PC.
  View + L1 asks the PC to save the last 30 seconds as a clip. Both are in the quick menu too.
- **Controller shortcuts your way.** Settings > Controller shortcuts: pick one and press the buttons you want on
  your controller, any of them (triggers, Home, Share and paddles included), put it back as it came, or turn it
  off. The quick menu, screenshot, replay, stream details and ending the stream are all there.
- **Messages and sounds from the PC.** What's typed on TunaStream's home page or its phone page pops up over the
  game, and sound-board sounds play.
- **Continue.** The game you played last is first, marked Continue; the one running is marked Playing.
- **Player numbers.** Each controller that joins says which player it is.
- **A tip per game.** If a game ran below full frame rate or had to drop quality last time, a one-line suggestion
  appears a few seconds into the next stream. Nothing is changed for you.
- **Screen saver.** Your game covers drifting by, with the time. Settings > Screen saver opens the TV's screen
  saver settings, where you pick TunaReceive game covers.
- **Your games on TunaHome.** [TunaHome](https://github.com/bigtunafish9943/TunaHome) can show your PC's games
  as a row on the TV's home screen, box art and all, most recently played first; select one to start it. Hidden
  games stay hidden.
- **Nine colour themes**, the seasonal ones (Halloween, Autumn, Winter, Summer) included, following the PC or
  picked here.

### Easy from the couch

- **Setup on first start.** A few questions, answered with the controller, choose the picture size, frame rate,
  picture quality, controller and sound. On a 4K TV with 5 GHz Wi-Fi it suggests 1440p, which TVs upscale well
  and Wi-Fi carries more reliably. Settings opens on the everyday choices; the rest are a switch away.
- **Straight to the games.** With one paired PC it opens that PC's games on start, waking the PC if it's asleep.
- **Connection test.** After pairing, it measures how fast the PC can send to the device and suggests a picture
  quality with headroom.
- **Couch menu.** Hold the controller's Home button for the quick menu (or set your own buttons).
- **PC tools and Settings** at the top of the games: your week, how the last stream went, fitting the picture
  quality to the connection, repairing the PC's virtual screen, and backup and restore, all in one place.
- **Settings for each game.** Hold a game and choose *Settings for this game* to give it one of your settings
  profiles (4K with HDR for a slow game, 1080p at 120 for a shooter). It's used whenever that game streams;
  every other game keeps your usual settings.
- **Send logs to PC.** *PC tools > Send logs to PC* sends TunaReceive's recent log, with the TV's model and
  settings, to TunaStream's Troubleshooting page. Problems can be looked into without a cable or adb.
- **What's new.** After an update, TunaReceive shows what changed since the version this TV last ran, once.
- **English, Spanish, French and German.** Settings > Language, or the TV's own language.
- **Settings from the PC.** TunaStream's Devices > TV settings changes this TV's settings from the PC, where
  there's room to explain each one; the TV picks them up within 15 seconds and says what changed.
- **Files from the PC** sent with TunaStream's *Send to device* arrive in Downloads by themselves.
- **Pop-ups you can read**, with clear Yes and No buttons on the Shield.
- **TunaStream's look.** The fish mark and name in every header, a home screen tile that sits cleanly in the
  launcher's row, and by default whichever colour theme the PC uses (TunaStream's own Desktop and Steam tiles
  included); turn that off in Settings to pick its own.
- **Backup and restore.** From the PC's menu, back up TunaReceive's settings (and, if you tick it, its pairings)
  to that PC, and restore them later or on a new TV.
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
