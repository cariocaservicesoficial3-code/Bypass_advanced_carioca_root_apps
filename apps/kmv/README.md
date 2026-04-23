# KMV (Ipiranga) — Root Bypass

**Módulo LSPosed cirúrgico para bypass completo anti-detecção no app KMV (Ipiranga).**

## Especificações do App Alvo

| Campo | Valor |
|-------|-------|
| **App** | KMV (Km de Vantagens) |
| **Pacote** | `com.gigigo.ipirangaconectcar` |
| **Versão analisada** | 4.83.101 |
| **Target SDK** | 36 |
| **Arquitetura** | arm64-v8a (Split APK com 23 MB de libs nativas) |
| **Motores identificados** | RootBeer, CashShield/PartnerShield, Play Integrity, FaceTec, iProov |

## Resumo da Solução

O módulo implementa **~60 hooks cirúrgicos** divididos em 4 camadas de defesa:

1. **CashShieldHooks**: Neutraliza a classe `com.shield.ptr.internal.NativeUtils` que atua como ponte JNI para a pesada biblioteca anti-fraude `libcashshieldptr-native-lib.so`. Hookamos 26 métodos booleanos (`isFoundMagisk`, `isFridaDetected`, etc.) para retornar `false`, além de 3 métodos numéricos para `0` e 4 métodos de string para valores seguros e stock.
2. **RootBeerHooks**: Intercepta a implementação ofuscada do RootBeer (`com.scottyab.rootbeer.RootBeer` e `RootBeerNative`). Hookamos os 12 métodos ofuscados para retornar `false`, forçamos `wasNativeLibraryLoaded` (ofuscado como `a()`) para `true` e neutralizamos a checagem nativa em `checkForRoot`.
3. **PlayIntegrityHooks**: Fornece tokens JWT *mockados* nas APIs legada e moderna da Google Play Integrity, permitindo o bypass *client-side*.
4. **LowLevelHooks**: Blindagem de defesa em profundidade no Android Framework, incluindo *spoofing* de `Build.TAGS`, filtragem de pacotes no `PackageManager`, ocultação de caminhos suspeitos em `java.io.File`, bloqueio de comandos como `su` e `magisk` no `Runtime.exec`, e falsificação de chaves no `SystemProperties`.

## Instalação

1. Instale o artefato `KMV-RootBypass-v1.0.0.apk` (encontrado na pasta `artifacts/`).
2. Ative o módulo no **LSPosed Manager**.
3. O escopo já está pré-configurado para `com.gigigo.ipirangaconectcar`.
4. Force a parada do app KMV e abra-o novamente.

## Logs

Para verificar se o bypass está funcionando, verifique os logs do LSPosed Manager filtrando por `[KMVBypass]`. Você verá a confirmação de inicialização e a aplicação de todos os grupos de hooks.

Para detalhes completos sobre a análise técnica que baseou estes hooks, veja [reverse_engineering.md](reverse_engineering.md).
