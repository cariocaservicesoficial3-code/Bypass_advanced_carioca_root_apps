# DTMF Auto Dialer - Módulo LSPosed

Módulo LSPosed/Xposed que permite digitar automaticamente uma sequência de números DTMF durante ligações telefônicas. Desenvolvido por **Carioca Services**.

## Funcionalidade

O módulo intercepta o serviço de chamada do Android (`InCallService`) e permite enviar tons DTMF em sequência automaticamente. Basta colar uma sequência de números (CPF, telefone, código de atendimento, etc.) e o módulo digita cada número no teclado do telefone durante a ligação.

### Casos de Uso

- Digitar CPF automaticamente em menus de atendimento (URA)
- Digitar número de protocolo durante ligações
- Navegar menus telefônicos automaticamente
- Enviar códigos USSD em sequência

## Arquitetura

O módulo funciona em três camadas:

### 1. Hook Layer (`MainHook.java`)
- Intercepta `InCallService.onCallAdded()` e `onCallRemoved()` do Android Framework
- Captura a referência da chamada ativa (`android.telecom.Call`)
- Registra `BroadcastReceiver` para receber comandos do widget flutuante
- Usa `Call.playDtmfTone(char)` e `Call.stopDtmfTone()` para enviar tons DTMF
- Envia cada dígito com delay configurável entre eles

### 2. Floating Widget (`FloatingWidgetService.java`)
- Widget flutuante que aparece sobre o app de telefone durante a ligação
- Permite colar números da área de transferência
- Configurar delay entre dígitos (100ms a 1000ms)
- Acompanhar progresso em tempo real
- Parar a sequência a qualquer momento

### 3. Main Activity (`MainActivity.java`)
- Interface principal para configuração do módulo
- Verificação de status do módulo no LSPosed
- Histórico de sequências enviadas
- Configurações persistentes

## Engenharia Reversa

O módulo foi desenvolvido com base na engenharia reversa do **Google Phone** (`com.google.android.dialer`) versão 218.0, utilizando a ferramenta [android-reverse-engineering-skill](https://github.com/SimoneAvogadro/android-reverse-engineering-skill).

### Classes Identificadas

| Classe Ofuscada | Classe Original | Função |
|---|---|---|
| `wtj` | `InCallService` (base) | Serviço de chamada |
| `pjx` | `CallControllerImpl` | Controle de chamada com DTMF |
| `wkf` | `XatuDtmfPlayer` | Player de sequência DTMF |
| `InCallServiceImpl` | `InCallServiceImpl` | Implementação do serviço |

### APIs Android Utilizadas

```java
// Enviar tom DTMF (API padrão do Android)
android.telecom.Call.playDtmfTone(char digit)
android.telecom.Call.stopDtmfTone()

// Serviço de chamada
android.telecom.InCallService.onCallAdded(Call call)
android.telecom.InCallService.onCallRemoved(Call call)
```

## Compatibilidade

| App de Telefone | Suportado |
|---|---|
| Google Phone (com.google.android.dialer) | Sim |
| AOSP Dialer (com.android.dialer) | Sim |
| Samsung Phone (com.samsung.android.dialer) | Sim |
| Qualquer app que use InCallService | Sim |

**Requisitos:**
- Android 8.0+ (API 26+)
- LSPosed Framework instalado
- Root com Magisk ou KernelSU

## Instalação

1. Instale o LSPosed Framework no seu dispositivo
2. Instale o APK do DTMF Auto Dialer
3. Abra o LSPosed Manager
4. Ative o módulo "DTMF Auto Dialer"
5. Selecione o app de Telefone no escopo do módulo
6. Reinicie o dispositivo
7. Abra o DTMF Auto Dialer e conceda permissão de overlay

## Como Usar

1. Copie a sequência de números que deseja digitar
2. Abra o DTMF Auto Dialer e inicie o widget flutuante
3. Faça a ligação normalmente
4. No widget flutuante, cole os números e toque em **DIGITAR**
5. O módulo digitará cada número automaticamente no teclado

### Formatos Aceitos

- `072.818.591-12` (com pontuação - pontuação é removida automaticamente)
- `07281859112` (somente números)
- `*123#` (códigos USSD)
- Qualquer combinação de `0-9`, `*` e `#`

## Compilação

```bash
# Clone o repositório
git clone https://github.com/cariocaservicesoficial3-code/Bypass_advanced_carioca_root_apps.git

# Abra no Android Studio
# Ou compile via linha de comando:
cd DTMFAutoDialer
./gradlew assembleRelease
```

## Estrutura do Projeto

```
DTMFAutoDialer/
├── app/
│   ├── src/main/
│   │   ├── java/com/carioca/dtmfautodialer/
│   │   │   ├── hooks/
│   │   │   │   └── MainHook.java          # Hook principal do Xposed
│   │   │   ├── service/
│   │   │   │   └── FloatingWidgetService.java  # Widget flutuante
│   │   │   └── ui/
│   │   │       └── MainActivity.java      # Interface principal
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml      # Layout da tela principal
│   │   │   ├── drawable/                  # Backgrounds e botões
│   │   │   └── values/                    # Strings, cores, temas
│   │   ├── assets/
│   │   │   └── xposed_init                # Registro do módulo Xposed
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
├── reverse_engineering_findings.md         # Achados da engenharia reversa
└── README.md
```

## Licença

Este projeto é fornecido para fins educacionais e de pesquisa. Use por sua conta e risco.

## Créditos

- **Carioca Services** - Desenvolvimento
- [android-reverse-engineering-skill](https://github.com/SimoneAvogadro/android-reverse-engineering-skill) - Ferramenta de engenharia reversa
- [LSPosed Framework](https://github.com/LSPosed/LSPosed) - Framework de hooking
