# Checkpoint — 2026-04-23 (sessão de bootstrap)

## Estado atual
- App em foco: **KMV** (`KMV_4.83.101.apks` enviado pelo usuário) — pacote/app a confirmar pela análise
- Versão analisada: 4.83.101
- Fase atual: **Bootstrap do repositório concluído**, iniciando fase de identificação do APK
- Última build: nenhuma ainda

## Resumo da sessão
- Ambiente preparado: Java 17, jadx 1.5.5, apktool 2.5, aapt/aapt2, zipalign, apksigner, d8 (R8 8.3.37)
- Skill `android-reverse-engineering` instalada em `/home/ubuntu/skills/android-reverse-engineering/`
- Repositório bootstrappado com:
  - `README.md` (índice + filosofia)
  - `KNOWLEDGE_BASE.md` (catálogo de motores de detecção: RootBeer, JailMonkey, Adjoe, Dynatrace, Phonesky, Play Integrity, RNDeviceInfo/device_info_plus, customs)
  - `CHECKPOINT.md` (este arquivo)
  - `docs/checkpoint_protocol.md` (protocolo de retomada de sessão)
  - `docs/lsposed_module_template.md` (template padrão dos módulos)
  - `shared/XposedBridgeApi-82.jar` (do MALTF/XposedBridgeAPI)
  - `shared/android.jar` (API 34 stub)
  - `shared/module.keystore` (keystore dev — senha `manus2026`, alias `manus`)

## Conhecimento novo
- Toolchain de build de módulos LSPosed em ambiente sandbox: `aapt → javac → d8 → zipalign → apksigner v1+v2+v3` (registrado no template)
- Fonte confiável de `XposedBridgeApi-82.jar`: `https://github.com/MALTF/XposedBridgeAPI/releases/download/v82/api-82.jar`

## Credenciais
- **Keystore**: `shared/module.keystore` — senha `manus2026`, alias `manus`, validade 10000 dias

## Próximos passos
1. Identificar o app dentro de `KMV_4.83.101.apks` (extrair, ler manifest, descobrir package name e nome real)
2. Decompilar com `jadx --deobf` e `apktool d`
3. Inventário de motores de detecção (RootBeer? JailMonkey? Adjoe? customs? Play Integrity?)
4. Análise de bibliotecas nativas com `strings`/`nm`
5. Mapear classes e métodos exatos a hookar (lista nominal)
6. Implementar `apps/<slug>/module/` seguindo o template
7. Compilar com `build.sh`, gerar APK, validar com `apksigner verify`
8. Documentar `apps/<slug>/{README.md, reverse_engineering.md}`
9. Atualizar tabela de apps em `README.md`, `KNOWLEDGE_BASE.md` (lições novas) e este CHECKPOINT.md
10. Commit + push

## Como retomar
```bash
cd ~/Bypass_advanced_carioca_root_apps
git pull
# Continue de: identificar APK base do KMV_4.83.101.apks (próximo passo 1 acima)
unzip -l /home/ubuntu/upload/KMV_4.83.101.apks
```
