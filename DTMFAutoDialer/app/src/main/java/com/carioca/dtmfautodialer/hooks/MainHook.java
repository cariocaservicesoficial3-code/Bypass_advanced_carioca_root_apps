package com.carioca.dtmfautodialer.hooks;

import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * MainHook - Hook principal do módulo LSPosed DTMF Auto Dialer v1.2
 *
 * ARQUITETURA DE COMUNICAÇÃO:
 * - O widget flutuante (FloatingWidgetService) escreve um arquivo de comando
 *   em /sdcard/Android/data/.dtmf_command com os dígitos e delay
 * - O hook (este arquivo) monitora esse arquivo com FileObserver
 * - Quando o arquivo é modificado, o hook lê os dígitos e envia DTMF
 * - O hook escreve progresso/status em /sdcard/Android/data/.dtmf_status
 * - O widget lê o status via polling
 *
 * Isso resolve o problema de broadcasts implícitos bloqueados no Android 11+
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "DTMFAutoDialer";
    private static final String SELF_PACKAGE = "com.carioca.dtmfautodialer";

    // Arquivos de comunicação cross-process
    public static final String COMMAND_DIR = "/sdcard/Android/data/.dtmf_autodialer";
    public static final String COMMAND_FILE = COMMAND_DIR + "/command.txt";
    public static final String STATUS_FILE = COMMAND_DIR + "/status.txt";

    // Constantes para ações do broadcast (mantidas para compatibilidade interna)
    public static final String ACTION_SEND_DTMF_SEQUENCE = "com.carioca.dtmfautodialer.SEND_DTMF_SEQUENCE";
    public static final String ACTION_STOP_DTMF = "com.carioca.dtmfautodialer.STOP_DTMF";
    public static final String ACTION_CALL_STATE_CHANGED = "com.carioca.dtmfautodialer.CALL_STATE_CHANGED";
    public static final String EXTRA_DIGITS = "digits";
    public static final String EXTRA_DELAY_MS = "delay_ms";
    public static final String EXTRA_CALL_ACTIVE = "call_active";

    // Referência estática para a chamada ativa
    private static Call sActiveCall = null;
    private static boolean sIsPlaying = false;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    // FileObserver para monitorar comandos
    private static FileObserver sFileObserver = null;
    private static boolean sObserverStarted = false;

    // Lista de pacotes de apps de telefone conhecidos
    private static final String[] DIALER_PACKAGES = {
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.android.incallui",
            "com.android.server.telecom",
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // ============================================================
        // HOOK PARA O PRÓPRIO APP DO MÓDULO
        // ============================================================
        if (lpparam.packageName.equals(SELF_PACKAGE)) {
            XposedBridge.log(TAG + ": Hooking self package for isModuleActive()");
            try {
                XposedHelpers.findAndHookMethod(
                        SELF_PACKAGE + ".ui.MainActivity",
                        lpparam.classLoader,
                        "isModuleActive",
                        XC_MethodReplacement.returnConstant(true)
                );
                XposedBridge.log(TAG + ": isModuleActive() hooked -> returns true");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Error hooking isModuleActive: " + t.getMessage());
            }
            return;
        }

        // ============================================================
        // HOOKS PARA APPS DE TELEFONE
        // ============================================================
        boolean isTargetPackage = false;
        for (String pkg : DIALER_PACKAGES) {
            if (lpparam.packageName.equals(pkg)) {
                isTargetPackage = true;
                break;
            }
        }

        if (!isTargetPackage) return;

        XposedBridge.log(TAG + ": Hooking dialer package: " + lpparam.packageName);

        // Criar diretório de comunicação
        ensureCommandDir();

        // Iniciar FileObserver para monitorar comandos do widget
        startFileObserver();

        // ============================================================
        // HOOK 1: InCallService.onCallAdded(Call)
        // ============================================================
        try {
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

                            // Registrar callback para monitorar estado da chamada
                            call.registerCallback(new Call.Callback() {
                                @Override
                                public void onStateChanged(Call call, int state) {
                                    XposedBridge.log(TAG + ": Call state changed to: " + state);
                                    if (state == Call.STATE_ACTIVE) {
                                        sActiveCall = call;
                                        writeStatus("CALL_ACTIVE");
                                    } else if (state == Call.STATE_DISCONNECTED ||
                                            state == Call.STATE_DISCONNECTING) {
                                        sActiveCall = null;
                                        sIsPlaying = false;
                                        writeStatus("CALL_ENDED");
                                    }
                                }
                            });

                            // Garantir que o FileObserver está rodando
                            startFileObserver();

                            // Notificar estado
                            if (call.getState() == Call.STATE_ACTIVE) {
                                writeStatus("CALL_ACTIVE");
                            } else {
                                writeStatus("CALL_RINGING");
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": onCallAdded hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error hooking onCallAdded: " + t.getMessage());
        }

        // ============================================================
        // HOOK 2: InCallService.onCallRemoved(Call)
        // ============================================================
        try {
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
                            writeStatus("CALL_ENDED");
                        }
                    }
            );
            XposedBridge.log(TAG + ": onCallRemoved hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error hooking onCallRemoved: " + t.getMessage());
        }

        XposedBridge.log(TAG + ": All hooks installed for " + lpparam.packageName);
    }

    // ============================================================
    // COMUNICAÇÃO VIA ARQUIVO
    // ============================================================

    /**
     * Garante que o diretório de comunicação existe
     */
    private static void ensureCommandDir() {
        try {
            File dir = new File(COMMAND_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            // Criar arquivos se não existem
            File cmd = new File(COMMAND_FILE);
            if (!cmd.exists()) {
                cmd.createNewFile();
            }
            File status = new File(STATUS_FILE);
            if (!status.exists()) {
                status.createNewFile();
            }
            XposedBridge.log(TAG + ": Command directory ready: " + COMMAND_DIR);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error creating command dir: " + e.getMessage());
        }
    }

    /**
     * Inicia o FileObserver para monitorar o arquivo de comando
     */
    private static void startFileObserver() {
        if (sObserverStarted) return;

        try {
            ensureCommandDir();

            // FileObserver monitora o diretório inteiro
            sFileObserver = new FileObserver(COMMAND_DIR, FileObserver.CLOSE_WRITE | FileObserver.MODIFY) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null && path.equals("command.txt")) {
                        XposedBridge.log(TAG + ": Command file changed! Reading...");
                        sHandler.post(() -> processCommand());
                    }
                }
            };
            sFileObserver.startWatching();
            sObserverStarted = true;
            XposedBridge.log(TAG + ": FileObserver started watching " + COMMAND_DIR);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error starting FileObserver: " + e.getMessage());

            // Fallback: usar polling a cada 500ms
            XposedBridge.log(TAG + ": Starting polling fallback...");
            startPolling();
        }
    }

    /**
     * Fallback: polling do arquivo de comando a cada 500ms
     */
    private static long sLastCommandModified = 0;

    private static void startPolling() {
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    File cmdFile = new File(COMMAND_FILE);
                    if (cmdFile.exists() && cmdFile.lastModified() > sLastCommandModified) {
                        sLastCommandModified = cmdFile.lastModified();
                        processCommand();
                    }
                } catch (Exception e) {
                    // Ignorar
                }
                // Continuar polling enquanto houver chamada ativa
                if (sActiveCall != null) {
                    sHandler.postDelayed(this, 500);
                } else {
                    sHandler.postDelayed(this, 2000);
                }
            }
        }, 500);
    }

    /**
     * Processa o comando lido do arquivo
     * Formato: SEND|dígitos|delay_ms  ou  STOP
     */
    private static void processCommand() {
        try {
            File cmdFile = new File(COMMAND_FILE);
            if (!cmdFile.exists() || cmdFile.length() == 0) return;

            BufferedReader reader = new BufferedReader(new FileReader(cmdFile));
            String line = reader.readLine();
            reader.close();

            if (line == null || line.isEmpty()) return;

            XposedBridge.log(TAG + ": Processing command: " + line);

            String[] parts = line.split("\\|");
            String action = parts[0].trim();

            if ("SEND".equals(action) && parts.length >= 3) {
                String digits = parts[1].trim();
                int delayMs = Integer.parseInt(parts[2].trim());

                // Limpar o arquivo de comando para não reprocessar
                clearCommandFile();

                sendDtmfSequence(digits, delayMs);

            } else if ("STOP".equals(action)) {
                clearCommandFile();
                stopDtmfSequence();
            }

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error processing command: " + e.getMessage());
        }
    }

    /**
     * Limpa o arquivo de comando
     */
    private static void clearCommandFile() {
        try {
            FileWriter writer = new FileWriter(COMMAND_FILE, false);
            writer.write("");
            writer.close();
        } catch (Exception e) {
            // Ignorar
        }
    }

    /**
     * Escreve status no arquivo de status para o widget ler
     * Formatos:
     *   CALL_ACTIVE
     *   CALL_ENDED
     *   CALL_RINGING
     *   PROGRESS|current|total|digit
     *   COMPLETE
     *   ERROR|mensagem
     */
    private static void writeStatus(String status) {
        try {
            ensureCommandDir();
            FileWriter writer = new FileWriter(STATUS_FILE, false);
            writer.write(status + "\n" + System.currentTimeMillis());
            writer.close();
            XposedBridge.log(TAG + ": Status written: " + status);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error writing status: " + e.getMessage());
        }
    }

    // ============================================================
    // DTMF PLAYBACK
    // ============================================================

    /**
     * Envia uma sequência de dígitos DTMF
     */
    private static void sendDtmfSequence(String digits, int delayMs) {
        if (sActiveCall == null) {
            XposedBridge.log(TAG + ": No active call to send DTMF!");
            writeStatus("ERROR|Nenhuma chamada ativa");
            return;
        }

        if (sIsPlaying) {
            XposedBridge.log(TAG + ": Already playing, stopping first...");
            stopDtmfSequence();
        }

        // Extrair apenas dígitos DTMF válidos
        String cleanDigits = digits.replaceAll("[^0-9*#]", "");
        if (cleanDigits.isEmpty()) {
            XposedBridge.log(TAG + ": No valid DTMF digits in: " + digits);
            writeStatus("ERROR|Nenhum dígito válido");
            return;
        }

        XposedBridge.log(TAG + ": === STARTING DTMF SEQUENCE ===");
        XposedBridge.log(TAG + ": Digits: " + cleanDigits + " | Delay: " + delayMs + "ms");

        sIsPlaying = true;
        final char[] digitArray = cleanDigits.toCharArray();

        writeStatus("PLAYING|0|" + digitArray.length);

        // Enviar cada dígito com delay
        for (int i = 0; i < digitArray.length; i++) {
            final int index = i;
            final char digit = digitArray[i];

            sHandler.postDelayed(() -> {
                if (!sIsPlaying || sActiveCall == null) {
                    XposedBridge.log(TAG + ": Sequence interrupted at index " + index);
                    return;
                }

                try {
                    XposedBridge.log(TAG + ": DTMF tone: " + digit +
                            " (" + (index + 1) + "/" + digitArray.length + ")");

                    // Tocar o tom DTMF
                    sActiveCall.playDtmfTone(digit);

                    // Parar o tom após 150ms
                    sHandler.postDelayed(() -> {
                        try {
                            if (sActiveCall != null) {
                                sActiveCall.stopDtmfTone();
                            }
                        } catch (Exception e) {
                            XposedBridge.log(TAG + ": Error stopping tone: " + e.getMessage());
                        }

                        // Escrever progresso
                        writeStatus("PROGRESS|" + (index + 1) + "|" + digitArray.length + "|" + digit);

                        // Último dígito?
                        if (index == digitArray.length - 1) {
                            sIsPlaying = false;
                            writeStatus("COMPLETE");
                            XposedBridge.log(TAG + ": === DTMF SEQUENCE COMPLETE ===");
                        }
                    }, 150);

                } catch (Exception e) {
                    XposedBridge.log(TAG + ": Error playing DTMF: " + e.getMessage());
                    writeStatus("ERROR|" + e.getMessage());
                    sIsPlaying = false;
                }
            }, (long) i * delayMs);
        }
    }

    /**
     * Para a sequência DTMF
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
        writeStatus("STOPPED");
        XposedBridge.log(TAG + ": DTMF sequence stopped");

        // Reiniciar polling se necessário
        startPolling();
    }
}
