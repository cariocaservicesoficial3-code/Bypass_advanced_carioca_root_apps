# Catálogo de Motores de Detecção

Esta é a lista canônica e referenciável de motores de detecção encontrados nos apps já trabalhados. Cada entrada inclui *fingerprints* para identificação rápida em novos apps.

## Como usar

Quando uma nova análise começa, rode este grep no diretório decompilado:

```bash
APP_DECOMPILED=/path/to/jadx_out/sources
grep -rln -E \
'rootbeer|JailMonkey|adjoe|integrity|safetynet|frida|magisk|xposed|lsposed|substrate|dynatrace|emulator|isRooted|/system/bin/su|TAGS\.contains.*test-keys|verifiedbootstate' \
"$APP_DECOMPILED" 2>/dev/null | sort -u | head -100
```

E para libs nativas:

```bash
for so in <APP_APKTOOL>/lib/*/*.so; do
  echo "=== $so ==="
  strings "$so" | grep -iE '^/(su|magisk|sbin|system/(bin|xbin)/(su|busybox|magisk))|frida|gum-js|libsubstrate|xposed|lsposed|/data/adb|/proc/self/(maps|status)|ptrace|getprop|verifiedbootstate' | sort -u | head -30
done
```

## Engine 1 — RootBeer

| Atributo | Valor |
|---|---|
| Tipo | Java + JNI (`libtoolChecker.so`) |
| Origem | OSS, github.com/scottyab/rootbeer |
| Classe principal | `com.scottyab.rootbeer.RootBeer` |
| Classe nativa | `com.scottyab.rootbeer.RootBeerNative` |
| Fingerprints | strings `"rootbeer"`, `Const.knownRootAppsPackages` array com nomes Magisk/SuperSU |
| Visto em | MinhaClaro (obfuscado como `ap.b`), Méqui (não-obfuscado) |

**Métodos a hookar** (todos retornam `false` exceto onde indicado):

```
isRooted, isRootedWithBusyBoxCheck, isRootedWithoutBusyBoxCheck
detectRootManagementApps (×2 overloads)
detectPotentiallyDangerousApps (×2 overloads)
detectRootCloakingApps (×2 overloads)
checkForBinary, checkForDangerousProps, checkForRWPaths
detectTestKeys, checkSuExists, checkForRootNative
checkForMagiskBinary, checkForBusyBoxBinary, checkForSuBinary
canLoadNativeLibrary           → TRUE  (anti-cloaking)
checkForNativeLibraryReadAccess → TRUE  (anti-cloaking)
RootBeerNative.checkForRoot(Object[])    → 0
RootBeerNative.wasNativeLibraryLoaded()  → TRUE
RootBeerNative.setLogDebugMessages(bool) → 0
```

## Engine 2 — JailMonkey (React Native)

| Atributo | Valor |
|---|---|
| Tipo | RN bridge module |
| Origem | github.com/GantMan/jail-monkey |
| Classe | `com.gantix.JailMonkey.JailMonkeyModule` |
| Fingerprints | string `"JailMonkey"`, `getConstants` retornando map com `isJailBroken`/`hookDetected` |
| Visto em | MinhaClaro (obfuscado: `yg.c`, `wg.a`, `xg.a`, `vg.a`, `ug.a`) |

**Bypass**:
- `getConstants()` → mapa all-`false`
- Cada submétodo boolean → `false`
- Promises `isDebuggedMode`/`isDevSettings` → resolve `false`

## Engine 3 — Adjoe Protection (anti-fraude)

| Atributo | Valor |
|---|---|
| Tipo | SDK comercial Java + JNI |
| Lib nativa | `libprotect.so` (~1 MB) |
| Classes-chave | `io.adjoe.protection.DeviceUtils`, `AdjoeProtectionLibrary` |
| Fingerprints | strings `"adjoe"`, `libprotect.so`, classes C++ `Emulator::checkBasic/checkAdvanced` |
| Visto em | Méqui |

**Bypass** (na borda Java/JNI, sem patch binário):
- `DeviceUtils.isEmulator(Context)` → `String[0]`
- `DeviceUtils.drmInfo()` → `"widevine;17.0.0;Google;AES/CBC/NoPadding"`
- `DeviceUtils.socName()` → `"qcom"`
- Métodos com Callback → `onFinished()` / `onSuccess("bypass-ok")` imediatos

## Engine 4 — Dynatrace

| Atributo | Valor |
|---|---|
| Tipo | RUM SDK corporativo |
| Classes obfuscadas (visto em MinhaClaro) | `ra.c.{a,b,c,d,f,g,h}`, `i9.a.a()` |
| Fingerprints | strings `"dynatrace"`, `"dtxData"`, classes em `com.dynatrace.android.*` |
| Visto em | MinhaClaro |

**Bypass**: 7 métodos boolean → `false`; `i9.a.a()` (Build.TAGS wrapper) → `"release-keys"`

## Engine 5 — Phonesky / Play Store legitimacy

| Atributo | Valor |
|---|---|
| Verifica | Play Store instalada e com release-keys |
| Classes obfuscadas (MinhaClaro) | `rl.c.a`, `wl.w.a` |
| Fingerprints | strings `"com.android.vending"`, verificação de assinatura GooglePlay |

**Bypass**: ambos retornam `true`

## Engine 6 — Google Play Integrity (substituto do SafetyNet)

| Atributo | Valor |
|---|---|
| API legacy | `com.google.android.play.core.integrity.IntegrityTokenResponse` |
| API moderna | `com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityToken` |
| Fingerprints | imports `com.google.android.play.core.integrity.*`, dependency `play-integrity` |
| Limitação | client-side bypass apenas; backend pode validar server-side |

**Bypass**: `token()` → `"eyJhbGciOiJSUzI1NiJ9.BYPASSED.SIGNATURE"`

## Engine 7 — RNDeviceInfo / device_info_plus

| Atributo | Valor |
|---|---|
| Tipo | Detecção de emulador em RN ou Flutter |
| Classes | `RNDeviceModule.isEmulatorSync/isEmulator`, `dev.fluttercommunity.plus.device_info.MethodCallHandlerImpl.isEmulator` |

**Bypass**: ambos → `false`

## Engine 8 — Detecção customizada nativa do próprio app

Padrão recente: app embute `.so` própria que faz `stat()` em paths conhecidos, `dlopen("libsubstrate.so")`, lê `/proc/self/maps`.

**Bypass**:
- Hookar APIs Java de mais alto nível antes que o C chegue lá
- Se app expõe método nativo `boolean Native.checkRoot()`: hookar diretamente o método Java declarado nativo (LSPosed permite via `XposedBridge.hookMethod` no `Method` obtido por reflection)
- Se for inevitável: patch binário no `.so` (substituir `cmp/jne` por `nop`/`b` na função identificada por símbolo ou padrão)

## Camadas Android Framework (sempre incluir — defesa em profundidade)

Vide `KNOWLEDGE_BASE.md` seção 2.
