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
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * v1.5.6 — CRASH FIX (AllowMe) + HttpURLConnection HOOK
 *
 * Diagnóstico v1.5.5:
 *   - Crash no AllowMe SDK (lateinit property error) pós-CEP.
 *   - UUID x-mobile CONTINUA FIXO no crash.har.
 *
 * Estratégia v1.5.6:
 *   1. Hook em HttpURLConnection: Interceptar headers em chamadas não-OkHttp.
 *   2. Estabilizar IDs: Garantir que Android ID e Serial nunca sejam null ou vazios.
 *   3. Hook em URLConnection.setRequestProperty: Pegar o UUID na raiz do Java.
 *   4. DNS Sinkhole mantido.
 */
public class PersistentIdHooks {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String TAG = MainHook.TAG_LOG;

    private static final String BAD_UUID_PART = "870949b0-2a4b-4a70-9f8d-9c80a1bb433a";

    private static final String SPOOFED_ANDROID_ID = randomHex(16);
    private static final String SPOOFED_SERIAL = "POCO" + randomHex(12).toUpperCase();
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
        Log.e(TAG, "PersistentIdHooks v1.5.6 starting. CRASH FIX + HttpURLConnection.");

        hookDnsSinkhole(lpparam);
        hookHttpURLConnection(lpparam);      // NOVA CAMADA — Java Native Networking
        hookOkHttpStable(lpparam);          // OkHttp
        hookSharedPreferences(lpparam);
        hookGsfId(lpparam);
        hookAndroidId(lpparam);
        hookUptimeSpoof(lpparam);
        hookMagnesCollectAndSubmit(lpparam);
        hookUrlConnectionBlocking(lpparam);
    }

    // ==================== CAMADA 1 — HttpURLConnection HOOK ====================
    private void hookHttpURLConnection(LoadPackageParam lpparam) {
        try {
            // Hook em URLConnection.setRequestProperty(String, String)
            XposedHelpers.findAndHookMethod(URLConnection.class, "setRequestProperty", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    String value = (String) param.args[1];
                    if ("x-mobile".equalsIgnoreCase(key) && value != null && value.contains(BAD_UUID_PART)) {
                        param.args[1] = value.replace(BAD_UUID_PART, SPOOFED_UUID);
                        Log.e(TAG, "URLConnection.setRequestProperty('x-mobile'): UUID REPLACED");
                    }
                }
            });

            // Hook em HttpURLConnection.getRequestProperty(String)
            XposedHelpers.findAndHookMethod(HttpURLConnection.class, "getRequestProperty", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    String value = (String) param.getResult();
                    if ("x-mobile".equalsIgnoreCase(key) && value != null && value.contains(BAD_UUID_PART)) {
                        param.setResult(value.replace(BAD_UUID_PART, SPOOFED_UUID));
                    }
                }
            });
        } catch (Throwable t) { Log.e(TAG, "HttpURLConnection hook failed: " + t.getMessage()); }
    }

    // ==================== CAMADA 2 — OKHTTP STABLE HOOKS ====================
    private void hookOkHttpStable(LoadPackageParam lpparam) {
        try {
            Class<?> headersClass = XposedHelpers.findClass("okhttp3.Headers", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(headersClass, "get", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[0];
                    String value = (String) param.getResult();
                    if ("x-mobile".equalsIgnoreCase(name) && value != null && value.contains(BAD_UUID_PART)) {
                        param.setResult(value.replace(BAD_UUID_PART, SPOOFED_UUID));
                        Log.e(TAG, "OkHttp Headers.get('x-mobile'): UUID REPLACED");
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

    // ==================== IDs & CRASH FIX (AllowMe) ====================
    private void hookAndroidId(LoadPackageParam lpparam) {
        try {
            // Garantir que SERIAL nunca seja null para evitar UninitializedPropertyAccessException
            XposedHelpers.setStaticObjectField(Build.class, "SERIAL", SPOOFED_SERIAL);
            XposedHelpers.findAndHookMethod(Build.class, "getSerial", XC_MethodReplacement.returnConstant(SPOOFED_SERIAL));
        } catch (Throwable t) {}
    }

    private void hookGsfId(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString", ContentResolver.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[1];
                    if ("android_id".equals(name)) {
                        param.setResult(SPOOFED_ANDROID_ID);
                    }
                }
            });
        } catch (Throwable t) {}
    }

    private void hookSharedPreferences(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "getSharedPreferences", String.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[0];
                    if (name != null && (name.contains("magnes") || name.contains("paypal") || name.contains("viewpkg") || name.contains("allowme"))) {
                        Object sp = param.getResult();
                        if (sp != null) ((SharedPreferences) sp).edit().clear().apply();
                    }
                }
            });
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
        String[] cls = {"lib.android.paypal.com.magnessdk.d", "lib.android.paypal.com.magnessdk.MagnesSDK"};
        for (String c : cls) {
            try {
                Class<?> clazz = XposedHelpers.findClass(c, lpparam.classLoader);
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().matches("collect|submit|collectAndSubmit")) {
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
