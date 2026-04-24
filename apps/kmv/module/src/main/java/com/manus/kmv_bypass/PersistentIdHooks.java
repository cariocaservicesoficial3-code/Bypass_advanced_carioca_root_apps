package com.manus.kmv_bypass;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * v1.5.5 — STABLE NETWORK HOOKS + DNS SINKHOLE
 *
 * Diagnóstico v1.5.4:
 *   - O hook em StringBuilder.toString() quebrou o app (instável).
 *   - O app parou de carregar recursos básicos.
 *
 * Estratégia v1.5.5:
 *   1. REMOVIDO: StringBuilder e Base64 hooks (causadores de instabilidade).
 *   2. REFORÇADO: Hook em okhttp3.Headers (métodos de leitura get/values).
 *   3. REFORÇADO: Hook em okhttp3.Request (método header).
 *   4. MANTIDO: DNS Sinkhole (PayPal, ViewPkg, AppsFlyer, etc).
 */
public class PersistentIdHooks {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String TAG = MainHook.TAG_LOG;

    // UUID antigo (o vilão)
    private static final String BAD_UUID_PART = "870949b0-2a4b-4a70-9f8d-9c80a1bb433a";

    private static final String SPOOFED_APP_GUID = UUID.randomUUID().toString();
    private static final String SPOOFED_MAGNES_GUID = UUID.randomUUID().toString();
    private static final String SPOOFED_GSF_ID = randomHex(16);
    private static final String SPOOFED_ANDROID_ID = randomHex(16);
    private static final long SPOOFED_UPTIME_OFFSET;
    private static final String SPOOFED_UUID;
    private static final String SPOOFED_UUID_HASH;

    private static final Set<String> BLOCKED_DOMAINS = new HashSet<>(Arrays.asList(
        "c.paypal.com", "b.stats.paypal.com", "t.paypal.com", "www.paypalobjects.com", "api-m.paypal.com",
        "d.viewpkg.com", "service2.br.incognia.com", "service3.br.incognia.com", "service4.br.incognia.com",
        "idf-api.serasaexperian.com.br", "514012981.collect.igodigital.com"
    ));

    static {
        SPOOFED_UPTIME_OFFSET = (1 + (long)(RNG.nextDouble() * 6)) * 86400000L;
        SPOOFED_UUID = UUID.randomUUID().toString();
        SPOOFED_UUID_HASH = randomHex(40);
    }

    private static String randomHex(int chars) {
        byte[] b = new byte[(chars + 1) / 2];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.substring(0, chars);
    }

    public void install(LoadPackageParam lpparam) {
        Log.e(TAG, "PersistentIdHooks v1.5.5 starting. STABILITY FOCUS.");

        hookDnsSinkhole(lpparam);
        hookOkHttpStable(lpparam);          // CAMADA 1 — OkHttp estável (Headers e Request)
        hookSharedPreferences(lpparam);     // CAMADA 2 — SharedPreferences
        hookGsfId(lpparam);                 // CAMADA 3 — GSF ID
        hookAndroidId(lpparam);             // CAMADA 4 — Android ID
        hookUptimeSpoof(lpparam);           // CAMADA 5 — Device Uptime
        hookMagnesCollectAndSubmit(lpparam); // CAMADA 6 — Magnes SDK
        hookUrlConnectionBlocking(lpparam); // CAMADA 7 — URL.openConnection
    }

    // ==================== CAMADA 1 — OKHTTP STABLE HOOKS ====================
    private void hookOkHttpStable(LoadPackageParam lpparam) {
        // Hook em okhttp3.Headers (get e values)
        try {
            Class<?> headersClass = XposedHelpers.findClass("okhttp3.Headers", lpparam.classLoader);
            
            // Hook no método get(String)
            XposedHelpers.findAndHookMethod(headersClass, "get", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[0];
                    String value = (String) param.getResult();
                    if ("x-mobile".equalsIgnoreCase(name) && value != null && value.contains(BAD_UUID_PART)) {
                        param.setResult(value.replace(BAD_UUID_PART, SPOOFED_UUID));
                        Log.e(TAG, "Headers.get('x-mobile'): UUID REPLACED");
                    }
                }
            });

            // Hook no método values(String)
            XposedHelpers.findAndHookMethod(headersClass, "values", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[0];
                    if ("x-mobile".equalsIgnoreCase(name)) {
                        List<String> values = (List<String>) param.getResult();
                        if (values != null) {
                            List<String> newValues = new ArrayList<>();
                            for (String v : values) {
                                if (v != null && v.contains(BAD_UUID_PART)) {
                                    newValues.add(v.replace(BAD_UUID_PART, SPOOFED_UUID));
                                } else {
                                    newValues.add(v);
                                }
                            }
                            param.setResult(newValues);
                        }
                    }
                }
            });
        } catch (Throwable t) { Log.e(TAG, "OkHttp Headers hook failed: " + t.getMessage()); }

        // Hook em okhttp3.Request.header(String)
        try {
            Class<?> requestClass = XposedHelpers.findClass("okhttp3.Request", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(requestClass, "header", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[0];
                    String value = (String) param.getResult();
                    if ("x-mobile".equalsIgnoreCase(name) && value != null && value.contains(BAD_UUID_PART)) {
                        param.setResult(value.replace(BAD_UUID_PART, SPOOFED_UUID));
                        Log.e(TAG, "Request.header('x-mobile'): UUID REPLACED");
                    }
                }
            });
        } catch (Throwable t) {}
    }

    // ==================== DNS SINKHOLE ====================
    private void hookDnsSinkhole(LoadPackageParam lpparam) {
        try {
            XC_MethodHook dnsHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String host = (String) param.args[0];
                    if (host != null && isBlockedDomain(host)) {
                        Log.e(TAG, "DNS SINKHOLE: " + host);
                        param.setResult(InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1}));
                    }
                }
            };
            XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, dnsHook);
            XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, dnsHook);
        } catch (Throwable t) {}
    }

    private static boolean isBlockedDomain(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        for (String b : BLOCKED_DOMAINS) { if (host.contains(b)) return true; }
        return host.endsWith(".appsflyersdk.com");
    }

    // ==================== SHARED PREFS & IDs ====================
    private void hookSharedPreferences(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "getSharedPreferences", String.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[0];
                    if (name != null && (name.contains("magnes") || name.contains("paypal") || name.contains("viewpkg"))) {
                        Object sp = param.getResult();
                        if (sp != null) ((SharedPreferences) sp).edit().clear().apply();
                    }
                }
            });
        } catch (Throwable t) {}
    }

    private void hookGsfId(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString", ContentResolver.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if ("android_id".equals(param.args[1])) param.setResult(SPOOFED_ANDROID_ID);
                }
            });
        } catch (Throwable t) {}
    }

    private void hookAndroidId(LoadPackageParam lpparam) {
        try {
            XposedHelpers.setStaticObjectField(Build.class, "SERIAL", "POCO" + randomHex(12).toUpperCase());
        } catch (Throwable t) {}
    }

    private void hookUptimeSpoof(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(SystemClock.class, "elapsedRealtime", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult((Long) param.getResult() + SPOOFED_UPTIME_OFFSET);
                }
            });
        } catch (Throwable t) {}
    }

    private void hookMagnesCollectAndSubmit(LoadPackageParam lpparam) {
        String[] cls = {"lib.android.paypal.com.magnessdk.d", "lib.android.paypal.com.magnessdk.MagnesSDK", "lib.android.paypal.com.magnessdk.a", "lib.android.paypal.com.magnessdk.C"};
        for (String c : cls) {
            try {
                Class<?> clazz = XposedHelpers.findClass(c, lpparam.classLoader);
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().matches("collect|submit|f|g|h|collectAndSubmit")) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                    }
                }
            } catch (Throwable t) {}
        }
    }

    private void hookUrlConnectionBlocking(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(java.net.URL.class, "openConnection", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    java.net.URL url = (java.net.URL) param.thisObject;
                    if (isBlockedDomain(url.getHost())) param.setThrowable(new java.io.IOException("Blocked"));
                }
            });
        } catch (Throwable t) {}
    }
}
