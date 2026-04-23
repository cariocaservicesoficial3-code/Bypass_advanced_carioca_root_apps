# Protocolo de Checkpoint

Este documento descreve **como** a Manus deve ler e escrever checkpoints neste repositório, para que cada nova sessão recupere o contexto integral do projeto.

## Ao abrir uma nova sessão

Quando o usuário **conectar a Manus a este repositório no início de uma sessão**, a primeira coisa que a Manus faz é:

1. `git clone` (ou `git pull` se já existir) o repositório
2. **Ler nesta ordem**:
   1. `README.md` — visão geral e índice de apps
   2. `KNOWLEDGE_BASE.md` — todo conhecimento técnico acumulado
   3. `CHECKPOINT.md` — estado da última sessão e próximos passos
3. **Ler em seguida** o `apps/<último_app>/README.md` e `reverse_engineering.md` se a sessão anterior estava no meio de um app
4. Anunciar ao usuário: "Recuperei o checkpoint da sessão anterior. Estávamos em `<estado>`. Próximos passos planejados: `<lista>`. Quer continuar daí ou mudar?"

## Ao concluir uma evolução

Toda vez que houver uma evolução significativa — **novo app processado, novo motor catalogado, nova técnica descoberta, novo módulo compilado** — a Manus deve:

1. Atualizar o(s) arquivo(s) afetado(s):
   - `KNOWLEDGE_BASE.md` (se aprendeu algo novo aplicável a outros apps)
   - `apps/<app>/README.md` e `reverse_engineering.md` (se trabalhou num app)
   - Tabela de apps em `README.md`
2. **Sobrescrever** `CHECKPOINT.md` com o novo estado
3. Commitar com mensagem padronizada (ver formato abaixo)
4. `git push origin main`

## Formato do CHECKPOINT.md

```markdown
# Checkpoint — <YYYY-MM-DD HH:MM TZ>

## Estado atual
- App em foco: <nome> (`<package>`)
- Versão analisada: <X.Y.Z>
- Fase atual: <ex: "Implementação dos hooks de Adjoe">
- Última build: `apps/<app>/artifacts/<App>-RootBypass-vX.Y.Z.apk` (MD5: `<hash>`)

## Resumo da sessão
<Bullet list do que foi feito nesta sessão>

## Conhecimento novo
<O que foi adicionado ao KNOWLEDGE_BASE.md nesta sessão>

## Próximos passos
1. <passo>
2. <passo>
...

## Como retomar
```bash
cd <local>/Bypass_advanced_carioca_root_apps
git pull
# Continue de: <comando ou arquivo exato>
```
```

## Formato de commit

```
<tipo>(<escopo>): <descrição curta>

<corpo opcional>

Checkpoint: <referência>
```

Tipos: `feat` (novo módulo/feature), `fix` (correção em hook), `kb` (atualização de Knowledge Base), `docs`, `build`, `ckpt` (atualização de checkpoint isolada).

Exemplos:
- `feat(kmv): módulo LSPosed v1.0.0 com 18 camadas de bypass`
- `kb: adicionar engine "AppGallery Integrity" ao catálogo`
- `ckpt: pausa após análise estática do KMV, faltam libs nativas`

## Recursos compartilhados (shared/)

- `XposedBridgeApi-82.jar` — usar nos build.sh com `-classpath`
- `module.keystore` — keystore de dev. Senha gerenciada em `CHECKPOINT.md` (na seção "Credenciais").
  - Quando criar a primeira vez, registrar a senha lá.

## Regras de ouro

1. **Nunca** subir APKs do app **alvo** (somente o módulo gerado por nós)
2. **Sempre** atualizar `CHECKPOINT.md` antes de encerrar uma sessão
3. **Sempre** commitar e push antes de encerrar — nada fica só local
4. Documentar todo método hookado com a fonte (smali path, símbolo nativo, string âncora)
