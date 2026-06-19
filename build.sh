#!/usr/bin/env bash
# Build, align, and sign volguard.apk with no Gradle — just the Android
# build-tools (aapt2/d8/zipalign/apksigner) + javac.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

: "${ANDROID_HOME:=$(brew --prefix)/share/android-commandlinetools}"
BT_DIR="$ANDROID_HOME/build-tools"
BT="$BT_DIR/$(ls "$BT_DIR" | sort -V | tail -1)"   # newest build-tools
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
JT="${JAVA_TARGET:-11}"                              # bytecode level d8 accepts

AAPT2="$BT/aapt2"; D8="$BT/d8"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "build-tools=$BT"

OUT="$HERE/build"
rm -rf "$OUT"; mkdir -p "$OUT/classes"

# 1) compile + link resources -> base.apk (+ generated R.java)
"$AAPT2" compile --dir res -o "$OUT/compiled.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$PLATFORM" \
  --manifest AndroidManifest.xml --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 --version-code 1 --version-name 1.0 \
  "$OUT/compiled.zip"

# 2) compile java against android.jar
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source "$JT" -target "$JT" -nowarn -encoding UTF-8 \
  -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

# 3) dex
( cd "$OUT/classes" && jar cf "$OUT/classes.jar" . )
"$D8" --release --min-api 26 --lib "$PLATFORM" --output "$OUT" "$OUT/classes.jar"

# 4) bundle dex into the apk, align, sign
cd "$OUT"
cp base.apk unsigned.apk
zip -uj unsigned.apk classes.dex >/dev/null
"$ZIPALIGN" -f 4 unsigned.apk aligned.apk

KS="$HERE/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias volguard -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=VolGuard" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$HERE/volguard.apk" aligned.apk

echo "Built: $HERE/volguard.apk"
