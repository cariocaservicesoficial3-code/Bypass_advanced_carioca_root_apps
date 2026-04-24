package com.manus.kmv_bypass;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.net.Uri;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * v1.5.8 — FIX CRÍTICO:
 * 1. Removida limpeza agressiva de SharedPreferences (causava UninitializedPropertyAccessException no AllowMe).
 * 2. Removidos hooks duplicados de Android ID/Serial (já estão no IdentitySpoofHooks).
 * 3. Mantido DNS Sinkhole (getAllByName fix) e UUID Source Hooks.
 */
public class PersistentIdHooks {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String TAG = MainHook.TAG_LOG;

    private static final String BAD_UUID_PART = "870949b0-2a4b-4a70-9f8d-9c80a1bb433a";
    private static final String BAD_UUID_SUFFIX = "40684ca1383bd79201f005ce8b246e755e41b1e6";

    private static final long SPOOFED_UPTIME_OFFSET;
    private static final String SPOOFED_PREF_UUID;
    private static final String SPOOFED_UUID_HASH;
    private static final String SPOOFED_FULL_UUID;

    private static final Set<String> BLOCKED_DOMAINS = new HashSet<>(Arrays.asList(
        "c.paypal.com", "b.stats.paypal.com", "t.paypal.com", "www.paypalobjects.com", "api-m.paypal.com",
        "d.viewpkg.com", "service2.br.incognia.com", "service3.br.incognia.com", "service4.br.incognia.com",
        "idf-api.serasaexperian.com.br", "514012981.collect.igodigital.com"
    ));

    static {
        SPOOFED_UPTIME_OFFSET = (1 + (long)(RNG.nextDouble() * 6)) * 86400000L;
        SPOOFED_PREF_UUID = UUID.randomUUID().toString();
        SPOOFED_UUID_HASH = randomHex(40);
        SPOOFED_FULL_UUID = SPOOFED_PREF_UUID + SPOOFED_UUID_HASH;
    }

    private static String randomHex(int chars) {
        byte[] b = new byte[(chars + 1) / 2];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.substring(0, chars);
    }

    public void install(LoadPackageParam lpparam) {
        Log.e(TAG, "PersistentIdHooks v1.5.8 starting.");
        Log.e(TAG, "SPOOFED_PREF_UUID: " + SPOOFED_PREF_UUID);
        
        hookDnsSinkhole(lpparam);
        hookSharedPreferencesUuid(lpparam);
        hookUuidSourceClass(lpparam);
        hookDeviceGetUuid(lpparam);
        hookOkHttpInterceptors(lpparam);
        hookHttpUrlConnection(lpparam);
        hookOkHttpStable(lpparam);
        
        hookUptimeSpoof(lpparam);
        hookMagnesCollectAndSubmit(lpparam);
        hookUrlConnectionBlocking(lpparam);
    }

    private void hookDnsSinkhole(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String host = (String) param.args[0];
                    if (host != null && isBlockedDomain(host)) {
                        Log.e(TAG, "DNS SINKHOLE (getByName): " + host);
                        param.setResult(InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1}));
                    }
                }
            });

            XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String host = (String) param.args[0];
                    if (host != null && isBlockedDomain(host)) {
                        Log.e(TAG, "DNS SINKHOLE (getAllByName): " + host);
                        param.setResult(new InetAddress[]{InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1})});
                    }
                }
            });
        } catch (Throwable t) {}
    }

    private static boolean isBlockedDomain(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        for (String b : BLOCKED_DOMAINS) { if (host.contains(b)) return true; }
        return host.endsWith(".appsflyersdk.com");
    }

    private void hookSharedPreferencesUuid(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", lpparam.classLoader,
                "getString", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if ("PREF_UNIQUE_ID".equals(param.args[0])) {
                        param.setResult(SPOOFED_PREF_UUID);
                    }
                }
            });
        } catch (Throwable t) {}
    }

    private void hookUuidSourceClass(LoadPackageParam lpparam) {
        try {
            Class<?> utilClass = XposedHelpers.findClass("com.gigigo.ipirangaconectcar.support.util.C0415r", lpparam.classLoader);
            for (Method m : utilClass.getDeclaredMethods()) {
                if (m.getName().equals("m1865D") || m.getName().equals("D")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return SPOOFED_FULL_UUID;
                        }
                    });
                }
            }
        } catch (Throwable t) {}
    }

    private void hookDeviceGetUuid(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.gigigo.ipirangaconectcar.support.util.Device", lpparam.classLoader,
                "getUuid", XC_MethodReplacement.returnConstant(SPOOFED_FULL_UUID));
        } catch (Throwable t) {}
    }

    private void hookOkHttpInterceptors(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("okhttp3.Request$Builder", lpparam.classLoader, "addHeader", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if ("x-mobile".equalsIgnoreCase((String)param.args[0])) {
                        param.args[1] = ((String)param.args[1]).replace(BAD_UUID_PART, SPOOFED_PREF_UUID).replace(BAD_UUID_SUFFIX, SPOOFED_UUID_HASH);
                    }
                }
            });
            XposedHelpers.findAndHookMethod("okhttp3.Request$Builder", lpparam.classLoader, "header", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if ("x-mobile".equalsIgnoreCase((String)param.args[0])) {
                        param.args[1] = ((String)param.args[1]).replace(BAD_UUID_PART, SPOOFED_PREF_UUID).replace(BAD_UUID_SUFFIX, SPOOFED_UUID_HASH);
                    }
                }
            });
        } catch (Throwable t) {}
    }

    private void hookHttpUrlConnection(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.android.okhttp.internal.huc.HttpURLConnectionImpl", lpparam.classLoader, "setRequestProperty", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if ("x-mobile".equalsIgnoreCase((String)param.args[0])) {
                        param.args[1] = ((String)param.args[1]).replace(BAD_UUID_PART, SPOOFED_PREF_UUID).replace(BAD_UUID_SUFFIX, SPOOFED_UUID_HASH);
                    }
                }
            });
        } catch (Throwable t) {}
    }

    private void hookOkHttpStable(LoadPackageParam lpparam) {
        try {
            Class<?> headersClass = XposedHelpers.findClass("okhttp3.Headers", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(headersClass, "get", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String value = (String) param.getResult();
                    if ("x-mobile".equalsIgnoreCase((String)param.args[0]) && value != null && value.contains(BAD_UUID_PART)) {
                        param.setResult(value.replace(BAD_UUID_PART, SPOOFED_PREF_UUID).replace(BAD_UUID_SUFFIX, SPOOFED_UUID_HASH));
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
