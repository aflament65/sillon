#!/usr/bin/env bash
# Fabrique l'APK de Sillon à partir de index.html (lancé par GitHub Actions).
set -euo pipefail
RUN="${GITHUB_RUN_NUMBER:-1}"

# 1. Contenu web : index.html avec les bibliothèques embarquées (fonctionne hors connexion)
rm -rf www android
mkdir -p www/lib
cp node_modules/lamejs/lame.min.js www/lib/
cp node_modules/jszip/dist/jszip.min.js www/lib/
cp node_modules/@zxing/library/umd/index.min.js www/lib/zxing.min.js
mkdir -p www/lib/chromaprint
cp node_modules/rusty-chromaprint-wasm/dist/rusty_chromaprint_wasm_bg.js node_modules/rusty-chromaprint-wasm/dist/rusty_chromaprint_wasm_bg.wasm www/lib/chromaprint/
sed -e 's#https://cdn.jsdelivr.net/npm/lamejs@1.2.1/lame.min.js#lib/lame.min.js#' \
    -e 's#https://cdnjs.cloudflare.com/ajax/libs/jszip/3.10.1/jszip.min.js#lib/jszip.min.js#' \
    index.html > www/index.html
grep -q 'lib/lame.min.js' www/index.html
grep -q 'lib/jszip.min.js' www/index.html

# 2. Projet Android généré par Capacitor
npx cap add android
JAVA_DIR=android/app/src/main/java/fr/aflament/sillon
test -d "$JAVA_DIR"
cp android-src/MainActivity.java android-src/SillonPlugin.java "$JAVA_DIR/"

# Permissions : micro (et réglages audio pour la carte son USB)
MAN=android/app/src/main/AndroidManifest.xml
sed -i 's#<application#<uses-permission android:name="android.permission.RECORD_AUDIO" />\n    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />\n    <uses-permission android:name="android.permission.CAMERA" />\n\n    <application#' "$MAN"
grep -q RECORD_AUDIO "$MAN"
grep -q permission.CAMERA "$MAN"

# Accès aux dossiers choisis + numéro de version
GR=android/app/build.gradle
sed -i '0,/^dependencies {/s//dependencies {\n    implementation "androidx.documentfile:documentfile:1.1.0"\n    implementation "com.google.android.gms:play-services-code-scanner:16.1.0"/' "$GR"
sed -i -E "s/versionCode [0-9]+/versionCode ${RUN}/; s/versionName \"[^\"]*\"/versionName \"1.${RUN}\"/" "$GR"
grep -q documentfile "$GR"
grep -q code-scanner "$GR"

npx cap sync android

# 3. Compilation et signature
( cd android && ./gradlew assembleRelease --no-daemon -q )
BT="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
UNSIGNED=$(ls android/app/build/outputs/apk/release/*-unsigned.apk | head -1)
"$BT/zipalign" -f -p 4 "$UNSIGNED" aligned.apk
"$BT/apksigner" sign --ks android-src/sillon.keystore --ks-key-alias sillon \
  --ks-pass pass:sillon-perso --key-pass pass:sillon-perso --out sillon.apk aligned.apk
"$BT/apksigner" verify sillon.apk
ls -la sillon.apk
