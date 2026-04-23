# Engenharia Reversa — KMV (Ipiranga)

**App**: `com.gigigo.ipirangaconectcar`
**Versão**: 4.83.101
**Target SDK**: 36
**Arquitetura Nativa**: arm64-v8a (com split dedicada de 23MB)

## Resumo dos Motores de Detecção

O app implementa uma forte defesa em profundidade baseada em **três pilares principais**:

1. **RootBeer (Java + JNI)**: Implementação clássica com ofuscação R8 no lado Java, mas mantendo a JNI `libtoolChecker.so`.
2. **CashShield / PartnerShield (JNI)**: SDK de anti-fraude corporativo extremamente agressivo (`libcashshieldptr-native-lib.so` com 361KB). Faz varreduras de path, detecção de hooking framework (Frida, Xposed, SandHook, Substrate) e verifica integridade de sistema via C++.
3. **Play Integrity API**: Validação Google para integridade de ambiente.
4. **Múltiplos SDKs de Biometria Liveness**: FaceTec, iProov, OneSpan/OZ Forensics (focados em liveness, não necessariamente em root, mas podem falhar se o ambiente estiver comprometido).

## Mapa Cirúrgico de Hooks

### 1. RootBeer (com.scottyab.rootbeer.RootBeer)

A classe sofreu ofuscação R8. Os métodos públicos booleanos que devem retornar `false` são:

- `a()`
- `b(String)`
- `c()`
- `checkForDangerousProps()`
- `checkForRWPaths()`
- `checkForRootNative()`
- `d()`
- `detectTestKeys()`
- `e()`
- `f(String[])`
- `g()`
- `h(String[])`
- `j()` (Este é o `isRooted()` principal, equivalente a `g() || e() || b("su") || checkForDangerousProps() || checkForRWPaths() || detectTestKeys() || d() || checkForRootNative() || c()`)

**Exceção Anti-Cloaking**:
A classe `com.scottyab.rootbeer.RootBeerNative` possui o método `a()` (equivalente a `wasNativeLibraryLoaded`) que deve retornar **`true`**. O método `checkForRoot(Object[])` deve retornar `0`.

### 2. CashShield / PartnerShield (com.shield.ptr.internal.NativeUtils)

O CashShield delega todas as suas verificações C++ para a ponte Java através de métodos `public native` na classe `NativeUtils`. Neutralizaremos o motor inteiro hookando esta borda, evitando a necessidade de patch binário.

**Métodos que devem retornar `false` (boolean):**
- `isAccessedSuperuserApk`
- `isChaosDetected`
- `isDetectedDevKeys`
- `isDetectedTestKeys`
- `isFoundBusyboxBinary`
- `isFoundDangerousProps`
- `isFoundMagisk`
- `isFoundResetprop`
- `isFoundSuBinary`
- `isFoundSubstrate`
- `isFoundWrongPathPermission`
- `isFoundXposed`
- `isFridaDetected`
- `isGboxDetected`
- `isJiaguDetected`
- `isLsplantDetected`
- `isNotFoundReleaseKeys`
- `isPermissiveSelinux`
- `isSandHookDetected`
- `isSuExists`
- `isTaichiDetected`
- `isVirtualAndroidDetected`
- `isVirtualCameraAppDetected`
- `isVirtualXposedDetected`
- `isZygiskDetected`
- `listenForFrida`

**Métodos que devem retornar `0` (int):**
- `getArpCache(int)`
- `isPathExists(String)`
- `jitCacheCount()`

**Métodos que devem retornar strings stock seguras:**
- `getHostsModifiedTime()` -> `"0"`
- `getBaseApkPath()` -> `"/data/app/~~random==/com.gigigo.ipirangaconectcar-random==/base.apk"`
- `getNativeAppVersion(Context)` -> `"4.83.101"`
- `getNativePackage(Context)` -> `"com.gigigo.ipirangaconectcar"`

### 3. Play Integrity (com.google.android.play.core.integrity)

Interceptaremos os tokens de resposta para fornecer um JWT mockado (bypass client-side).

- `IntegrityTokenResponse.token()` -> `"eyJhbGciOiJSUzI1NiJ9.BYPASSED.SIGNATURE"`
- `StandardIntegrityManager$StandardIntegrityToken.token()` -> `"eyJhbGciOiJSUzI1NiJ9.BYPASSED.SIGNATURE"`

### 4. Camada Android Framework (Defesa em Profundidade)

Como o CashShield possui verificações C++ nativas agressivas (como leitura de `/proc/self/maps`), é crucial manter a blindagem do framework:

- **Build.TAGS**: Spoofing para `"release-keys"` via reflection.
- **PackageManager**: Lançar `NameNotFoundException` para pacotes Magisk, Xposed, LSPosed, SuperSU, etc.
- **java.io.File**: Forçar `exists()`, `canRead()`, `canExecute()` a retornar `false` para caminhos como `/system/bin/su`, `/magisk`, `/data/adb/magisk`, `/data/local/tmp/xposed`, etc.
- **Runtime.exec**: Bloquear comandos como `su`, `magisk`, `which su`.
- **SystemProperties**: Spoofing de `ro.debuggable`, `ro.secure`, `ro.build.tags`, `ro.boot.verifiedbootstate`, etc.

## Estratégia de Implementação do Módulo

1. Criar `MainHook` direcionado ao pacote `com.gigigo.ipirangaconectcar`.
2. Implementar `RootBeerHooks` para cobrir os métodos ofuscados.
3. Implementar `CashShieldHooks` para interceptar a `NativeUtils`.
4. Implementar `PlayIntegrityHooks`.
5. Implementar `LowLevelHooks` (framework Android) compartilhados.
