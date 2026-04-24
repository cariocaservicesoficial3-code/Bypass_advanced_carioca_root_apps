package com.manus.kmv_bypass;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Method;
import java.security.SecureRandom;

/**
 * v1.4 — IDENTITY SPOOF HOOKS (anti-blacklist)
 *
 * Diagnóstico do 333.har:
 *   - Mesmo com hooks v1.3, o PayPal Magnes ainda envia ANDROID_ID PERSISTENTE no payload.
 *   - O backend Zaig provavelmente já blacklistou o combo (CPF, ANDROID_ID, app_first_install_time)
 *     após múltiplas tentativas de cadastro.
 *   - Solução: ROTACIONAR todos os identificadores persistentes a cada execução.
 *
 * Spoofs aplicados:
 *   1. Settings.Secure.ANDROID_ID  → 16 chars hex aleatórios
 *   2. Build.SERIAL / getSerial()  → serial Xiaomi-like aleatório
 *   3. Build.FINGERPRINT/HOST/DISPLAY/BOOTLOADER → strings stock POCO/Xiaomi
 *   4. PackageInfo.firstInstallTime → timestamp aleatório dos últimos 30 dias
 *   5. PackageInfo.lastUpdateTime  → mesma lógica (random)
 *
 * IMPORTANTE: cada install/restart do app gera um IDENTIDADE NOVA → o backend
 * não consegue correlacionar com tentativas anteriores.
 */
public class IdentitySpoofHooks {

    private static final SecureRandom RNG = new SecureRandom();

    // Gerados UMA VEZ por load do módulo (consistentes para a sessão)
    private static String SPOOFED_ANDROID_ID;
    private static String SPOOFED_SERIAL;
    private static long SPOOFED_INSTALL_TIME;
    private static long SPOOFED_UPDATE_TIME;

    static {
        regenerate();
    }

    public static void regenerate() {
        SPOOFED_ANDROID_ID = randomHex(16);
        SPOOFED_SERIAL = "POCO" + randomHex(12).toUpperCase();
        // install time: entre 30 e 365 dias atrás (device "antigo")
        long now = System.currentTimeMillis();
        long minDays = 30, maxDays = 365;
        long daysAgo = minDays + (long)(RNG.nextDouble() * (maxDays - minDays));
        SPOOFED_INSTALL_TIME = now - (daysAgo * 86400000L);
        // last update: depois do install, antes de agora
        SPOOFED_UPDATE_TIME = SPOOFED_INSTALL_TIME + (long)(RNG.nextDouble() * (now - SPOOFED_INSTALL_TIME));
    }

    private static String randomHex(int chars) {
        byte[] b = new byte[(chars + 1) / 2];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.substring(0, chars);
    }

    public void install(LoadPackageParam lpparam) {
        Log.e(MainHook.TAG_LOG, "IdentitySpoof v1.4 starting. NEW IDENTITY:");
        Log.e(MainHook.TAG_LOG, "  ANDROID_ID = " + SPOOFED_ANDROID_ID);
        Log.e(MainHook.TAG_LOG, "  SERIAL     = " + SPOOFED_SERIAL);
        Log.e(MainHook.TAG_LOG, "  INSTALL_T  = " + SPOOFED_INSTALL_TIME);

        spoofAndroidId(lpparam);
        spoofBuildSerial(lpparam);
        spoofBuildIdentifiers(lpparam);
        spoofInstallTimes(lpparam);
        spoofMagnesAggressive(lpparam);
    }

    /** Settings.Secure.getString(resolver, "android_id") → hex aleatório */
    private void spoofAndroidId(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Settings.Secure.class,
                "getString",
                "android.content.ContentResolver", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 1 && Settings.Secure.ANDROID_ID.equals(param.args[1])) {
                            param.setResult(SPOOFED_ANDROID_ID);
                        }
                    }
                }
            );
            Log.e(MainHook.TAG_LOG, "Settings.Secure.getString(ANDROID_ID) HOOKED");
        } catch (Throwable t) {
            Log.e(MainHook.TAG_LOG, "Settings.Secure hook failed: " + t.getMessage());
        }

        // Fallback: Settings.System.getString
        try {
            XposedHelpers.findAndHookMethod(
                Settings.System.class,
                "getString",
                "android.content.ContentResolver", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 1 && "android_id".equals(param.args[1])) {
                            param.setResult(SPOOFED_ANDROID_ID);
                        }
                    }
                }
            );
        } catch (Throwable ignored) {}
    }

    /** Build.SERIAL field + Build.getSerial() method */
    private void spoofBuildSerial(LoadPackageParam lpparam) {
        try {
            XposedHelpers.setStaticObjectField(Build.class, "SERIAL", SPOOFED_SERIAL);
            Log.e(MainHook.TAG_LOG, "Build.SERIAL set to " + SPOOFED_SERIAL);
        } catch (Throwable t) {
            Log.e(MainHook.TAG_LOG, "Build.SERIAL set failed: " + t.getMessage());
        }
        try {
            XposedHelpers.findAndHookMethod(
                Build.class,
                "getSerial",
                XC_MethodReplacement.returnConstant(SPOOFED_SERIAL)
            );
            Log.e(MainHook.TAG_LOG, "Build.getSerial() HOOKED");
        } catch (Throwable ignored) {}
    }

    /** Build.FINGERPRINT, HOST, DISPLAY, BOOTLOADER → stock POCO/Xiaomi */
    private void spoofBuildIdentifiers(LoadPackageParam lpparam) {
        try {
            // Forçamos fingerprint stock POCO/Xiaomi M2012K11AG (POCO F3)
            // que combina com MARCA=POCO, MODELO=M2012K11AG já enviado
            XposedHelpers.setStaticObjectField(Build.class, "FINGERPRINT",
                "POCO/alioth_eea/alioth:13/RKQ1.211001.001/V14.0.5.0.TKHEUXM:user/release-keys");
            XposedHelpers.setStaticObjectField(Build.class, "HOST", "pangu-build-component-system");
            XposedHelpers.setStaticObjectField(Build.class, "DISPLAY", "RKQ1.211001.001");
            XposedHelpers.setStaticObjectField(Build.class, "BOOTLOADER", "unknown");
            XposedHelpers.setStaticObjectField(Build.class, "TAGS", "release-keys");
            XposedHelpers.setStaticObjectField(Build.class, "TYPE", "user");
            Log.e(MainHook.TAG_LOG, "Build.* identifiers spoofed (POCO stock release-keys)");
        } catch (Throwable t) {
            Log.e(MainHook.TAG_LOG, "Build identifiers spoof failed: " + t.getMessage());
        }
    }

    /** PackageInfo.firstInstallTime / lastUpdateTime — varia por sessão */
    private void spoofInstallTimes(LoadPackageParam lpparam) {
        try {
            XposedBridge.hookAllMethods(
                Class.forName("android.content.pm.PackageManager"),
                "getPackageInfo",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        if (r instanceof PackageInfo) {
                            PackageInfo pi = (PackageInfo) r;
                            if ("com.gigigo.ipirangaconectcar".equals(pi.packageName)) {
                                pi.firstInstallTime = SPOOFED_INSTALL_TIME;
                                pi.lastUpdateTime = SPOOFED_UPDATE_TIME;
                            }
                        }
                    }
                }
            );
            Log.e(MainHook.TAG_LOG, "PackageInfo.firstInstallTime/lastUpdateTime spoofed");
        } catch (Throwable t) {
            Log.e(MainHook.TAG_LOG, "PackageInfo install_time hook failed: " + t.getMessage());
        }
    }

    /**
     * MAGNES AGRESSIVO: hookar TODOS os métodos privados da classe C que retornam JSONObject,
     * forçando-os a retornar JSONObject vazio. Isso garante que a coleta seja neutralizada
     * mesmo se o método x() não for o ponto de entrada esperado.
     */
    private void spoofMagnesAggressive(LoadPackageParam lpparam) {
        try {
            Class<?> clsC = XposedHelpers.findClass("lib.android.paypal.com.magnessdk.C", lpparam.classLoader);
            int hookedJSON = 0, hookedX = 0;

            // Hookar TODOS os métodos que retornam JSONObject (vazio)
            for (Method m : clsC.getDeclaredMethods()) {
                Class<?> ret = m.getReturnType();
                String mname = m.getName();
                if (ret.getName().equals("org.json.JSONObject")) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) {
                                return new org.json.JSONObject();
                            }
                        });
                        hookedJSON++;
                        Log.e(MainHook.TAG_LOG, "  Magnes C." + mname + "(...) -> {}");
                    } catch (Throwable t) {
                        Log.e(MainHook.TAG_LOG, "  Magnes C." + mname + " hook FAIL: " + t.getMessage());
                    }
                }
                // Hookar x() - método principal de gerar payload
                if (mname.equals("x")) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) {
                                try {
                                    org.json.JSONObject j = new org.json.JSONObject();
                                    j.put("app_id", "com.gigigo.ipirangaconectcar");
                                    j.put("os_type", "Android");
                                    j.put("os_version", "13");
                                    j.put("is_rooted", false);
                                    j.put("is_emulator", false);
                                    j.put("android_id", SPOOFED_ANDROID_ID);
                                    j.put("app_first_install_time", SPOOFED_INSTALL_TIME);
                                    return j;
                                } catch (Throwable t) {
                                    return new org.json.JSONObject();
                                }
                            }
                        });
                        hookedX++;
                        Log.e(MainHook.TAG_LOG, "  Magnes C.x(" + m.getParameterTypes().length + " args) -> minimal JSON");
                    } catch (Throwable t) {
                        Log.e(MainHook.TAG_LOG, "  Magnes C.x hook FAIL: " + t.getMessage());
                    }
                }
            }
            Log.e(MainHook.TAG_LOG, "Magnes aggressive: " + hookedJSON + " JSON methods + " + hookedX + " x() variants");
        } catch (Throwable t) {
            Log.e(MainHook.TAG_LOG, "Magnes aggressive setup failed: " + t.getMessage());
        }

        // Hookar também a classe d (MagnesSDK) f() = collectAndSubmit para retornar c vazio
        // Como não temos a referência exata, deixamos para iteração futura.
    }
}
