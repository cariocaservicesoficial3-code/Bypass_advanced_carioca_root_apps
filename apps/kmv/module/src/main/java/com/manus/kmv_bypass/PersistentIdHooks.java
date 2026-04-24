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

import java.lang.reflect.Field;
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
 * v1.5.3 — GLOBAL HEADERS HOOK + DNS SINKHOLE + ID ROTATION
 *
 * Diagnóstico pós v1.5.2 (lalala.har):
 *   - Bloqueios DNS (AppsFlyer, Magnes, ViewPkg) estão perfeitos.
 *   - UUID no x-mobile CONTINUA FIXO (870949b0-...)
 *   - Os hooks no Request.Builder e InterceptorChain não pegaram o UUID.
 *
 * Estratégia v1.5.3:
 *   1. Hook GLOBAL em okhttp3.Headers$Builder (set e add) — é aqui que TODOS os headers nascem.
 *   2. Hook em okhttp3.Request (construtor) — para interceptar a criação final do objeto.
 *   3. Hook em okhttp3.internal.http.RealInterceptorChain.proceed() reforçado.
 */
public class PersistentIdHooks {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String TAG = MainHook.TAG_LOG;

    private static final String SPOOFED_APP_GUID = UUID.randomUUID().toString();
    private static final String SPOOFED_MAGNES_GUID = UUID.randomUUID().toString();
    private static final String SPOOFED_GSF_ID = randomHex(16);
    private static final String SPOOFED_ANDROID_ID = randomHex(16);
    private static final long SPOOFED_UPTIME_OFFSET;
    private static final long SPOOFED_INSTALL_TIME;
    private static final String SPOOFED_UUID;
    private static final String SPOOFED_UUID_HASH;

    private static final Set<String> BLOCKED_DOMAINS = new HashSet<>(Arrays.asList(
        "c.paypal.com", "b.stats.paypal.com", "t.paypal.com", "www.paypalobjects.com", "api-m.paypal.com",
        "d.viewpkg.com", "service2.br.incognia.com", "service3.br.incognia.com", "service4.br.incognia.com",
        "idf-api.serasaexperian.com.br", "514012981.collect.igodigital.com"
    ));

    private static final Set<String> MAGNES_PREF_NAMES = new HashSet<>(Arrays.asList(
        "RiskManagerAG", "RiskManagerMG", "MagnesSettings", "PayPalRDA",
        "lib.android.paypal.com.magnessdk", "magnes_prefs"
    ));

    static {
        long now = System.currentTimeMillis();
        SPOOFED_INSTALL_TIME = now - ((60 + (long)(RNG.nextDouble() * 240)) * 86400000L);
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
        Log.e(TAG, "PersistentIdHooks v1.5.3 starting. NEW IDENTITY:");
        Log.e(TAG, "  UUID = " + SPOOFED_UUID + SPOOFED_UUID_HASH);

        hookDnsSinkhole(lpparam);
        hookOkHttpGlobalHeaders(lpparam);       // NOVA CAMADA — Global Headers.Builder
        hookOkHttpInterceptorChain(lpparam);
        hookOkHttpBlocking(lpparam);
        hookSharedPreferences(lpparam);
        hookGsfId(lpparam);
        hookAndroidId(lpparam);
        hookUptimeSpoof(lpparam);
        hookMagnesCollectAndSubmit(lpparam);
        hookUrlConnectionBlocking(lpparam);
        hookAdvertisingId(lpparam);
    }

    // ==================== CAMADA 1 — DNS SINKHOLE ====================
    private void hookDnsSinkhole(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String host = (String) param.args[0];
                    if (host != null && isBlockedDomain(host)) {
                        Log.e(TAG, "DNS SINKHOLE: " + host + " → 127.0.0.1");
                        param.setResult(InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1}));
                    }
                }
            });
            XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String host = (String) param.args[0];
                    if (host != null && isBlockedDomain(host)) {
                        InetAddress lo = InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1});
                        param.setResult(new InetAddress[]{lo});
                    }
                }
            });
        } catch (Throwable t) { Log.e(TAG, "DNS hook failed: " + t.getMessage()); }
    }

    // ==================== CAMADA 2 — GLOBAL HEADERS HOOK ====================
    /**
     * Hook em okhttp3.Headers$Builder.set(String, String) e add(String, String)
     * É aqui que o header x-mobile é construído antes de virar um Request.
     */
    private void hookOkHttpGlobalHeaders(LoadPackageParam lpparam) {
        try {
            Class<?> headersBuilderClass = XposedHelpers.findClass("okhttp3.Headers$Builder", lpparam.classLoader);
            XC_MethodHook headerHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[0];
                    String value = (String) param.args[1];
                    if (name != null && value != null && "x-mobile".equalsIgnoreCase(name)) {
                        if (value.contains("UUID=")) {
                            String newValue = rewriteXMobileHeader(value);
                            param.args[1] = newValue;
                            Log.e(TAG, "GLOBAL HEADERS.Builder: x-mobile UUID REWRITTEN");
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(headersBuilderClass, "set", String.class, String.class, headerHook);
            XposedHelpers.findAndHookMethod(headersBuilderClass, "add", String.class, String.class, headerHook);
            Log.e(TAG, "Global Headers.Builder hooks installed");
        } catch (Throwable t) { Log.e(TAG, "Headers.Builder hook failed: " + t.getMessage()); }

        // Hook no construtor de okhttp3.Request para garantir
        try {
            Class<?> requestClass = XposedHelpers.findClass("okhttp3.Request", lpparam.classLoader);
            XposedBridge.hookAllConstructors(requestClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // O construtor de Request recebe um Builder. Podemos tentar ler os headers do Builder.
                    // Mas é mais seguro deixar o Headers.Builder fazer o trabalho.
                }
            });
        } catch (Throwable t) {}
    }

    // ==================== CAMADA 3 — INTERCEPTOR CHAIN ====================
    private void hookOkHttpInterceptorChain(LoadPackageParam lpparam) {
        String[] chainClasses = {"okhttp3.internal.http.RealInterceptorChain", "okhttp3.internal.connection.RealInterceptorChain"};
        for (String clsName : chainClasses) {
            try {
                Class<?> chainClass = XposedHelpers.findClass(clsName, lpparam.classLoader);
                XposedBridge.hookAllMethods(chainClass, "proceed", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object request = null;
                        for (Object arg : param.args) {
                            if (arg != null && arg.getClass().getName().equals("okhttp3.Request")) { request = arg; break; }
                        }
                        if (request == null) return;
                        try {
                            String xMobile = (String) XposedHelpers.callMethod(request, "header", "x-mobile");
                            if (xMobile != null && xMobile.contains("UUID=")) {
                                String newXMobile = rewriteXMobileHeader(xMobile);
                                if (!newXMobile.equals(xMobile)) {
                                    Object builder = XposedHelpers.callMethod(request, "newBuilder");
                                    XposedHelpers.callMethod(builder, "header", "x-mobile", newXMobile);
                                    Object newRequest = XposedHelpers.callMethod(builder, "build");
                                    for (int i = 0; i < param.args.length; i++) {
                                        if (param.args[i] != null && param.args[i].getClass().getName().equals("okhttp3.Request")) {
                                            param.args[i] = newRequest; break;
                                        }
                                    }
                                    Log.e(TAG, "CHAIN.proceed() UUID REWRITTEN (Backup)");
                                }
                            }
                        } catch (Throwable t) {}
                    }
                });
                break;
            } catch (Throwable t) {}
        }
    }

    private String rewriteXMobileHeader(String value) {
        if (!value.contains("UUID=")) return value;
        int uuidStart = value.indexOf("UUID=") + 5;
        int uuidEnd = value.indexOf(",", uuidStart);
        if (uuidEnd == -1) uuidEnd = value.length();
        String newUuid = SPOOFED_UUID + SPOOFED_UUID_HASH;
        return value.substring(0, uuidStart) + newUuid + value.substring(uuidEnd);
    }

    // ==================== OUTRAS CAMADAS (MANTIDAS) ====================
    private void hookOkHttpBlocking(LoadPackageParam lpparam) {
        try {
            Class<?> okHttpClient = XposedHelpers.findClass("okhttp3.OkHttpClient", lpparam.classLoader);
            XposedBridge.hookAllMethods(okHttpClient, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0 && param.args[0] != null) {
                        Object request = param.args[0];
                        Object urlObj = XposedHelpers.callMethod(request, "url");
                        if (isBlockedDomain(urlObj.toString())) {
                            param.setThrowable(new java.io.IOException("Blocked by KMV Bypass"));
                        }
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
                    if (name != null && isMagnesPref(name)) {
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
                    if (param.args.length > 1 && "android_id".equals(param.args[1])) param.setResult(SPOOFED_ANDROID_ID);
                }
            });
        } catch (Throwable t) {}
    }

    private void hookAndroidId(LoadPackageParam lpparam) {
        try {
            XposedHelpers.setStaticObjectField(Build.class, "SERIAL", "POCO" + randomHex(12).toUpperCase());
            XposedHelpers.findAndHookMethod(Build.class, "getSerial", XC_MethodReplacement.returnConstant("POCO" + randomHex(12).toUpperCase()));
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
        String[] magnesClasses = {"lib.android.paypal.com.magnessdk.d", "lib.android.paypal.com.magnessdk.MagnesSDK", "lib.android.paypal.com.magnessdk.a", "lib.android.paypal.com.magnessdk.C"};
        for (String clsName : magnesClasses) {
            try {
                Class<?> cls = XposedHelpers.findClass(clsName, lpparam.classLoader);
                for (Method m : cls.getDeclaredMethods()) {
                    String name = m.getName();
                    if (name.equals("collectAndSubmit") || name.equals("collect") || name.equals("submit") || name.equals("f") || name.equals("g") || name.equals("h")) {
                        XposedBridge.hookMethod(m, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam p) { return null; } });
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

    private void hookAdvertisingId(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.google.android.gms.ads.identifier.AdvertisingIdClient$Info", lpparam.classLoader, "getId", XC_MethodReplacement.returnConstant(UUID.randomUUID().toString()));
        } catch (Throwable t) {}
    }

    private static boolean isBlockedDomain(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        for (String b : BLOCKED_DOMAINS) { if (host.contains(b)) return true; }
        return host.endsWith(".appsflyersdk.com");
    }

    private static boolean isMagnesPref(String name) {
        String l = name.toLowerCase();
        for (String p : MAGNES_PREF_NAMES) { if (l.contains(p.toLowerCase())) return true; }
        return l.contains("magnes") || l.contains("paypal") || l.contains("viewpkg");
    }
}
