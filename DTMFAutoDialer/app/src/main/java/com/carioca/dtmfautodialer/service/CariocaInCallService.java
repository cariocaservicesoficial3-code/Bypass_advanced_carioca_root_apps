package com.carioca.dtmfautodialer.service;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;

import com.carioca.dtmfautodialer.ui.InCallActivity;

/**
 * CariocaInCallService v1.7
 * 
 * Este serviço é o coração do Discador Padrão.
 * O Android chama este serviço sempre que uma ligação começa ou muda de estado.
 */
public class CariocaInCallService extends InCallService {

    private static final String TAG = "CariocaDialer";
    public static Call currentCall = null;

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        Log.d(TAG, "Chamada adicionada: " + call.getDetails().getHandle());
        currentCall = call;
        
        // Abrir a tela de chamada do nosso app
        Intent intent = new Intent(this, InCallActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
        startActivity(intent);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Log.d(TAG, "Chamada removida");
        if (currentCall == call) {
            currentCall = null;
        }
    }

    /**
     * Método estático para enviar tons DTMF de qualquer lugar do app
     */
    public static void sendDtmf(char digit) {
        if (currentCall != null) {
            Log.d(TAG, "Enviando DTMF: " + digit);
            currentCall.playDtmfTone(digit);
            
            // O Android recomenda parar o tom após um curto período
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (currentCall != null) {
                    currentCall.stopDtmfTone();
                }
            }, 150);
        } else {
            Log.e(TAG, "Erro: Nenhuma chamada ativa para enviar DTMF");
        }
    }
}
