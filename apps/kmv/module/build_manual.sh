#!/usr/bin/env bash
set -euo pipefail

export PATH="$HOME/android-build-tools/android-14:$PATH"

NAME="kmv_bypass"
VERSION="1.5.7"
PKG="com.manus.${NAME}"
MODULE_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${MODULE_DIR}/../artifacts"
BUILD="${MODULE_DIR}/build"
SHARED="${MODULE_DIR}/../../../shared"
ANDROID_JAR="${SHARED}/android.jar"
XPOSED_JAR="${SHARED}/XposedBridgeApi-82.jar"
KEYSTORE="${SHARED}/module.keystore"
KS_PASS="${KS_PASS:-manus2026}"

echo "=== KMV Bypass v${VERSION} Build ==="
echo "MODULE_DIR: $MODULE_DIR"
echo "ANDROID_JAR: $ANDROID_JAR"
echo "XPOSED_JAR: $XPOSED_JAR"

rm -rf "$BUILD"
mkdir -p "$BUILD/obj" "$BUILD/dex" "$OUT"

# 1. AAPT
echo "=== STEP 1: AAPT ==="
aapt package -f -m -F "$BUILD/${NAME}_unaligned.apk" \
    -M "${MODULE_DIR}/AndroidManifest.xml" \
    -S "${MODULE_DIR}/res" \
    -A "${MODULE_DIR}/src/main/assets" \
    -I "$ANDROID_JAR" \
    -J "$BUILD/obj"
echo "AAPT OK"

# 2. Compilar Java
echo "=== STEP 2: JAVAC ==="
find "${MODULE_DIR}/src/main/java" -name '*.java' > "$BUILD/sources.txt"
echo "Sources:"
cat "$BUILD/sources.txt"
javac -source 1.8 -target 1.8 \
    -classpath "${ANDROID_JAR}:${XPOSED_JAR}" \
    -d "$BUILD/obj" \
    @"$BUILD/sources.txt" \
    "$BUILD/obj/com/manus/${NAME}/R.java"
echo "JAVAC OK"

# 3. DEX
echo "=== STEP 3: D8 ==="
d8 --release --min-api 21 --output "$BUILD/dex" \
    $(find "$BUILD/obj" -name '*.class')
echo "D8 OK"

# 4. Injetar DEX
echo "=== STEP 4: ZIP DEX ==="
cp "$BUILD/${NAME}_unaligned.apk" "$BUILD/${NAME}_with_dex.apk"
cd "$BUILD/dex"
zip -uj "../${NAME}_with_dex.apk" classes.dex
cd "$MODULE_DIR"
echo "ZIP OK"

# 5. Zipalign
echo "=== STEP 5: ZIPALIGN ==="
zipalign -f 4 "$BUILD/${NAME}_with_dex.apk" "$BUILD/${NAME}_aligned.apk"
echo "ZIPALIGN OK"

# 6. Assinar
echo "=== STEP 6: SIGN ==="
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:${KS_PASS}" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "${OUT}/KMV-RootBypass-v${VERSION}.apk" \
    "$BUILD/${NAME}_aligned.apk"
echo "SIGN OK"

apksigner verify --verbose "${OUT}/KMV-RootBypass-v${VERSION}.apk"
echo ""
echo "===> APK: ${OUT}/KMV-RootBypass-v${VERSION}.apk"
md5sum "${OUT}/KMV-RootBypass-v${VERSION}.apk"
ls -la "${OUT}/KMV-RootBypass-v${VERSION}.apk"
