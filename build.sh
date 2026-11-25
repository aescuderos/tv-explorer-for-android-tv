#!/usr/bin/env bash
# Builds a signed APK without Gradle. Requires: JDK 17, Android SDK (platform android-33 + build-tools 33.x).
# Usage: ANDROID_SDK=~/Android/Sdk ./build.sh [versionCode] [versionName]
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:?set ANDROID_SDK}
BT=$(ls -d "$SDK"/build-tools/33.* | tail -1)
JAR=$SDK/platforms/android-33/android.jar
VC=${1:-2}; VN=${2:-1.1}
KS=${KEYSTORE:-$HOME/.android/tv-explorer.jks}
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias tvx -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=TV Explorer" -storepass android -keypass android
fi
rm -rf out && mkdir -p out/cls
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 33 --version-code "$VC" --version-name "$VN" out/res.zip
javac --release 8 -cp "$JAR" -d out/cls src/com/tvexplorer/*.java
"$BT/d8" --lib "$JAR" --min-api 21 --output out out/cls/com/tvexplorer/*.class
(cd out && zip -q base.apk classes.dex)
"$BT/zipalign" -f 4 out/base.apk out/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out out/tv-explorer.apk out/aligned.apk
echo "Built out/tv-explorer.apk"
