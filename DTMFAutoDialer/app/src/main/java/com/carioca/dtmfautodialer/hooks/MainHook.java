package com.carioca.dtmfautodialer.hooks;

import android.telecom.Call;
import android.telecom.InCallService;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.List;

/**
 * MainHook v1.4 - Minimalista
 * 
 * O objetivo agora é apenas garantir que o módulo seja detectado como ativo
 * e tentar injetar o comportamento de auto-dialer diretamente no InCallService
 * do sistema ou do discador.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "DTMFAutoDialer";
    private static final String SELF_PACKAGE = "com.carioca.dtmfautodialer";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        
        // Hook para o próprio app mostrar "MÓDULO ATIVO"
        if (lpparam.packageName.equals(SELF_PACKAGE)) {
            XposedHelpers.findAndHookMethod(
                    SELF_PACKAGE + ".ui.MainActivity",
                    lpparam.classLoader,
                    "isModuleActive",
                    XC_MethodReplacement.returnConstant(true)
            );
            return;
        }

        // Hook nos apps de telefone para capturar a chamada e enviar DTMF
        // Vamos focar no InCallService que é a base de todos os discadores
        if (lpparam.packageName.equals("com.google.android.dialer") || 
            lpparam.packageName.equals("com.android.incallui") ||
            lpparam.packageName.equals("com.android.server.telecom")) {
            
            XposedBridge.log(TAG + ": Hooking " + lpparam.packageName);

            try {
                // Hook no onCallAdded para gerenciar a chamada automaticamente se houver uma sequência pendente
                XposedHelpers.findAndHookMethod(
                        "android.telecom.InCallService",
                        lpparam.classLoader,
                        "onCallAdded",
                        Call.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                final Call call = (Call) param.args[0];
                                XposedBridge.log(TAG + ": Chamada detectada no hook");
                                
                                // O widget agora vai cuidar da lógica, o hook é apenas um backup
                            }
                        }
                );
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Erro ao instalar hooks de telecom: " + t.getMessage());
            }
        }
    }
    
    // Constantes de caminho mantidas para não quebrar compilação se referenciadas
    public static final String COMMAND_DIR = "/data/local/tmp/dtmf_autodialer";
    public static final String COMMAND_FILE = COMMAND_DIR + "/command.txt";
    public static final String STATUS_FILE = COMMAND_DIR + "/status.txt";
}
