# TV Explorer for Android TV

A small Android TV app to browse and watch free-to-air live TV channels from public lists.
It is inspired by [tvexplorer.live](https://tvexplorer.live/) and uses the same visual style as
[Retro Launcher](https://github.com/aescuderos/retro-launcher-for-android-tv), with a red accent.
Channels are played inside the app using LibVLC; a separate VLC app is not required.

## Disclaimer

- This app **does not host, produce, or distribute any content**. It only reads **publicly available channel lists** and hands the stream URLs to a video player.
- The lists come from third parties: [iptv-org](https://github.com/iptv-org/iptv), [TDTChannels](https://www.tdtchannels.com/) and [Free-TV/IPTV](https://github.com/Free-TV/IPTV). Their content and availability are outside the control of this project.
- **The author takes no responsibility for the content played through the app**, its legality in your country, or its availability and quality. You are solely responsible for what you watch.
- The software is provided "as is", without warranty of any kind (see [LICENSE](LICENSE)).
- Channel names and logos belong to their respective owners. This project is not affiliated with any broadcaster or with the sources above.

## AI disclosure

This app was built with the assistance of an AI coding tool, under the direction and review of the author.

## Features

- Browse by country, category (and regional categories for Spain), search and favourites.
- Responsive phone layout: narrow screens use a two-column grid (three on wider
  compact screens) and touch controls for country, search, favourites and categories.
  TV and wide-screen layouts retain the sidebar and five-column grid.
- Merges several public sources and removes duplicates, keeping several alternative URLs per channel.
- **Stream verification:** country views probe streams (manifest plus first media chunk) and cache results for 12 hours. This is not a guarantee of playback; the all-countries view and favourites can include unverified streams.
- Built-in LibVLC video player with accessible icon controls for touch and remote:
  pause/play, alternative streams, fullscreen, return and license information.
  Fullscreen requests landscape orientation; exiting restores the previous orientation.
  It tries other URLs when a stream fails. Playback stops when the app goes to the background.
- Remote and gamepad friendly: **A** select, **B** back, **L1 / R1** switch category; long-press/menu/Y/X offers alternative streams.
- Java UI with LibVLC, `minSdk 21`, Leanback launcher entry with banner.

## Requirements

- Android TV / Android device with internet access.

## Build

Requires JDK 17, the Android SDK (platform `android-33`, build-tools `33.x`), curl, zip and unzip. No Gradle.

```
mkdir -p deps
curl -fL -o deps/libvlc-all-3.6.2.aar https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.6.2/libvlc-all-3.6.2.aar
ANDROID_SDK=~/Android/Sdk ./build.sh
adb install -r out/tv-explorer.apk
```

A debug keystore is generated on first build at `~/.android/tv-explorer.jks` (override with `KEYSTORE=` and `KEYSTORE_PASSWORD=`). Keep the same signing key to update an existing installation. The build verifies the LibVLC AAR's SHA-256; it embeds separate native libraries for arm64, armv7, x86 and x86_64.

## Phone layout smoke test

With the app installed, a populated country selected and an unlocked phone:

```
ADB=/path/to/adb ANDROID_SERIAL=<device> python3 tests/portrait_smoke.py
```

Tests portrait controls, favorites, search and rotation to/from the wide layout.
It restores the previous rotation setting and removes the favorite it adds.
`tests/player_smoke.py` checks the integrated player on the same device with a live channel.

## How it works

On first run the app downloads the lists, merges them into a compact local catalogue (refreshed every 14 days or via
"Actualizar lista"), then probes the streams of the selected country in parallel before showing them.
The all-countries view can also show unverified channels, favourites are not filtered by verification, and
search checks up to 300 matches. A stream can stop working after a successful check.
Streams that need a custom user agent/referrer, YouTube links and NSFW or closed channels are skipped.

## Project layout

```
AndroidManifest.xml
src/com/tvexplorer/Catalog.java        download, merge and stream probing
src/com/tvexplorer/MainActivity.java   UI, verification, player launch
src/com/tvexplorer/PlayerActivity.java integrated video playback
res/                                   icon and TV banner
build.sh                               Gradle-less build script
```

## License

The app's own code is MIT (see [LICENSE](LICENSE)). It embeds unmodified
[LibVLC for Android 3.6.2](https://code.videolan.org/videolan/vlc-android), copyright
VideoLAN and contributors, licensed under LGPL 2.1 or later. The LGPL text is included
in `assets/licenses/LGPL-2.1.txt` and can be opened from the player.
[Android binding sources](https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.6.2/libvlc-all-3.6.2-sources.jar)
and [VLC sources](https://code.videolan.org/videolan/vlc) are published upstream.
Before distributing an APK, verify the corresponding source and redistribution obligations
for the exact LibVLC build and its included native dependencies.
