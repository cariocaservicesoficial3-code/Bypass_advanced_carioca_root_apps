package com.manus.kmv_bypass;

import android.os.Build;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Method;

/**
 * v1.1 — Hooks para as camadas descobertas na análise do HAR (erro #1004):
 * - AllowMe SDK (Serasa IDF) — responsável pelo "deviceFingerprintSessionId" e payload 'data' criptografado
 * - Incognia SDK — anti-fraude adicional
 * - Spoof complementar de Build para tapar telemetria final
 */
public class FingerprintHooks {

    public void install(LoadPackageParam lpparam) {
        hookAllowMe(lpparam);
        hookIncognia(lpparam);
        hookBuildSpoofing();
        hookFingerprintDataZaig(lpparam);
    }

    /**
     * AllowMe SDK (br.com.allowme.android.allowmesdk.AllowMe):
     * O SDK coleta fingerprint e envia ao Serasa IDF, que retorna um "collect" token
     * que é mandado ao backend KMV. Se o Serasa flagar o device, o backend bloqueia.
     *
     * Estratégia: interceptar os callbacks de collect/start/setup e forçar sempre sucesso,
     * retornando um token mockado válido (será enviado ao backend que não tem como validar
     * sem consultar o Serasa, que pode ter cache).
     */
    private void hookAllowMe(LoadPackageParam lpparam) {
        final String allowMeCls = "br.com.allowme.android.allowmesdk.AllowMe";
        final String collectCallback = "br.com.allowme.android.allowmesdk.CollectCallback";
        final String startCallback = "br.com.allowme.android.allowmesdk.StartCallback";
        final String setupCallback = "br.com.allowme.android.allowmesdk.SetupCallback";

        // 1. AllowMe.collect(CollectCallback) -> chamar callback.success() com token fake
        try {
            Class<?> callbackIface = XposedHelpers.findClass(collectCallback, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(allowMeCls, lpparam.classLoader, "collect", callbackIface, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    Object callback = param.args[0];
                    if (callback != null) {
                        try {
                            // Chamar success(String collect) no callback
                            Method m = findMethodByName(callback.getClass(), "onSuccess", "success");
                            if (m != null && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == String.class) {
                                m.setAccessible(true);
                                m.invoke(callback, "clean-device-fingerprint-token");
                                MainHook.log("AllowMe.collect callback forced success");
                            }
                        } catch (Throwable inner) {
                            MainHook.log("AllowMe.collect callback invoke failed: " + inner);
                        }
                    }
                    return null;
                }
            });
            MainHook.log("AllowMe.collect(CollectCallback) hooked");
        } catch (Throwable t) {
            MainHook.log("Could not hook AllowMe.collect(callback): " + t.getMessage());
        }

        // 2. AllowMe.collect(Function1, Function1) — variante Kotlin lambdas
        try {
            Class<?> function1 = XposedHelpers.findClass("kotlin.jvm.functions.Function1", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(allowMeCls, lpparam.classLoader, "collect", function1, function1, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    Object onSuccess = param.args[0];
                    if (onSuccess != null) {
                        try {
                            Method invoke = onSuccess.getClass().getMethod("invoke", Object.class);
                            invoke.setAccessible(true);
                            invoke.invoke(onSuccess, "clean-device-fingerprint-token");
                            MainHook.log("AllowMe.collect(Function1,Function1) onSuccess invoked");
                        } catch (Throwable inner) {
                            MainHook.log("AllowMe collect lambda invoke failed: " + inner);
                        }
                    }
                    return null;
                }
            });
            MainHook.log("AllowMe.collect(Function1,Function1) hooked");
        } catch (Throwable t) {
            MainHook.log("Could not hook AllowMe.collect(Function1,Function1): " + t.getMessage());
        }

        // 3. AllowMeCollectResponse$Success.getCollect() — retornar sempre um token limpo
        try {
            XposedHelpers.findAndHookMethod(
                "br.com.allowme.android.allowmesdk.AllowMeCollectResponse$Success",
                lpparam.classLoader,
                "getCollect",
                XC_MethodReplacement.returnConstant("clean-device-fingerprint-token")
            );
            MainHook.log("AllowMeCollectResponse$Success.getCollect() hooked");
        } catch (Throwable t) {
            MainHook.log("Could not hook AllowMeCollectResponse$Success: " + t.getMessage());
        }

        // 4. AllowMe.start(...) — forçar sucesso
        try {
            Class<?> cb = XposedHelpers.findClass(startCallback, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(allowMeCls, lpparam.classLoader, "start", cb, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    Object callback = param.args[0];
                    if (callback != null) {
                        try {
                            Method m = findMethodByName(callback.getClass(), "onSuccess", "success");
                            if (m != null && m.getParameterTypes().length == 0) {
                                m.setAccessible(true);
                                m.invoke(callback);
                            }
                        } catch (Throwable ignored) {}
                    }
                    return null;
                }
            });
        } catch (Throwable t) {
            MainHook.log("Could not hook AllowMe.start: " + t.getMessage());
        }

        // 5. AllowMe.setup(...) — forçar sucesso
        try {
            Class<?> cb = XposedHelpers.findClass(setupCallback, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(allowMeCls, lpparam.classLoader, "setup", cb, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    Object callback = param.args[0];
                    if (callback != null) {
                        try {
                            Method m = findMethodByName(callback.getClass(), "onSuccess", "success");
                            if (m != null && m.getParameterTypes().length == 0) {
                                m.setAccessible(true);
                                m.invoke(callback);
                            }
                        } catch (Throwable ignored) {}
                    }
                    return null;
                }
            });
        } catch (Throwable t) {
            MainHook.log("Could not hook AllowMe.setup: " + t.getMessage());
        }

        MainHook.log("AllowMe hooks installed");
    }

    /**
     * Incognia — motor de anti-fraude comportamental/fingerprint.
     * Estratégia: bloquear todos os `trackEvent` e neutralizar o `init`.
     */
    private void hookIncognia(LoadPackageParam lpparam) {
        final String incogniaCls = "com.incognia.Incognia";

        // trackEvent(String)
        try {
            XposedHelpers.findAndHookMethod(incogniaCls, lpparam.classLoader, "trackEvent", String.class, XC_MethodReplacement.returnConstant(null));
            MainHook.log("Incognia.trackEvent(String) neutralized");
        } catch (Throwable t) { MainHook.log("Incognia.trackEvent(String) hook failed: " + t.getMessage()); }

        // trackEvent(String, EventProperties)
        try {
            Class<?> eventProps = XposedHelpers.findClass("com.incognia.EventProperties", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(incogniaCls, lpparam.classLoader, "trackEvent", String.class, eventProps, XC_MethodReplacement.returnConstant(null));
            MainHook.log("Incognia.trackEvent(String, EventProperties) neutralized");
        } catch (Throwable t) { MainHook.log("Incognia.trackEvent(EP) hook failed: " + t.getMessage()); }

        // trackLocalizedEvent
        try {
            XposedHelpers.findAndHookMethod(incogniaCls, lpparam.classLoader, "trackLocalizedEvent", String.class, XC_MethodReplacement.returnConstant(null));
        } catch (Throwable ignored) {}

        // setAccountId — opcional, mas reduz telemetria
        try {
            XposedHelpers.findAndHookMethod(incogniaCls, lpparam.classLoader, "setAccountId", String.class, XC_MethodReplacement.returnConstant(null));
        } catch (Throwable ignored) {}

        // getInstallationId — retornar UUID fake estável
        try {
            XposedHelpers.findAndHookMethod(incogniaCls, lpparam.classLoader, "getInstallationId",
                XC_MethodReplacement.returnConstant("00000000-0000-0000-0000-000000000000"));
            MainHook.log("Incognia.getInstallationId spoofed");
        } catch (Throwable ignored) {}

        MainHook.log("Incognia hooks installed");
    }

    /**
     * Spoof complementar de Build para fingerprint do device parecer 100% stock.
     * CashShield e AllowMe lêem esses campos via reflection para computar fingerprint.
     */
    private void hookBuildSpoofing() {
        try {
            // Já spoofamos TAGS no LowLevelHooks. Aqui cobrimos o resto:
            XposedHelpers.setStaticObjectField(Build.class, "FINGERPRINT",
                "Xiaomi/alioth/alioth:11/RKQ1.200826.002/V12.5.7.0.RKJMIXM:user/release-keys");
            XposedHelpers.setStaticObjectField(Build.class, "BOOTLOADER", "unknown");
            XposedHelpers.setStaticObjectField(Build.class, "TYPE", "user");
            XposedHelpers.setStaticObjectField(Build.class, "DISPLAY", "RKQ1.200826.002");
            XposedHelpers.setStaticObjectField(Build.class, "HOST", "c3-sc-036-019.bj");
            XposedHelpers.setStaticObjectField(Build.class, "USER", "builder");
            MainHook.log("Build static fields spoofed (FINGERPRINT, BOOTLOADER, TYPE, DISPLAY, HOST, USER)");
        } catch (Throwable t) {
            MainHook.log("Could not spoof Build fields: " + t.getMessage());
        }
    }

    /**
     * FingerprintDataZaig — classe própria do KMV que armazena o sessionId do Zaig.
     * Forçamos sessionId vazio para evitar envio.
     */
    private void hookFingerprintDataZaig(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.gigigo.ipirangaconectcar.security.zaig.FingerprintDataZaig",
                lpparam.classLoader,
                "getSessionId",
                XC_MethodReplacement.returnConstant("")
            );
            MainHook.log("FingerprintDataZaig.getSessionId -> empty");
        } catch (Throwable t) {
            MainHook.log("Could not hook FingerprintDataZaig: " + t.getMessage());
        }
    }

    private static Method findMethodByName(Class<?> c, String... names) {
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                for (String name : names) {
                    if (m.getName().equals(name)) return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
