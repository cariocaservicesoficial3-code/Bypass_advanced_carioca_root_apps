# Achados da Engenharia Reversa - Google Phone (com.google.android.dialer)

## Pacote: com.google.android.dialer
## Versão: 218.0.898653443

## Classes Chave Identificadas:

### 1. InCallServiceImpl (com.android.incallui.InCallServiceImpl)
- Estende `wtj` que estende `InCallService`
- Classe principal do serviço de chamada em andamento
- Gerencia callbacks de chamadas (onCallAdded, onCallRemoved, etc.)

### 2. CallControllerImpl (pjx.java)
- Classe original: `com.android.dialer.incall.core.call.CallControllerImpl`
- **Método `t(char c)`** → `playDtmfTone(char)` - Envia tom DTMF
  - Linha 280-302: `this.b.playDtmfTone(c)` onde `this.b` é `android.telecom.Call`
- **Método `v()`** → `stopDtmfTone()` - Para tom DTMF
  - Linha 310-314: `this.b.stopDtmfTone()`

### 3. XatuDtmfPlayer (wkf.java)
- Classe original: `com.android.dialer.xatu.impl.service.XatuDtmfPlayer`
- Player de tons DTMF em sequência
- Método `a(char[] cArr, ...)` → `playDtmfTones` - Toca sequência de tons

### 4. API Android Framework usada:
- `android.telecom.Call.playDtmfTone(char)` - API padrão do Android
- `android.telecom.Call.stopDtmfTone()` - API padrão do Android
- `android.telecom.InCallService` - Serviço base

## Estratégia do Módulo LSPosed:
O módulo NÃO precisa hookar classes ofuscadas do Google Dialer.
Podemos usar APIs padrão do Android Framework:
1. Hookar `android.telecom.InCallService` para capturar chamadas ativas
2. Usar `android.telecom.Call.playDtmfTone()` e `stopDtmfTone()` diretamente
3. Interface própria para colar números e disparar sequência DTMF
