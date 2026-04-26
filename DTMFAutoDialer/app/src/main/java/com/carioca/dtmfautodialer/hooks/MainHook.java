package com.carioca.dtmfautodialer.hooks;

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
 * MainHook - Hook principal do módulo LSPosed DTMF Auto Dialer v1.3
 *
 * ARQUITETURA DE COMUNICAÇÃO:
 * Usa /data/local/tmp/dtmf_autodialer/ como diretório compartilhado.
 * Este caminho é acessível por qualquer processo em dispositivos com root,
 * sem precisar de permissões de storage do Android 11+.
 *
 * - Widget escreve: command.txt (formato: SEND|dígitos|delay ou STOP)
 * - Hook monitora: command.txt via FileObserver + polling fallback
 * - Hook escreve: status.txt (PROGRESS, COMPLETE, ERROR, etc.)
 * - Widget lê: status.txt via polling
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "DTMFAutoDialer";
    private static final String SELF_PACKAGE = "com.carioca.dtmfautodialer";

    // Diretório de comunicação - /data/local/tmp é world-readable/writable com root
    public static final String COMMAND_DIR = "/data/local/tmp/dtmf_autodialer";
    public static final String COMMAND_FILE = COMMAND_DIR + "/command.txt";
    public static final String STATUS_FILE = COMMAND_DIR + "/status.txt";

    // Referência estática para a chamada ativa
    private static Call sActiveCall = null;
    private static boolean sIsPlaying = false;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    // FileObserver para monitorar comandos
    private static FileObserver sFileObserver = null;
    private static boolean sObserverStarted = false;
    private static boolean sPollingStarted = false;

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

        // Iniciar monitoramento
        startFileObserver();
        startPolling();

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

                            // Garantir monitoramento ativo
                            ensureCommandDir();
                            startFileObserver();

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

    private static void ensureCommandDir() {
        try {
            File dir = new File(COMMAND_DIR);
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                XposedBridge.log(TAG + ": mkdir " + COMMAND_DIR + " = " + created);
                if (created) {
                    // Tornar acessível por todos
                    Runtime.getRuntime().exec("chmod 777 " + COMMAND_DIR);
                }
            }
            File cmd = new File(COMMAND_FILE);
            if (!cmd.exists()) {
                cmd.createNewFile();
                Runtime.getRuntime().exec("chmod 666 " + COMMAND_FILE);
            }
            File status = new File(STATUS_FILE);
            if (!status.exists()) {
                status.createNewFile();
                Runtime.getRuntime().exec("chmod 666 " + STATUS_FILE);
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error creating command dir: " + e.getMessage());
        }
    }

    private static void startFileObserver() {
        if (sObserverStarted) return;

        try {
            ensureCommandDir();

            sFileObserver = new FileObserver(COMMAND_DIR, FileObserver.CLOSE_WRITE | FileObserver.MODIFY) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null && path.equals("command.txt")) {
                        XposedBridge.log(TAG + ": FileObserver: command.txt changed!");
                        sHandler.post(() -> processCommand());
                    }
                }
            };
            sFileObserver.startWatching();
            sObserverStarted = true;
            XposedBridge.log(TAG + ": FileObserver started on " + COMMAND_DIR);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": FileObserver error: " + e.getMessage());
        }
    }

    private static long sLastCommandModified = 0;

    private static void startPolling() {
        if (sPollingStarted) return;
        sPollingStarted = true;

        XposedBridge.log(TAG + ": Starting command polling...");

        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    File cmdFile = new File(COMMAND_FILE);
                    if (cmdFile.exists() && cmdFile.length() > 0 && cmdFile.lastModified() > sLastCommandModified) {
                        sLastCommandModified = cmdFile.lastModified();
                        XposedBridge.log(TAG + ": Polling: command.txt changed!");
                        processCommand();
                    }
                } catch (Exception e) {
                    // Ignorar
                }
                // Polling rápido durante chamada, lento fora
                int interval = (sActiveCall != null) ? 300 : 2000;
                sHandler.postDelayed(this, interval);
            }
        }, 300);
    }

    private static void processCommand() {
        try {
            File cmdFile = new File(COMMAND_FILE);
            if (!cmdFile.exists() || cmdFile.length() == 0) return;

            BufferedReader reader = new BufferedReader(new FileReader(cmdFile));
            String line = reader.readLine();
            reader.close();

            if (line == null || line.trim().isEmpty()) return;

            XposedBridge.log(TAG + ": Processing command: " + line);

            // Limpar imediatamente para não reprocessar
            clearCommandFile();

            String[] parts = line.split("\\|");
            String action = parts[0].trim();

            if ("SEND".equals(action) && parts.length >= 3) {
                String digits = parts[1].trim();
                int delayMs = Integer.parseInt(parts[2].trim());
                sendDtmfSequence(digits, delayMs);
            } else if ("STOP".equals(action)) {
                stopDtmfSequence();
            }

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error processing command: " + e.getMessage());
        }
    }

    private static void clearCommandFile() {
        try {
            FileWriter writer = new FileWriter(COMMAND_FILE, false);
            writer.write("");
            writer.close();
        } catch (Exception e) {
            // Ignorar
        }
    }

    private static void writeStatus(String status) {
        try {
            ensureCommandDir();
            FileWriter writer = new FileWriter(STATUS_FILE, false);
            writer.write(status + "\n" + System.currentTimeMillis());
            writer.flush();
            writer.close();
            XposedBridge.log(TAG + ": Status: " + status);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error writing status: " + e.getMessage());
        }
    }

    // ============================================================
    // DTMF PLAYBACK
    // ============================================================

    private static void sendDtmfSequence(String digits, int delayMs) {
        if (sActiveCall == null) {
            XposedBridge.log(TAG + ": ERROR - No active call!");
            writeStatus("ERROR|Nenhuma chamada ativa");
            return;
        }

        if (sIsPlaying) {
            stopDtmfSequence();
            // Pequeno delay antes de iniciar nova sequência
            sHandler.postDelayed(() -> doSendDtmf(digits, delayMs), 200);
        } else {
            doSendDtmf(digits, delayMs);
        }
    }

    private static void doSendDtmf(String digits, int delayMs) {
        String cleanDigits = digits.replaceAll("[^0-9*#]", "");
        if (cleanDigits.isEmpty()) {
            writeStatus("ERROR|Nenhum dígito válido");
            return;
        }

        XposedBridge.log(TAG + ": === STARTING DTMF: " + cleanDigits + " delay=" + delayMs + "ms ===");

        sIsPlaying = true;
        final char[] digitArray = cleanDigits.toCharArray();

        writeStatus("PLAYING|0|" + digitArray.length);

        for (int i = 0; i < digitArray.length; i++) {
            final int index = i;
            final char digit = digitArray[i];

            sHandler.postDelayed(() -> {
                if (!sIsPlaying || sActiveCall == null) return;

                try {
                    XposedBridge.log(TAG + ": DTMF [" + digit + "] " + (index + 1) + "/" + digitArray.length);

                    sActiveCall.playDtmfTone(digit);

                    sHandler.postDelayed(() -> {
                        try {
                            if (sActiveCall != null) sActiveCall.stopDtmfTone();
                        } catch (Exception e) { }

                        writeStatus("PROGRESS|" + (index + 1) + "|" + digitArray.length + "|" + digit);

                        if (index == digitArray.length - 1) {
                            sIsPlaying = false;
                            writeStatus("COMPLETE");
                            XposedBridge.log(TAG + ": === DTMF COMPLETE ===");
                        }
                    }, 150);

                } catch (Exception e) {
                    XposedBridge.log(TAG + ": DTMF error: " + e.getMessage());
                    writeStatus("ERROR|" + e.getMessage());
                    sIsPlaying = false;
                }
            }, (long) i * delayMs);
        }
    }

    private static void stopDtmfSequence() {
        sIsPlaying = false;
        sHandler.removeCallbacksAndMessages(null);
        if (sActiveCall != null) {
            try { sActiveCall.stopDtmfTone(); } catch (Exception e) { }
        }
        writeStatus("STOPPED");
        XposedBridge.log(TAG + ": DTMF stopped");

        // Reiniciar polling
        sPollingStarted = false;
        startPolling();
    }
}
