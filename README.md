# TV Explorer for Android TV

A small, dependency-free Android TV app to browse and watch free-to-air live TV channels from public lists.
It is inspired by [tvexplorer.live](https://tvexplorer.live/) and uses the same visual style as
[Retro Launcher](https://github.com/aescuderos/retro-launcher-for-android-tv), with a red accent.
Channels are played in an external player (VLC by default).

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
- Merges several public sources and removes duplicates, keeping several alternative URLs per channel.
- **Stream verification:** only channels whose stream really answers (manifest plus first media chunk) are shown. Results are cached for 12 hours.
- Remote and gamepad friendly: **A** select, **B** back, **L1 / R1** switch category; long-press/menu/Y/X offers alternative streams.
- Pure Java (Android framework only), `minSdk 21`, Leanback launcher entry with banner.

## Requirements

- Android TV / Android device with internet access.
- [VLC for Android](https://www.videolan.org/vlc/) (recommended); any app that handles `video/*` links also works.

## Build

Requires JDK 17 and the Android SDK (platform `android-33`, build-tools `33.x`). No Gradle.

```
ANDROID_SDK=~/Android/Sdk ./build.sh
adb install -r out/tv-explorer.apk
```

A debug keystore is generated on first build at `~/.android/tv-explorer.jks` (override with `KEYSTORE=`).

## How it works

On first run the app downloads the lists, merges them into a compact local catalogue (refreshed every 14 days or via
"Actualizar lista"), then probes the streams of the selected country in parallel before showing them.
Streams that need a custom user agent/referrer, YouTube links and NSFW or closed channels are skipped.

## Project layout

```
AndroidManifest.xml
src/com/tvexplorer/Catalog.java        download, merge and stream probing
src/com/tvexplorer/MainActivity.java   UI, verification, player launch
res/                                   icon and TV banner
build.sh                               Gradle-less build script
```

## License

MIT (see [LICENSE](LICENSE)).
