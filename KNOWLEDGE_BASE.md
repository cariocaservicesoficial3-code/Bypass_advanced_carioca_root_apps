# Knowledge Base — Bypass Advanced Root Apps

> Base de conhecimento viva. Cresce a cada app processado. Sempre lida no início de uma nova sessão.

## 1. Catálogo de motores de detecção conhecidos

### 1.1 RootBeer (Java + JNI)

Biblioteca open-source mais comum em apps brasileiros. Identificável por:
- Presença da classe `com.scottyab.rootbeer.RootBeer` (não-obfuscada quando R8 preserva nome via `Const.knownRootAppsPackages`)
- `libtoolChecker.so` ou similar para a parte nativa
- Quando obfuscada, métodos viram `ap.b.{a,b,c,...,n}` — todos retornando boolean

**Pontos de bypass**:
1. Hookar `RootBeer.isRooted()` e os 12+ submétodos (`detectRootManagementApps`, `detectPotentiallyDangerousApps`, `checkForBinary`, `checkForDangerousProps`, `checkForRWPaths`, `detectTestKeys`, `checkSuExists`, `checkForRootNative`, `checkForMagiskBinary`, etc.) → todos `false`
2. `RootBeerNative.checkForRoot(Object[])` → `0`
3. **Cuidado**: forçar `canLoadNativeLibrary()` e `checkForNativeLibraryReadAccess()` para **`true`** (não false), senão o app entra no caminho `detectRootCloakingApps`

### 1.2 JailMonkey (React Native)

Módulo RN que expõe constants boolean para JS:
- `com.gantix.JailMonkey.JailMonkeyModule.getConstants` → mapa com `isJailBroken`, `hookDetected`, `canMockLocation`, `isOnExternalStorage`, etc.
- Quando obfuscado vira `yg.c`, `yg.b`, `wg.a`, `xg.a`, `vg.a`, `ug.a`

**Bypass**: substituir `getConstants` por mapa all-`false` + cada submétodo boolean retorna `false` + Promises resolvidas com `false`.

### 1.3 Adjoe Protection (anti-fraude)

SDK comercial com `libprotect.so` (~1 MB). Verificações C++ internas para emulador.
- `io.adjoe.protection.DeviceUtils.isEmulator(Context)` → retornar `String[0]`
- `DeviceUtils.drmInfo()` → string Widevine bem-formada
- `DeviceUtils.socName()` → `"qcom"`
- Métodos com Callback → `onFinished()` / `onSuccess("bypass-ok")` imediatos

### 1.4 Dynatrace (RUM/observability)

Vem embutido em apps corporativos. Tem motor próprio de root detection.
- Classes obfuscadas tipo `ra.c.{a,b,c,d,f,g,h}` → todos `false`
- Wrapper de `Build.TAGS` em `i9.a.a()` → retornar `"release-keys"`

### 1.5 Phonesky verification (Play Store legítima)

Verifica que a Play Store está instalada e assinada:
- `rl.c.a`, `wl.w.a` → retornar `true`

### 1.6 Google Play Integrity

API moderna substituta do SafetyNet:
- `com.google.android.play.core.integrity.IntegrityTokenResponse.token()` → JWT fake
- `com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityToken.token()` → JWT fake
- Valor sugerido: `"eyJhbGciOiJSUzI1NiJ9.BYPASSED.SIGNATURE"`
- **Atenção**: client-side bypass apenas. Se o backend valida server-side, é necessário interceptar a resposta HTTP ou o servidor irá bloquear de qualquer forma.

### 1.7 RNDeviceInfo / device_info_plus (Flutter)

Detecção de emulador:
- `RNDeviceModule.isEmulatorSync/isEmulator` → `false`
- `dev.fluttercommunity.plus.device_info.MethodCallHandlerImpl.isEmulator` → `false`

### 1.8 Detecção customizada nativa (próprio app)

Padrão recente: app embute lib `.so` própria que faz `stat()` em paths conhecidos, lê `/proc/self/maps`, tenta `dlopen("libsubstrate.so")`, etc.

**Bypass**: hookar APIs Java de mais alto nível antes que o C chegue lá; se o app expõe método nativo `boolean Native.checkRoot()`, hookar diretamente o método Java declarado nativo (LSPosed permite).

## 2. Camadas Android Framework (defesa em profundidade — sempre incluir)

| # | Hook | Ação |
|---|------|------|
| 1 | `Build.TAGS` (static field via reflection + remover `final`) | `"release-keys"` |
| 2 | `Build.FINGERPRINT/TYPE/HARDWARE/PRODUCT/BOARD/BOOTLOADER` | valores Pixel 6 stock |
| 3 | `SystemProperties.get/getInt/getBoolean` | spoof de 21 chaves: `ro.debuggable=0`, `ro.secure=1`, `ro.boot.verifiedbootstate=green`, `ro.boot.flash.locked=1`, `ro.build.selinux=1`, `ro.crypto.state=encrypted`, `ro.build.tags=release-keys`, `ro.boot.veritymode=enforcing`, `init.svc.adbd=stopped`, `service.adb.root=0`, `ro.kernel.qemu=0`, etc. |
| 4 | `PackageManager.getPackageInfo/getApplicationInfo` | `NameNotFoundException` para 30+ pacotes blacklistados (Magisk, SuperSU, Xposed family, LSPosed, EdXposed, LuckyPatcher, Frida, Substrate, RootCloak, BusyBox apps, etc.) |
| 5 | `PackageManager.getInstalledPackages/getInstalledApplications` | filtra blacklisted das listas |
| 6 | `Runtime.exec` + `ProcessBuilder.start` | `IOException` (ou substitui por `echo`) para `su`, `mount`, `getprop`, `which`, `magisk`, `busybox`, `resetprop` |
| 7 | `java.io.File.exists/canRead/canExecute` | `false` para 25+ paths suspeitos (`/system/bin/su`, `/sbin/su`, `/magisk`, `/data/adb/magisk`, `/data/adb/lsposed`, `/data/adb/modules`, `/system/framework/XposedBridge.jar`, `/system/lib/libxposed_*.so`, `/system/xbin/busybox`, etc.) |
| 8 | `Settings.Secure/Global.getInt/getString` | `0`/`"0"` para `adb_enabled`, `development_settings_enabled`, `mock_location`, `install_non_market_apps` |
| 9 | `android.os.Debug.isDebuggerConnected/waitingForDebugger` | `false` |
| 10 | `ApplicationInfo.flags` | strip `FLAG_DEBUGGABLE` do próprio app |
| 11 | `ActivityManager.getRunningServices/getRunningAppProcesses` | filtra processos `frida*`, `magisk*`, `xposed*`, `supersu*` |

## 3. Pipeline de engenharia reversa (passo-a-passo)

1. **Extrair APKs** do `.apks` / `.xapk`:
   ```bash
   unzip -d /tmp/<app> <app>.apks
   # base.apk + split_*.apk (config, language, screen)
   ```
2. **Decompilação Java** (rápida):
   ```bash
   jadx --deobf -d /tmp/<app>/jadx /tmp/<app>/base.apk
   ```
3. **Resources e manifesto**:
   ```bash
   apktool d -o /tmp/<app>/apktool /tmp/<app>/base.apk
   ```
4. **Inventário rápido** (para já priorizar):
   ```bash
   grep -r -l "rootbeer\|JailMonkey\|adjoe\|integrity\|safetynet\|frida\|magisk\|xposed" /tmp/<app>/jadx/sources/ | head -50
   ```
5. **Análise de libs nativas**:
   ```bash
   for so in /tmp/<app>/apktool/lib/*/*.so ; do
     echo "=== $so ==="; strings "$so" | grep -iE 'su$|magisk|frida|xposed|/proc|root|/system/' | sort -u | head
   done
   ```
6. **Identificar engine principal de root** (ler classes encontradas no passo 4)
7. **Mapear classes obfuscadas**: usar strings (`"Magisk"`, `"isRooted"`, package names `com.topjohnwu.magisk`) como âncoras para descobrir métodos renomeados
8. **Listar todos hooks necessários** num doc `reverse_engineering.md` antes de codar
9. **Implementar módulo LSPosed** seguindo template (`docs/lsposed_module_template.md`)
10. **Compilar + assinar v1+v2+v3** com `build.sh`
11. **Documentar e checkpointar**

## 4. Template de módulo LSPosed (ver docs/lsposed_module_template.md)

Estrutura mínima:
```
<app>_module/
├── AndroidManifest.xml          # uses-sdk, meta-data xposedmodule, xposeddescription, xposedminversion=82
├── res/values/
│   ├── strings.xml              # nome do módulo
│   └── arrays.xml               # xposedscope (lista de packages alvo)
├── src/main/
│   ├── assets/xposed_init       # uma única linha: com.manus.<app>_bypass.MainHook
│   └── java/com/manus/<app>_bypass/
│       ├── MainHook.java        # implements IXposedHookLoadPackage; despacha por package name
│       ├── <Engine1>Hooks.java  # 1 classe por motor neutralizado
│       ├── ...
│       └── LowLevelHooks.java   # camadas Android framework (sempre presente)
├── libs/XposedBridgeApi-82.jar  # provided, NÃO empacotado no APK
├── module.keystore              # keystore dev
└── build.sh                     # aapt → dx/d8 → zipalign → apksigner v1+v2+v3
```

## 5. Lições aprendidas

### Do projeto MinhaClaro (com.nvt.cs, RN+Flutter híbrido)
- React Native expõe módulos nativos via `getConstants`: hookar o mapa **antes** dos métodos individuais economiza muitos hooks
- Hermes bytecode (`index.android.bundle`) não é hookável via Xposed — toda detecção precisa estar nas pontes Java/JNI
- Dynatrace tem motor independente; sempre verificar `ra.c.*` quando o app é corporativo

### Do projeto Méqui (com.mcdo.mcdonalds)
- `canLoadNativeLibrary()` deve voltar **`true`**, não false (anti-cloaking)
- Adjoe `libprotect.so` pode ser bypassado totalmente na borda Java — não precisa de patch binário
- Play Integrity moderna: hookar no `token()` final é suficiente; deixar a request/task lifecycle intacta

### Padrões emergentes (atualizar a cada novo app)
- *(será preenchido conforme processamos KMV e outros)*

## 6. Convenções de logging dentro dos módulos

Todos os módulos seguem o padrão:
```
[<AppShortName>Bypass] ==========================================
[<AppShortName>Bypass]    <App> Root Bypass v<X.Y.Z> — ACTIVATED
[<AppShortName>Bypass]    Target: <package>
[<AppShortName>Bypass] ==========================================
[<AppShortName>Bypass] [OK] <GroupName>Hooks installed
...
```

E em runtime:
```
[<AppShortName>Bypass] <ClassName>.<methodName>(<args>) -> <returnedValue>
```

Isso facilita correlacionar logs do LSPosed Manager com as fases.

### 1.9 CashShield / PartnerShield

SDK de anti-fraude corporativo extremamente agressivo e pesado.
- Lib nativa: `libcashshieldptr-native-lib.so` (frequentemente >300 KB)
- Classes-chave: `com.shield.ptr.internal.NativeUtils`
- Fingerprints: strings `"cashshield"`, `"shield/ptr"`, símbolos JNI como `isFoundMagisk`, `isFridaDetected`, `isVirtualXposedDetected`
- Conhece caminhos específicos de Magisk Delta, LSPosed Manager, SandHook, etc.

**Bypass**:
O CashShield delega todas as suas verificações C++ para a ponte Java através de métodos `public native` na classe `NativeUtils`. Como LSPosed pode hookar métodos `native` declarados em Java, podemos neutralizar o motor inteiro na borda:
- Hookar ~26 métodos booleanos (`isFoundMagisk`, `isFridaDetected`, `isAccessedSuperuserApk`, etc.) para retornar `false`
- Hookar métodos int (`getArpCache`, `isPathExists`, `jitCacheCount`) para retornar `0`
- Hookar métodos string (`getHostsModifiedTime`, `getBaseApkPath`, `getNativeAppVersion`, `getNativePackage`) para retornar valores stock/seguros

## AllowMe SDK / Serasa IDF
**Natureza**: SDK de biometria comportamental e anti-fraude server-side da Serasa Experian.
**Ação**: Coleta dados profundos do dispositivo (fingerprint de hardware, apps instalados, configurações) e envia via requisições criptografadas (ECDH) para a API `idf-api.serasaexperian.com.br`. Retorna um token (`sessionId` ou `collectToken`) que o app repassa ao seu próprio backend.
**Bypass (Ouro)**:
O segredo não é impedir a coleta, mas sim interceptar a resposta do SDK e injetar um token limpo antes que o app envie para o backend. O backend não consegue validar matematicamente o token sem consultar o Serasa, e frequentemente aceita qualquer string bem formada ou ignora se o Serasa retornar timeout.
```java
// Hook na classe br.com.allowme.android.allowmesdk.AllowMe
XposedHelpers.findAndHookMethod("br.com.allowme.android.allowmesdk.AllowMe", lpparam.classLoader, "collect", "br.com.allowme.android.allowmesdk.CollectCallback", new XC_MethodReplacement() {
    @Override
    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
        Object callback = param.args[0];
        Method m = callback.getClass().getMethod("onSuccess", String.class);
        m.invoke(callback, "clean-device-fingerprint-token"); // O backend aceitará esse token
        return null;
    }
});
```

## Incognia SDK
**Natureza**: SDK de localização e biometria comportamental (trackEvent).
**Ação**: Monitora ações do usuário (cadastro, login) cruzando com sensores e redes Wi-Fi.
**Bypass**: Neutralizar os métodos estáticos `trackEvent` e falsificar o `getInstallationId`.
```java
XposedHelpers.findAndHookMethod("com.incognia.Incognia", lpparam.classLoader, "trackEvent", String.class, XC_MethodReplacement.returnConstant(null));
XposedHelpers.findAndHookMethod("com.incognia.Incognia", lpparam.classLoader, "getInstallationId", XC_MethodReplacement.returnConstant("00000000-0000-0000-0000-000000000000"));
```
