#!/usr/bin/env bash
# Builds a signed APK without Gradle. Requires: JDK 17, Android SDK (platform android-33 + build-tools 33.x).
# Usage: ANDROID_SDK=~/Android/Sdk ./build.sh [versionCode] [versionName]
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:?set ANDROID_SDK}
BT=$(ls -d "$SDK"/build-tools/33.* | tail -1)
JAR=$SDK/platforms/android-33/android.jar
VC=${1:-3}; VN=${2:-1.2}
KS=${KEYSTORE:-$HOME/.android/tv-explorer.jks}
KSPASS=${KEYSTORE_PASSWORD:-android}
export KEYSTORE_PASSWORD_EFFECTIVE="$KSPASS"
AAR=${LIBVLC_AAR:-deps/libvlc-all-3.6.2.aar}
if [ ! -f "$AAR" ]; then
  echo "Missing LibVLC AAR: $AAR (see README.md)" >&2
  exit 1
fi
echo "cf6042f4a0c520a85f850e73b7dd8f68b70aff1923364f2b16d87f30f48168bd  $AAR" | sha256sum -c
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias tvx -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=TV Explorer" -storepass "$KSPASS" -keypass "$KSPASS"
fi
rm -rf out && mkdir -p out/cls out/gen
unzip -q "$AAR" classes.jar 'jni/*' 'assets/*' -d out/libvlc
mkdir -p out/libvlc/assets/licenses
cp assets/licenses/LGPL-2.1.txt out/libvlc/assets/licenses/
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 33 --version-code "$VC" --version-name "$VN" --java out/gen -A out/libvlc/assets out/res.zip
javac --release 8 -cp "$JAR:out/libvlc/classes.jar" -d out/cls out/gen/com/tvexplorer/R.java src/com/tvexplorer/*.java
"$BT/d8" --lib "$JAR" --min-api 21 --output out out/cls/com/tvexplorer/*.class out/libvlc/classes.jar
mkdir -p out/lib
cp -r out/libvlc/jni/. out/lib/
(cd out && zip -q -r base.apk classes.dex lib)
"$BT/zipalign" -f 4 out/base.apk out/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass env:KEYSTORE_PASSWORD_EFFECTIVE --out out/tv-explorer.apk out/aligned.apk
echo "Built out/tv-explorer.apk"
