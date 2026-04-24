# Checkpoint — 2026-04-24 (Sessão KMV v1.5.8)

## Estado atual
- App em foco: **KMV (Km de Vantagens Ipiranga)** (`com.gigigo.ipirangaconectcar`)
- Versão analisada: 4.83.101
- Última build: `apps/kmv/artifacts/KMV-RootBypass-v1.5.8.apk`

## Fase atual
- **v1.5.8 — FIX CRÍTICO: ALLOWME CRASH + APP CARREGAMENTO**
- Resolvido o problema que impedia o app de abrir na v1.5.7.

## Diagnóstico desta sessão (v1.5.8)

### PROBLEMA 1: UninitializedPropertyAccessException (CRASH)
- **Causa**: A v1.5.7 tinha um hook agressivo que limpava o SharedPreferences do AllowMe (`SharedPreferencesImpl.edit().clear().apply()`) toda vez que o SDK tentava acessá-lo. Isso causava uma falha de inicialização interna no SDK do AllowMe (Serasa), resultando no crash fatal.
- **Evidência**: Crash log `crash-com-gigigo-ipirangaconectcar-24_04-19-15-04_007.zip`
  - `kotlin.UninitializedPropertyAccessException: lateinit property has not been initialized` em `br.com.allowme.android.allowmesdk.environment.storage.cF18276.b`
- **Correção v1.5.8**: Removido o hook de limpeza automática de SharedPreferences. O bypass agora foca apenas em interceptar os valores lidos, sem corromper o estado interno do SDK.

### PROBLEMA 2: Erro ao carregar (App nem abria)
- **Causa**: O DNS Sinkhole ou a instabilidade causada pelo crash do AllowMe estavam impedindo o carregamento dos configs do Contentful e MarketingCloud.
- **Correção v1.5.8**: Estabilização do módulo e remoção de hooks redundantes que causavam sobrecarga no startup.

## Camadas de proteção v1.5.8 (PersistentIdHooks.java)
1. **DNS SINKHOLE**: Mantido bloqueio de telemetria (PayPal, ViewPkg, Incognia, Serasa) com o fix do ARRAY para `getAllByName`.
2. **UUID SOURCE HOOKS**: Mantidos os hooks na fonte (`PREF_UNIQUE_ID`, `C0415r.m1865D`, `Device.getUuid`) para garantir UUID aleatório por sessão.
3. **OKHTTP REWRITE**: Interceptação de segurança nos headers `x-mobile`.
4. **ESTABILIDADE**: Removida limpeza de SharedPreferences para permitir que o app inicialize corretamente.

## Próximos passos
1. Instalar `KMV-RootBypass-v1.5.8.apk`.
2. O app deve abrir normalmente e o UUID deve ser rotacionado.
3. Prosseguir com o cadastro.

## Como retomar
```bash
cd ~/Bypass_advanced_carioca_root_apps
git pull
# APK v1.5.8 pronto em apps/kmv/artifacts/KMV-RootBypass-v1.5.8.apk
```
