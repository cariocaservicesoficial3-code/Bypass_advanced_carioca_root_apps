# Carioca Dialer — DTMF Auto Dialer v1.8

Aplicativo Android que funciona como **Discador Padrão** com automação de digitação DTMF integrada. Ao ser definido como discador padrão, o app tem controle total sobre as chamadas e pode enviar tons DTMF diretamente via API oficial do Android (`Call.playDtmfTone()`), sem necessidade de ROOT.

---

## Novidades da v1.8

### Correções
- **Botão flutuante restaurado**: O botão "INICIAR WIDGET FLUTUANTE" estava ausente desde a v1.7. Agora está de volta na aba Discador, com botão de parar também.
- **Arquitetura de abas**: A tela principal agora usa `TabLayout + ViewPager2` com duas abas separadas.

### Novas funcionalidades
- **Aba 1 — DISCADOR**: Tela para fazer ligações, definir como discador padrão, preparar sequência DTMF antes de ligar e iniciar/parar o widget flutuante.
- **Aba 2 — EM LIGAÇÃO**: Aparece automaticamente ao receber uma chamada. Mostra número e estado da chamada em tempo real, permite colar sequência DTMF e dispara a digitação automática via API.
- **Auto-colar ao entrar na aba Em Ligação**: Ao navegar para a aba "Em Ligação", o app tenta colar automaticamente o conteúdo da área de transferência no campo de sequência.
- **Delay configurável**: SeekBar de 100ms a 1500ms em ambas as abas, sincronizadas.
- **Botão PARAR DIGITAÇÃO**: Interrompe a sequência a qualquer momento.
- **Widget flutuante melhorado**: Botão minimizar/expandir, botão para abrir o app principal, notificação com ação de fechar.
- **Navegação automática**: Ao receber uma chamada, o app abre diretamente na aba "Em Ligação".

---

## Como usar

### Modo Discador Padrão (recomendado — sem ROOT)

1. Instale o APK `CariocaDialer-v1.8-debug.apk`
2. Abra o app → Aba **DISCADOR**
3. Toque em **"DEFINIR COMO DISCADOR PADRÃO"** e confirme
4. O status no topo ficará verde: `DISCADOR PADRAO`
5. **Antes de ligar**: Cole a sequência DTMF (CPF, ramal, senha) no campo "Sequência DTMF"
6. Digite o número e toque em **LIGAR**
7. Quando a chamada for atendida, o app abre automaticamente na aba **EM LIGAÇÃO**
8. A sequência pré-configurada é transferida automaticamente (ou cole uma nova)
9. Toque em **DIGITAR AGORA** — os tons DTMF são enviados via API oficial

### Modo Widget Flutuante (alternativo — requer ROOT para keyevent)

1. Aba **DISCADOR** → **"INICIAR WIDGET FLUTUANTE"**
2. Conceda a permissão "Exibir sobre outros apps" se solicitado
3. O widget aparece sobre qualquer app
4. Cole os números no widget e toque em **DIGITAR**
5. O widget aguarda 2 segundos para você tocar no teclado do app de telefone
6. Usa `su input keyevent` para simular as teclas (requer ROOT)

---

## Formatos aceitos

| Formato | Exemplo |
|---|---|
| Somente números | `07281859112` |
| CPF com pontuação | `072.818.591-12` |
| Código USSD | `*123#` |
| Ramal com * | `*200` |
| Qualquer combinação | `0-9`, `*`, `#` |

A pontuação (`.`, `-`, espaços) é removida automaticamente antes do envio.

---

## Arquitetura

```
DTMFAutoDialer/
├── ui/
│   ├── MainActivity.java         # Activity principal com TabLayout + ViewPager2
│   ├── MainPagerAdapter.java     # Adapter das duas abas
│   ├── DialerFragment.java       # Aba 1: Discador
│   └── InCallFragment.java       # Aba 2: Em Ligação
├── service/
│   ├── CariocaInCallService.java # InCallService (coração do discador padrão)
│   └── FloatingWidgetService.java # Widget overlay flutuante
└── hooks/
    └── MainHook.java             # Hook LSPosed (legado, mantido para compatibilidade)
```

---

## Histórico de versões

| Versão | Descrição |
|---|---|
| v1.8.0 | Abas separadas, botão flutuante restaurado, auto-colar, delay configurável |
| v1.7.0 | Discador padrão, InCallService, InCallActivity separada |
| v1.6.x | Widget flutuante com correção de foco |
| v1.0–v1.5 | Módulo LSPosed com hooks no app Telefone |
