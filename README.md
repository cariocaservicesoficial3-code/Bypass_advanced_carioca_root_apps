# Carioca Services — Bypass Advanced Root Apps

> Knowledge base e arsenal de **módulos LSPosed cirúrgicos** para neutralizar detecção de root, Magisk, bootloader desbloqueado, custom ROM, Frida, Xposed, emulador e Play Integrity em apps Android específicos.

## Filosofia do projeto

Cada app passa por **engenharia reversa integral** (decompilação Java/Smali, análise de bibliotecas nativas, mapeamento de strings) para identificar **todos** os motores de detecção que ele utiliza, e então recebe um módulo LSPosed dedicado com **defesa em profundidade**: cada motor é neutralizado nos seus pontos exatos (classes/métodos identificados pelo nome real ou pelo nome obfuscado pós-R8/ProGuard), e em paralelo o framework Android é blindado (`Build.TAGS`, `SystemProperties`, `PackageManager`, `Runtime.exec`, `File`, `Settings`, `Debug`).

> **Princípio cirúrgico**: nada de "monkey patch" genérico. Cada hook é justificado por uma evidência extraída do APK alvo (smali, string nativa, símbolo JNI, fluxo de chamada).

## Estrutura do repositório

```
Bypass_advanced_carioca_root_apps/
├── README.md                          # este arquivo (índice geral)
├── KNOWLEDGE_BASE.md                  # conhecimento acumulado: motores de detecção, padrões, técnicas
├── CHECKPOINT.md                      # estado atual da sessão (sempre atualizado a cada evolução)
├── apps/                              # um diretório por app processado
│   └── <app_slug>/
│       ├── README.md                  # ficha técnica do app (pacote, versão, motores, hooks)
│       ├── reverse_engineering.md     # mapa completo da engenharia reversa
│       ├── module/                    # código-fonte do módulo LSPosed
│       │   ├── AndroidManifest.xml
│       │   ├── build.sh
│       │   ├── res/
│       │   ├── src/
│       │   └── libs/
│       └── artifacts/                 # APK assinado pronto para uso
│           └── <App>-RootBypass-vX.Y.Z.apk
├── shared/                            # recursos compartilhados entre módulos
│   ├── XposedBridgeApi-82.jar
│   └── module.keystore                # keystore de dev (senha em CHECKPOINT.md)
└── docs/                              # referências técnicas, comparativos, playbooks
    ├── lsposed_module_template.md
    ├── detection_engines_catalog.md
    └── checkpoint_protocol.md
```

## Sistema de Checkpoints

Inspirado em "save points" de jogos: a cada evolução significativa (novo app processado, novo motor catalogado, nova técnica descoberta) é gravado um **checkpoint** em `CHECKPOINT.md` contendo:

- Estado atual (último app trabalhado, fase atual)
- Conhecimento novo aprendido nessa sessão
- Próximos passos planejados
- Como retomar (comandos exatos, arquivos relevantes)

Quando uma nova sessão é aberta e a Manus se conecta a este repositório, ela **lê primeiro** `CHECKPOINT.md` e `KNOWLEDGE_BASE.md` para recuperar todo o contexto e continuar de onde parou.

Veja `docs/checkpoint_protocol.md` para o protocolo completo de leitura/escrita.

## Apps processados

| # | App | Pacote | Versão | Motores neutralizados | Status | Pasta |
|---|-----|--------|--------|------------------------|--------|-------|
| 1 | KMV (a confirmar) | a definir | 4.83.101 | a definir | em análise | [apps/kmv](apps/kmv/) |

(Tabela atualizada automaticamente a cada novo módulo.)

## Aviso legal

Este repositório existe estritamente para **pesquisa pessoal de segurança defensiva, engenharia reversa educacional e teste de aplicativos em dispositivos próprios da equipe Carioca Services**. Os módulos são distribuídos sem garantia. Não há intenção de fraudar serviços, evadir verificações de pagamento, contornar licenças ou qualquer atividade ilegal. Use apenas em dispositivos que você possui e em apps sobre os quais você tem direito de testar.
