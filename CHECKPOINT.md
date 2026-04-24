# Checkpoint — 2026-04-23 (Sessão KMV v1.1)

## Estado atual
- App em foco: **KMV (Km de Vantagens Ipiranga)** (`com.gigigo.ipirangaconectcar`)
- Versão analisada: 4.83.101
- Fase atual: **Erro #1004 diagnosticado via HAR, Módulo v1.1 finalizado**
- Última build: `apps/kmv/artifacts/KMV-RootBypass-v1.1.0.apk` (MD5: `4852d18b5b8e0585366a771920533a9d`)

## Resumo da sessão (v1.1)
- O usuário reportou o erro **#1004** durante o cadastro e forneceu um log HAR da rede.
- A análise do HAR revelou que o erro ocorre no endpoint `api.kmdevantagens.com.br/kmv/api/v1/minimum_signup/generate_mfa`.
- Identificamos o campo bloqueador: `deviceFingerprintSessionId` no header `x-mobile` e o payload `data` criptografado.
- Descobrimos que o KMV utiliza o **AllowMe SDK (Serasa IDF)** para coletar um fingerprint agressivo do dispositivo, enviando os dados via ECDH para `idf-api.serasaexperian.com.br`. O Serasa retorna um token de validação que o KMV repassa ao backend.
- Também descobrimos a presença do motor anti-fraude comportamental **Incognia SDK** (`service*.br.incognia.com`).
- Implementamos a classe `FingerprintHooks.java` na v1.1, interceptando os callbacks do `AllowMe.collect()` para forçar um token limpo e neutralizamos o `Incognia.trackEvent()`.
- Realizamos spoofing complementar da classe `Build` (FINGERPRINT, BOOTLOADER, DISPLAY) para cobrir lacunas lidas pelo AllowMe.

## Conhecimento novo (KNOWLEDGE_BASE)
- **AllowMe SDK (Serasa IDF)**: Motor de fingerprinting focado em telemetria server-side. Pode ser neutralizado interceptando os callbacks `CollectCallback`, `StartCallback` e lambdas Kotlin da classe `br.com.allowme.android.allowmesdk.AllowMe`.
- **Incognia SDK**: Motor comportamental de anti-fraude que cruza dados de localização e sensores. Neutralizado hookando os métodos estáticos de `com.incognia.Incognia`.

## Próximos passos
1. Entregar o artefato `KMV-RootBypass-v1.1.0.apk` ao usuário.
2. Aguardar novo teste de cadastro no app.
3. Caso ainda haja bloqueio server-side, o próximo passo será analisar o payload do Google Enterprise reCAPTCHA (chamadas `mrr`, `mri`, `mlg`) que também estava presente no HAR.

## Como retomar
```bash
cd ~/Bypass_advanced_carioca_root_apps
git pull
# O módulo do KMV v1.1 está pronto em apps/kmv/artifacts/
```
