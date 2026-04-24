#!/usr/bin/env bash
set -euo pipefail

NAME="kmv_bypass"
VERSION="1.5.6"
PKG="com.manus.${NAME}"
OUT="$(pwd)/../artifacts"
BUILD="$(pwd)/build"
SHARED="../../../shared"

ANDROID_JAR="$(find /usr/lib/android-sdk -name 'android.jar' 2>/dev/null | head -1)"
[ -z "$ANDROID_JAR" ] && ANDROID_JAR="${SHARED}/android.jar"

XPOSED_JAR="${SHARED}/XposedBridgeApi-82.jar"
KEYSTORE="${SHARED}/module.keystore"
KS_PASS="${KS_PASS:-manus2026}"

rm -rf "$BUILD"
mkdir -p "$BUILD/obj" "$BUILD/dex" "$OUT"

# 1. compilar AAPT (gera R.java + resources.arsc + manifesto binário)
aapt package -f -m -F "$BUILD/${NAME}_unaligned.apk" \
    -M AndroidManifest.xml \
    -S res \
    -A src/main/assets \
    -I "$ANDROID_JAR" \
    -J "$BUILD/obj"

# 2. compilar Java
find src/main/java -name '*.java' > "$BUILD/sources.txt"
javac -source 1.8 -target 1.8 \
    -classpath "$ANDROID_JAR:$XPOSED_JAR" \
    -d "$BUILD/obj" \
    @"$BUILD/sources.txt" \
    "$BUILD/obj/com/manus/${NAME}/R.java"

# 3. converter para DEX
D8="$(which d8 || true)"
if [ -z "$D8" ]; then
    echo "ERRO: d8 não encontrado no PATH"
    exit 1
fi

"$D8" --release --min-api 21 --output "$BUILD/dex" \
    $(find "$BUILD/obj" -name '*.class')

# 4. injetar classes.dex no APK
cp "$BUILD/${NAME}_unaligned.apk" "$BUILD/${NAME}_with_dex.apk"
cd "$BUILD/dex"
zip -uj "../${NAME}_with_dex.apk" classes.dex
cd - > /dev/null

# 5. zipalign
zipalign -f 4 "$BUILD/${NAME}_with_dex.apk" "$BUILD/${NAME}_aligned.apk"

# 6. assinar v1+v2+v3
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KS_PASS" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "${OUT}/KMV-RootBypass-v${VERSION}.apk" \
    "$BUILD/${NAME}_aligned.apk"

apksigner verify --verbose "${OUT}/KMV-RootBypass-v${VERSION}.apk"

echo
echo "===> APK: ${OUT}/KMV-RootBypass-v${VERSION}.apk"
md5sum "${OUT}/KMV-RootBypass-v${VERSION}.apk"
ls -la "${OUT}/KMV-RootBypass-v${VERSION}.apk"
