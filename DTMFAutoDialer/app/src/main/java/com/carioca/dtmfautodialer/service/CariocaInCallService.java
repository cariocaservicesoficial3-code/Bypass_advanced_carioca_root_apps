package com.carioca.dtmfautodialer.service;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.util.Log;

import com.carioca.dtmfautodialer.ui.MainActivity;

/**
 * CariocaInCallService v1.8
 *
 * Coração do Discador Padrão.
 * O Android chama este serviço sempre que uma ligação começa ou muda de estado.
 *
 * Ao detectar uma chamada, abre a MainActivity na aba "Em Ligação"
 * em vez de uma Activity separada.
 */
public class CariocaInCallService extends InCallService {

    private static final String TAG = "CariocaDialer";

    /** Chamada ativa atual — acessível de qualquer lugar do app */
    public static Call currentCall = null;
    public static CariocaInCallService instance = null;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        Log.d(TAG, "Chamada adicionada: " + call.getDetails().getHandle());
        currentCall = call;

        // Registrar callback para monitorar mudanças de estado da chamada
        call.registerCallback(callCallback);

        // Abrir MainActivity diretamente na aba "Em Ligação"
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
        intent.putExtra(MainActivity.EXTRA_GO_TO_INCALL, true);
        startActivity(intent);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Log.d(TAG, "Chamada removida");
        call.unregisterCallback(callCallback);
        if (currentCall == call) {
            currentCall = null;
        }
    }

    /**
     * Callback para monitorar mudanças de estado da chamada
     */
    private final Call.Callback callCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            Log.d(TAG, "Estado da chamada mudou: " + state);
            // Atualização de estado é tratada pelo InCallFragment via polling
        }
    };

    /**
     * Envia um tom DTMF diretamente via API oficial do Android.
     * Pode ser chamado de qualquer lugar do app.
     *
     * @param digit Caractere DTMF: 0-9, *, #
     */
    /**
     * Alterna o Viva-Voz (Speakerphone)
     */
    public void setSpeaker(boolean on) {
        if (on) {
            setAudioRoute(CallAudioState.ROUTE_SPEAKER);
        } else {
            setAudioRoute(CallAudioState.ROUTE_EARPIECE);
        }
    }

    public static void sendDtmf(char digit) {
        if (currentCall != null) {
            Log.d(TAG, "Enviando DTMF: " + digit);
            currentCall.playDtmfTone(digit);

            // Parar o tom após 150ms (recomendação do Android)
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (currentCall != null) {
                    currentCall.stopDtmfTone();
                }
            }, 150);
        } else {
            Log.e(TAG, "sendDtmf: Nenhuma chamada ativa");
        }
    }
}
