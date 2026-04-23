# Template de Módulo LSPosed

Estrutura padrão usada em **todos** os módulos deste repositório.

## Layout de diretórios

```
<app>_module/
├── AndroidManifest.xml
├── build.sh
├── module.keystore                  → simlink para shared/module.keystore
├── libs/
│   └── XposedBridgeApi-82.jar       → simlink para shared/XposedBridgeApi-82.jar
├── res/values/
│   ├── strings.xml
│   └── arrays.xml                   # xposedscope com a lista de packages alvo
└── src/main/
    ├── assets/xposed_init           # uma única linha: com.manus.<app>_bypass.MainHook
    └── java/com/manus/<app>_bypass/
        ├── MainHook.java
        ├── <Engine1>Hooks.java
        ├── ...
        └── LowLevelHooks.java
```

## AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.manus.<app>_bypass"
    android:versionCode="1"
    android:versionName="1.0.0">

    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="34"/>

    <application
        android:label="@string/app_name"
        android:icon="@android:drawable/ic_dialog_info"
        android:allowBackup="false"
        android:hasCode="true">

        <meta-data android:name="xposedmodule" android:value="true" />
        <meta-data android:name="xposeddescription" android:value="@string/xposed_description" />
        <meta-data android:name="xposedminversion" android:value="82" />
        <meta-data android:name="xposedscope" android:resource="@array/xposedscope" />
    </application>
</manifest>
```

## res/values/arrays.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="xposedscope">
        <item><!-- package alvo, ex: com.example.app --></item>
    </string-array>
</resources>
```

## res/values/strings.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name"><App> Root Bypass</string>
    <string name="xposed_description">Bypass cirúrgico de root/Magisk/bootloader/Play Integrity para <package>.</string>
</resources>
```

## src/main/assets/xposed_init

```
com.manus.<app>_bypass.MainHook
```

(Nenhum newline extra, nenhum BOM.)

## MainHook.java (esqueleto)

```java
package com.manus.<app>_bypass;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {
    public static final String TAG = "[<App>Bypass] ";
    public static final String TARGET = "<package>";
    public static final String VERSION = "1.0.0";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!TARGET.equals(lpparam.packageName)) return;

        log("==========================================");
        log("   <App> Root Bypass v" + VERSION + " — ACTIVATED");
        log("   Target: " + TARGET);
        log("==========================================");

        try { new <Engine1>Hooks().install(lpparam); log("[OK] <Engine1>Hooks installed"); }
        catch (Throwable t) { log("[FAIL] <Engine1>Hooks: " + t); }

        try { new LowLevelHooks().install(lpparam); log("[OK] LowLevelHooks installed"); }
        catch (Throwable t) { log("[FAIL] LowLevelHooks: " + t); }

        log("All hook groups installed.");
    }

    public static void log(String msg) { XposedBridge.log(TAG + msg); }
}
```

## build.sh (esqueleto)

```bash
#!/usr/bin/env bash
set -euo pipefail

NAME="<app>_bypass"
VERSION="1.0.0"
PKG="com.manus.${NAME}"
OUT="$(pwd)"
BUILD="${OUT}/build"
SHARED="../../shared"

ANDROID_JAR="$(find /usr/lib/android-sdk -name 'android.jar' 2>/dev/null | head -1)"
[ -z "$ANDROID_JAR" ] && ANDROID_JAR="${SHARED}/android.jar"

XPOSED_JAR="${SHARED}/XposedBridgeApi-82.jar"
KEYSTORE="${SHARED}/module.keystore"
KS_PASS="${KS_PASS:-manus2026}"

rm -rf "$BUILD"
mkdir -p "$BUILD/obj" "$BUILD/dex"

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

# 3. converter para DEX (d8 do build-tools, ou use d8 standalone)
D8="$(which d8 || true)"
if [ -z "$D8" ]; then
    # fallback: usa dx empacotado
    DX="$(which dx || true)"
    [ -z "$DX" ] && { echo "ERRO: precisa de d8 ou dx"; exit 1; }
    cd "$BUILD/obj"
    "$DX" --dex --output="$BUILD/dex/classes.dex" .
    cd "$OUT"
else
    "$D8" --release --min-api 21 --output "$BUILD/dex" \
        $(find "$BUILD/obj" -name '*.class')
fi

# 4. injetar classes.dex no APK
cp "$BUILD/${NAME}_unaligned.apk" "$BUILD/${NAME}_with_dex.apk"
cd "$BUILD/dex"
zip -uj "../${NAME}_with_dex.apk" classes.dex
cd "$OUT"

# 5. zipalign
zipalign -f 4 "$BUILD/${NAME}_with_dex.apk" "$BUILD/${NAME}_aligned.apk"

# 6. assinar v1+v2+v3
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KS_PASS" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "${OUT}/<App>-RootBypass-v${VERSION}.apk" \
    "$BUILD/${NAME}_aligned.apk"

apksigner verify --verbose "${OUT}/<App>-RootBypass-v${VERSION}.apk"

echo
echo "===> APK: ${OUT}/<App>-RootBypass-v${VERSION}.apk"
md5sum "${OUT}/<App>-RootBypass-v${VERSION}.apk"
ls -la "${OUT}/<App>-RootBypass-v${VERSION}.apk"
```

(Nota: keystore deve ser criada uma vez com `keytool -genkey -v -keystore module.keystore -alias manus -keyalg RSA -keysize 4096 -validity 10000 -storepass manus2026 -keypass manus2026 -dname "CN=Manus,O=CariocaServices,C=BR"` e fica em `shared/`.)
