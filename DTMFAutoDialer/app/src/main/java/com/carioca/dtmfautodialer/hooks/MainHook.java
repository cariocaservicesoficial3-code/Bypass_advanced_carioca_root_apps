package com.carioca.dtmfautodialer.hooks;

import android.app.AndroidAppHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.ArrayList;
import java.util.List;

/**
 * MainHook - Hook principal do módulo LSPosed DTMF Auto Dialer
 *
 * Este módulo intercepta o InCallService do Android para capturar chamadas ativas
 * e permite enviar tons DTMF automaticamente em sequência durante uma ligação.
 *
 * Funciona com qualquer app de telefone (Google Phone, Samsung Phone, AOSP Dialer, etc.)
 * pois hookamos a API do framework Android, não classes específicas do app.
 *
 * Baseado na engenharia reversa do Google Phone (com.google.android.dialer) v218.0
 * que revelou o uso de android.telecom.Call.playDtmfTone() / stopDtmfTone()
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "DTMFAutoDialer";

    // Ação do broadcast para receber sequência de dígitos do widget flutuante
    public static final String ACTION_SEND_DTMF_SEQUENCE = "com.carioca.dtmfautodialer.SEND_DTMF_SEQUENCE";
    public static final String ACTION_STOP_DTMF = "com.carioca.dtmfautodialer.STOP_DTMF";
    public static final String ACTION_CALL_STATE_CHANGED = "com.carioca.dtmfautodialer.CALL_STATE_CHANGED";
    public static final String EXTRA_DIGITS = "digits";
    public static final String EXTRA_DELAY_MS = "delay_ms";
    public static final String EXTRA_CALL_ACTIVE = "call_active";

    // Referência estática para a chamada ativa (acessível pelo broadcast receiver)
    private static Call sActiveCall = null;
    private static boolean sIsPlaying = false;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    // Lista de pacotes de apps de telefone conhecidos
    private static final String[] DIALER_PACKAGES = {
            "com.google.android.dialer",      // Google Phone
            "com.android.dialer",              // AOSP Dialer
            "com.samsung.android.dialer",      // Samsung Phone
            "com.android.incallui",            // InCallUI genérico
            "com.android.server.telecom",      // Telecom Server
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // Verificar se é um dos apps de telefone alvo
        boolean isTargetPackage = false;
        for (String pkg : DIALER_PACKAGES) {
            if (lpparam.packageName.equals(pkg)) {
                isTargetPackage = true;
                break;
            }
        }

        if (!isTargetPackage) return;

        XposedBridge.log(TAG + ": Hooking package: " + lpparam.packageName);

        // ============================================================
        // HOOK 1: InCallService.onCallAdded(Call)
        // Captura quando uma nova chamada é adicionada (ativa)
        // ============================================================
        XposedHelpers.findAndHookMethod(
                "android.telecom.InCallService",
                lpparam.classLoader,
                "onCallAdded",
                Call.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Call call = (Call) param.args[0];
                        sActiveCall = call;
                        XposedBridge.log(TAG + ": Call added! State: " + call.getState());

                        // Registrar callback para monitorar mudanças de estado
                        call.registerCallback(new Call.Callback() {
                            @Override
                            public void onStateChanged(Call call, int state) {
                                XposedBridge.log(TAG + ": Call state changed to: " + state);
                                if (state == Call.STATE_ACTIVE) {
                                    sActiveCall = call;
                                    broadcastCallState(true);
                                } else if (state == Call.STATE_DISCONNECTED ||
                                        state == Call.STATE_DISCONNECTING) {
                                    sActiveCall = null;
                                    sIsPlaying = false;
                                    broadcastCallState(false);
                                }
                            }
                        });

                        // Registrar o receiver para receber comandos DTMF
                        registerDtmfReceiver();

                        // Notificar que há chamada ativa
                        if (call.getState() == Call.STATE_ACTIVE) {
                            broadcastCallState(true);
                        }
                    }
                }
        );

        // ============================================================
        // HOOK 2: InCallService.onCallRemoved(Call)
        // Captura quando uma chamada é removida (encerrada)
        // ============================================================
        XposedHelpers.findAndHookMethod(
                "android.telecom.InCallService",
                lpparam.classLoader,
                "onCallRemoved",
                Call.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + ": Call removed!");
                        sActiveCall = null;
                        sIsPlaying = false;
                        broadcastCallState(false);
                    }
                }
        );

        XposedBridge.log(TAG + ": All hooks installed successfully for " + lpparam.packageName);
    }

    /**
     * Registra o BroadcastReceiver que recebe comandos do widget flutuante
     */
    private static boolean sReceiverRegistered = false;

    private void registerDtmfReceiver() {
        if (sReceiverRegistered) return;

        try {
            Context context = AndroidAppHelper.currentApplication();
            if (context == null) return;

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action == null) return;

                    switch (action) {
                        case ACTION_SEND_DTMF_SEQUENCE:
                            String digits = intent.getStringExtra(EXTRA_DIGITS);
                            int delayMs = intent.getIntExtra(EXTRA_DELAY_MS, 300);
                            if (digits != null && !digits.isEmpty()) {
                                sendDtmfSequence(digits, delayMs);
                            }
                            break;

                        case ACTION_STOP_DTMF:
                            stopDtmfSequence();
                            break;
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_SEND_DTMF_SEQUENCE);
            filter.addAction(ACTION_STOP_DTMF);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }

            sReceiverRegistered = true;
            XposedBridge.log(TAG + ": DTMF receiver registered successfully");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error registering receiver: " + e.getMessage());
        }
    }

    /**
     * Envia uma sequência de dígitos DTMF com delay configurável entre cada dígito
     *
     * @param digits  String com os dígitos (ex: "07281859112")
     * @param delayMs Delay em milissegundos entre cada dígito
     */
    private static void sendDtmfSequence(String digits, int delayMs) {
        if (sActiveCall == null) {
            XposedBridge.log(TAG + ": No active call to send DTMF!");
            return;
        }

        if (sIsPlaying) {
            XposedBridge.log(TAG + ": Already playing DTMF sequence, stopping first...");
            stopDtmfSequence();
        }

        // Extrair apenas dígitos válidos para DTMF (0-9, *, #)
        String cleanDigits = digits.replaceAll("[^0-9*#]", "");
        if (cleanDigits.isEmpty()) {
            XposedBridge.log(TAG + ": No valid DTMF digits found in: " + digits);
            return;
        }

        XposedBridge.log(TAG + ": Sending DTMF sequence: " + cleanDigits +
                " (delay: " + delayMs + "ms)");

        sIsPlaying = true;
        final char[] digitArray = cleanDigits.toCharArray();

        // Enviar cada dígito com delay
        for (int i = 0; i < digitArray.length; i++) {
            final int index = i;
            final char digit = digitArray[i];

            // Agendar playDtmfTone
            sHandler.postDelayed(() -> {
                if (!sIsPlaying || sActiveCall == null) return;

                try {
                    XposedBridge.log(TAG + ": Playing DTMF tone: " + digit +
                            " (" + (index + 1) + "/" + digitArray.length + ")");

                    // Tocar o tom DTMF
                    sActiveCall.playDtmfTone(digit);

                    // Parar o tom após 150ms (duração do tom)
                    sHandler.postDelayed(() -> {
                        if (sActiveCall != null) {
                            sActiveCall.stopDtmfTone();
                        }

                        // Broadcast de progresso
                        broadcastProgress(index + 1, digitArray.length, digit);

                        // Se foi o último dígito, marcar como concluído
                        if (index == digitArray.length - 1) {
                            sIsPlaying = false;
                            broadcastComplete();
                        }
                    }, 150); // Duração do tom

                } catch (Exception e) {
                    XposedBridge.log(TAG + ": Error playing DTMF tone: " + e.getMessage());
                    sIsPlaying = false;
                }
            }, (long) i * delayMs); // Delay entre dígitos
        }
    }

    /**
     * Para a sequência DTMF em andamento
     */
    private static void stopDtmfSequence() {
        sIsPlaying = false;
        sHandler.removeCallbacksAndMessages(null);
        if (sActiveCall != null) {
            try {
                sActiveCall.stopDtmfTone();
            } catch (Exception e) {
                XposedBridge.log(TAG + ": Error stopping DTMF: " + e.getMessage());
            }
        }
        XposedBridge.log(TAG + ": DTMF sequence stopped");
    }

    /**
     * Envia broadcast informando estado da chamada
     */
    private static void broadcastCallState(boolean active) {
        try {
            Context context = AndroidAppHelper.currentApplication();
            if (context == null) return;

            Intent intent = new Intent(ACTION_CALL_STATE_CHANGED);
            intent.putExtra(EXTRA_CALL_ACTIVE, active);
            context.sendBroadcast(intent);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error broadcasting call state: " + e.getMessage());
        }
    }

    /**
     * Envia broadcast de progresso da sequência DTMF
     */
    private static void broadcastProgress(int current, int total, char digit) {
        try {
            Context context = AndroidAppHelper.currentApplication();
            if (context == null) return;

            Intent intent = new Intent("com.carioca.dtmfautodialer.DTMF_PROGRESS");
            intent.putExtra("current", current);
            intent.putExtra("total", total);
            intent.putExtra("digit", String.valueOf(digit));
            context.sendBroadcast(intent);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error broadcasting progress: " + e.getMessage());
        }
    }

    /**
     * Envia broadcast quando a sequência DTMF é concluída
     */
    private static void broadcastComplete() {
        try {
            Context context = AndroidAppHelper.currentApplication();
            if (context == null) return;

            Intent intent = new Intent("com.carioca.dtmfautodialer.DTMF_COMPLETE");
            context.sendBroadcast(intent);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error broadcasting complete: " + e.getMessage());
        }
    }
}
