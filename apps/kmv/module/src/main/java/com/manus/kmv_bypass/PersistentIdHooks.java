package com.manus.kmv_bypass;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
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
 * v1.5.4 — STRING + BASE64 NUCLEAR HOOK + DNS SINKHOLE
 *
 * Diagnóstico final (tmnc.har):
 *   - O UUID 870949b0-2a4b-4a70-9f8d-9c80a1bb433a... é IMORTAL.
 *   - Hooks em OkHttp (Headers, Builder, Chain) NÃO pegaram ele.
 *   - Explicação: O app pode estar usando OkHttp ofuscado ou Native Code.
 *
 * Estratégia v1.5.4:
 *   1. Hook em android.util.Base64: O header x-mobile é Base64. Se o app codificar
 *      algo contendo o UUID antigo, nós interceptamos e trocamos.
 *   2. Hook em java.lang.StringBuilder.toString(): Capturar a montagem da string x-mobile.
 *   3. Hook em okhttp3.Request.header(String): Hook de leitura no objeto final.
 *   4. DNS Sinkhole mantido (PayPal, ViewPkg, AppsFlyer, etc.).
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
        Log.e(TAG, "PersistentIdHooks v1.5.4 starting. TARGETING IMMORTAL UUID.");

        hookDnsSinkhole(lpparam);
        hookBase64(lpparam);                // CAMADA 1 — Interceptar codificação do header
        hookStringBuilder(lpparam);         // CAMADA 2 — Interceptar montagem da string
        hookOkHttpFinalRequest(lpparam);    // CAMADA 3 — Hook de leitura no Request
        
        // Camadas de base mantidas
        hookSharedPreferences(lpparam);
        hookGsfId(lpparam);
        hookAndroidId(lpparam);
        hookUptimeSpoof(lpparam);
        hookMagnesCollectAndSubmit(lpparam);
        hookUrlConnectionBlocking(lpparam);
    }

    // ==================== CAMADA 1 — BASE64 HOOK ====================
    private void hookBase64(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Base64.class, "encodeToString", byte[].class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    byte[] input = (byte[]) param.args[0];
                    if (input == null) return;
                    String s = new String(input);
                    if (s.contains(BAD_UUID_PART)) {
                        String newS = s.replace(BAD_UUID_PART, SPOOFED_UUID);
                        param.args[0] = newS.getBytes();
                        Log.e(TAG, "BASE64.encodeToString: IMMORTAL UUID DETECTED AND REPLACED!");
                    }
                }
            });
        } catch (Throwable t) {}
    }

    // ==================== CAMADA 2 — STRINGBUILDER HOOK ====================
    private void hookStringBuilder(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(StringBuilder.class, "toString", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String s = (String) param.getResult();
                    if (s != null && s.contains(BAD_UUID_PART)) {
                        param.setResult(s.replace(BAD_UUID_PART, SPOOFED_UUID));
                        Log.e(TAG, "StringBuilder.toString: IMMORTAL UUID REPLACED!");
                    }
                }
            });
        } catch (Throwable t) {}
    }

    // ==================== CAMADA 3 — OKHTTP FINAL REQUEST ====================
    private void hookOkHttpFinalRequest(LoadPackageParam lpparam) {
        try {
            Class<?> requestClass = XposedHelpers.findClass("okhttp3.Request", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(requestClass, "header", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[0];
                    String value = (String) param.getResult();
                    if ("x-mobile".equalsIgnoreCase(name) && value != null && value.contains(BAD_UUID_PART)) {
                        param.setResult(value.replace(BAD_UUID_PART, SPOOFED_UUID));
                        Log.e(TAG, "Request.header('x-mobile'): IMMORTAL UUID REPLACED!");
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
