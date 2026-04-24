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
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * v1.5.2 — DNS SINKHOLE + INTERCEPTOR CHAIN HOOK + UUID ROTATION
 *
 * Diagnóstico pós v1.5.1 (bypass5.1.har):
 *   - TODOS os 9 domínios de telemetria BLOQUEADOS via DNS sinkhole
 *   - UUID no x-mobile AINDA FIXO — Request.Builder hook não pegou
 *     porque o KMV usa OkHttp Interceptor para adicionar headers
 *   - AppsFlyer: 58 requisições com dados criptografados
 *   - igodigital (Salesforce): 6 requisições de tracking
 *
 * Estratégia v1.5.2:
 *   1. DNS SINKHOLE expandido (+ AppsFlyer, igodigital, Marketing Cloud)
 *   2. OkHttp Interceptor.Chain.proceed() hook para reescrever x-mobile UUID
 *   3. Request.headers() hook como backup para capturar UUID
 *   4. HTTP blocking mantido
 *   5. SharedPreferences + ID rotation mantidos
 */
public class PersistentIdHooks {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String TAG = MainHook.TAG_LOG;

    // IDs gerados UMA VEZ por sessão
    private static final String SPOOFED_APP_GUID = UUID.randomUUID().toString();
    private static final String SPOOFED_MAGNES_GUID = UUID.randomUUID().toString();
    private static final String SPOOFED_GSF_ID = randomHex(16);
    private static final String SPOOFED_ANDROID_ID = randomHex(16);
    private static final long SPOOFED_UPTIME_OFFSET;
    private static final long SPOOFED_INSTALL_TIME;
    private static final String SPOOFED_UUID;
    private static final String SPOOFED_UUID_HASH;

    // Domínios a bloquear via DNS sinkhole
    private static final Set<String> BLOCKED_DOMAINS = new HashSet<>(Arrays.asList(
        // PayPal Magnes
        "c.paypal.com",
        "b.stats.paypal.com",
        "t.paypal.com",
        "www.paypalobjects.com",
        "api-m.paypal.com",
        // ViewPkg
        "d.viewpkg.com",
        // Incognia
        "service2.br.incognia.com",
        "service3.br.incognia.com",
        "service4.br.incognia.com",
        // Serasa AllowMe
        "idf-api.serasaexperian.com.br"
    ));

    // Domínios de telemetria adicionais (v1.5.2)
    private static final Set<String> EXTRA_TELEMETRY = new HashSet<>(Arrays.asList(
        // AppsFlyer — envia device fingerprint criptografado
        "yv6qq3-inapps.appsflyersdk.com",
        "yv6qq3-launches.appsflyersdk.com",
        // Salesforce Marketing Cloud / igodigital
        "514012981.collect.igodigital.com"
    ));

    // SharedPreferences names do Magnes
    private static final Set<String> MAGNES_PREF_NAMES = new HashSet<>(Arrays.asList(
        "RiskManagerAG", "RiskManagerMG",
        "MagnesSettings", "PayPalRDA",
        "lib.android.paypal.com.magnessdk",
        "magnes_prefs"
    ));

    static {
        long now = System.currentTimeMillis();
        long daysAgo = 60 + (long)(RNG.nextDouble() * 240);
        SPOOFED_INSTALL_TIME = now - (daysAgo * 86400000L);
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
        Log.e(TAG, "PersistentIdHooks v1.5.2 starting. NEW IDENTITY:");
        Log.e(TAG, "  APP_GUID     = " + SPOOFED_APP_GUID);
        Log.e(TAG, "  MAGNES_GUID  = " + SPOOFED_MAGNES_GUID);
        Log.e(TAG, "  GSF_ID       = " + SPOOFED_GSF_ID);
        Log.e(TAG, "  ANDROID_ID   = " + SPOOFED_ANDROID_ID);
        Log.e(TAG, "  INSTALL_TIME = " + SPOOFED_INSTALL_TIME);
        Log.e(TAG, "  UUID         = " + SPOOFED_UUID + SPOOFED_UUID_HASH);

        hookDnsSinkhole(lpparam);               // CAMADA 1 — DNS sinkhole
        hookOkHttpInterceptorChain(lpparam);     // CAMADA 2 — Interceptor.Chain.proceed() UUID rewrite
        hookOkHttpBlocking(lpparam);             // CAMADA 3 — OkHttp blocking
        hookXMobileHeaderBuilder(lpparam);       // CAMADA 4 — Request.Builder backup
        hookSharedPreferences(lpparam);          // CAMADA 5 — SharedPreferences
        hookGsfId(lpparam);                      // CAMADA 6 — GSF ID
        hookAndroidId(lpparam);                  // CAMADA 7 — Android ID
        hookUptimeSpoof(lpparam);                // CAMADA 8 — Device Uptime
        hookMagnesCollectAndSubmit(lpparam);      // CAMADA 9 — Magnes SDK
        hookUrlConnectionBlocking(lpparam);      // CAMADA 10 — URL.openConnection
        hookAdvertisingId(lpparam);              // CAMADA 11 — Advertising ID
    }

    // ==================== CAMADA 1 — DNS SINKHOLE ====================

    private void hookDnsSinkhole(LoadPackageParam lpparam) {
        Set<String> allBlocked = new HashSet<>();
        allBlocked.addAll(BLOCKED_DOMAINS);
        allBlocked.addAll(EXTRA_TELEMETRY);

        try {
            XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String host = (String) param.args[0];
                        if (host != null && isBlockedDomain(host, allBlocked)) {
                            Log.e(TAG, "DNS SINKHOLE: " + host + " → 127.0.0.1");
                            param.setResult(InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1}));
                        }
                    }
                }
            );
        } catch (Throwable t) { Log.e(TAG, "DNS getByName hook failed: " + t.getMessage()); }

        try {
            XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String host = (String) param.args[0];
                        if (host != null && isBlockedDomain(host, allBlocked)) {
                            Log.e(TAG, "DNS SINKHOLE getAllByName: " + host);
                            InetAddress lo = InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1});
                            param.setResult(new InetAddress[]{lo});
                        }
                    }
                }
            );
        } catch (Throwable t) { Log.e(TAG, "DNS getAllByName hook failed: " + t.getMessage()); }

        Log.e(TAG, "DNS SINKHOLE: " + allBlocked.size() + " domains blocked");
    }

    // ==================== CAMADA 2 — INTERCEPTOR CHAIN HOOK ====================
    /**
     * Hook no OkHttp Interceptor.Chain.proceed(Request) para reescrever o header x-mobile
     * DEPOIS que todos os interceptors do app já adicionaram seus headers.
     * Também hook no Request.headers() e Request.header(String) para capturar leituras.
     */
    private void hookOkHttpInterceptorChain(LoadPackageParam lpparam) {
        // Abordagem 1: Hook no RealInterceptorChain.proceed(Request)
        String[] chainClasses = {
            "okhttp3.internal.http.RealInterceptorChain",
            "okhttp3.internal.connection.RealInterceptorChain"
        };

        for (String clsName : chainClasses) {
            try {
                Class<?> chainClass = XposedHelpers.findClass(clsName, lpparam.classLoader);
                XposedBridge.hookAllMethods(chainClass, "proceed", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // proceed(Request) — o Request é o primeiro argumento
                        Object request = null;
                        for (Object arg : param.args) {
                            if (arg != null && arg.getClass().getName().equals("okhttp3.Request")) {
                                request = arg;
                                break;
                            }
                        }
                        if (request == null && param.args.length > 0) {
                            request = param.args[0];
                        }
                        if (request == null) return;

                        try {
                            // Ler o header x-mobile
                            String xMobile = (String) XposedHelpers.callMethod(request, "header", "x-mobile");
                            if (xMobile != null && xMobile.contains("UUID=")) {
                                String newXMobile = rewriteXMobileHeader(xMobile);
                                if (!newXMobile.equals(xMobile)) {
                                    // Criar novo Request com header modificado
                                    Object newRequest = rebuildRequestWithHeader(request, "x-mobile", newXMobile, lpparam);
                                    if (newRequest != null) {
                                        // Substituir o argumento
                                        for (int i = 0; i < param.args.length; i++) {
                                            if (param.args[i] != null && param.args[i].getClass().getName().equals("okhttp3.Request")) {
                                                param.args[i] = newRequest;
                                                break;
                                            }
                                        }
                                        if (param.args.length > 0 && param.args[0] == request) {
                                            param.args[0] = newRequest;
                                        }
                                        Log.e(TAG, "CHAIN.proceed() x-mobile UUID REWRITTEN");
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "Chain proceed x-mobile rewrite error: " + t.getMessage());
                        }
                    }
                });
                Log.e(TAG, "Interceptor Chain.proceed() hook installed via " + clsName);
                break; // Sucesso, não tentar próxima classe
            } catch (Throwable t) {
                Log.e(TAG, "Chain hook failed for " + clsName + ": " + t.getMessage());
            }
        }

        // Abordagem 2: Hook no Request.newBuilder() para interceptar quando o app
        // cria um novo builder a partir de um request existente
        try {
            Class<?> requestClass = XposedHelpers.findClass("okhttp3.Request", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(requestClass, "newBuilder",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // Após criar o builder, verificar se tem x-mobile e reescrever
                        try {
                            Object builder = param.getResult();
                            // O builder herda os headers do Request original
                            // Precisamos chamar header("x-mobile", newValue) no builder
                            Object request = param.thisObject;
                            String xMobile = (String) XposedHelpers.callMethod(request, "header", "x-mobile");
                            if (xMobile != null && xMobile.contains("UUID=")) {
                                String newXMobile = rewriteXMobileHeader(xMobile);
                                if (!newXMobile.equals(xMobile)) {
                                    XposedHelpers.callMethod(builder, "header", "x-mobile", newXMobile);
                                    Log.e(TAG, "Request.newBuilder() x-mobile UUID REWRITTEN");
                                }
                            }
                        } catch (Throwable t) {
                            // Silenciar
                        }
                    }
                }
            );
            Log.e(TAG, "Request.newBuilder() hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "Request.newBuilder hook failed: " + t.getMessage());
        }

        // Abordagem 3: Hook direto no Request.build() do Builder
        try {
            Class<?> builderClass = XposedHelpers.findClass("okhttp3.Request$Builder", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(builderClass, "build",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            // Acessar o campo headers do builder
                            Object headersBuilder = XposedHelpers.getObjectField(param.thisObject, "headers");
                            if (headersBuilder != null) {
                                // Headers.Builder tem método set(String, String)
                                // Primeiro, tentar ler o valor atual de x-mobile
                                String xMobile = null;
                                try {
                                    xMobile = (String) XposedHelpers.callMethod(headersBuilder, "get", "x-mobile");
                                } catch (Throwable t) {
                                    // Tentar alternativa
                                }
                                if (xMobile != null && xMobile.contains("UUID=")) {
                                    String newXMobile = rewriteXMobileHeader(xMobile);
                                    if (!newXMobile.equals(xMobile)) {
                                        XposedHelpers.callMethod(headersBuilder, "set", "x-mobile", newXMobile);
                                        Log.e(TAG, "Request.Builder.build() x-mobile UUID REWRITTEN");
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            // Silenciar — nem todos os requests têm x-mobile
                        }
                    }
                }
            );
            Log.e(TAG, "Request.Builder.build() hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "Request.Builder.build hook failed: " + t.getMessage());
        }
    }

    /**
     * Reconstrói um Request OkHttp com um header substituído.
     */
    private Object rebuildRequestWithHeader(Object request, String headerName, String headerValue, LoadPackageParam lpparam) {
        try {
            Object builder = XposedHelpers.callMethod(request, "newBuilder");
            XposedHelpers.callMethod(builder, "header", headerName, headerValue);
            return XposedHelpers.callMethod(builder, "build");
        } catch (Throwable t) {
            Log.e(TAG, "rebuildRequestWithHeader failed: " + t.getMessage());
            return null;
        }
    }

    // ==================== CAMADA 3 — HTTP BLOCKING ====================

    private void hookOkHttpBlocking(LoadPackageParam lpparam) {
        Set<String> allBlocked = new HashSet<>();
        allBlocked.addAll(BLOCKED_DOMAINS);
        allBlocked.addAll(EXTRA_TELEMETRY);

        try {
            Class<?> okHttpClient = XposedHelpers.findClass("okhttp3.OkHttpClient", lpparam.classLoader);
            XposedBridge.hookAllMethods(okHttpClient, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0 && param.args[0] != null) {
                        Object request = param.args[0];
                        Object urlObj = XposedHelpers.callMethod(request, "url");
                        String host = extractHost(urlObj.toString(), urlObj);
                        if (isBlockedDomain(host, allBlocked)) {
                            Log.e(TAG, "HTTP BLOCKED: " + host);
                            param.setThrowable(new java.io.IOException("Blocked by KMV Bypass: " + host));
                        }
                    }
                }
            });
        } catch (Throwable t) { Log.e(TAG, "OkHttp newCall hook failed: " + t.getMessage()); }

        // RealCall backup
        for (String clsName : new String[]{"okhttp3.internal.connection.RealCall", "okhttp3.RealCall"}) {
            try {
                Class<?> realCall = XposedHelpers.findClass(clsName, lpparam.classLoader);
                for (String method : new String[]{"execute", "enqueue"}) {
                    XposedBridge.hookAllMethods(realCall, method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object req = XposedHelpers.callMethod(param.thisObject, "request");
                                Object urlObj = XposedHelpers.callMethod(req, "url");
                                String host = extractHost(urlObj.toString(), urlObj);
                                if (isBlockedDomain(host, allBlocked)) {
                                    Log.e(TAG, "RealCall BLOCKED: " + host);
                                    if ("execute".equals(method)) {
                                        param.setThrowable(new java.io.IOException("Blocked"));
                                    } else {
                                        param.setResult(null);
                                    }
                                }
                            } catch (Throwable t) { /* silenciar */ }
                        }
                    });
                }
                break;
            } catch (Throwable t) { /* tentar próxima */ }
        }
    }

    // ==================== CAMADA 4 — REQUEST.BUILDER BACKUP ====================

    private void hookXMobileHeaderBuilder(LoadPackageParam lpparam) {
        for (String methodName : new String[]{"addHeader", "header"}) {
            try {
                Class<?> builderClass = XposedHelpers.findClass("okhttp3.Request$Builder", lpparam.classLoader);
                XposedHelpers.findAndHookMethod(builderClass, methodName, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String name = (String) param.args[0];
                            String value = (String) param.args[1];
                            if (name != null && value != null && "x-mobile".equalsIgnoreCase(name)) {
                                String newValue = rewriteXMobileHeader(value);
                                if (!newValue.equals(value)) {
                                    param.args[1] = newValue;
                                    Log.e(TAG, "Builder." + methodName + "() x-mobile UUID ROTATED");
                                }
                            }
                        }
                    }
                );
            } catch (Throwable t) { /* silenciar */ }
        }

        // HttpURLConnection backup
        try {
            XposedHelpers.findAndHookMethod(java.net.HttpURLConnection.class, "setRequestProperty",
                String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String name = (String) param.args[0];
                        String value = (String) param.args[1];
                        if (name != null && value != null && "x-mobile".equalsIgnoreCase(name)) {
                            param.args[1] = rewriteXMobileHeader(value);
                        }
                    }
                }
            );
        } catch (Throwable t) { /* silenciar */ }
    }

    // ==================== UUID REWRITE LOGIC ====================

    private String rewriteXMobileHeader(String value) {
        if (!value.contains("UUID=")) return value;

        int uuidStart = value.indexOf("UUID=") + 5;
        int uuidEnd = value.indexOf(",", uuidStart);
        if (uuidEnd == -1) uuidEnd = value.length();

        // Novo UUID no mesmo formato: uuid-com-hifens + hash hex (40 chars)
        String newUuid = SPOOFED_UUID + SPOOFED_UUID_HASH;

        String result = value.substring(0, uuidStart) + newUuid + value.substring(uuidEnd);
        Log.e(TAG, "UUID rewritten: " + newUuid.substring(0, 30) + "...");
        return result;
    }

    // ==================== CAMADA 5 — SHARED PREFERENCES ====================

    private void hookSharedPreferences(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader,
                "getSharedPreferences", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String name = (String) param.args[0];
                        if (name != null && isMagnesPref(name)) {
                            Object sp = param.getResult();
                            if (sp != null) {
                                try {
                                    ((SharedPreferences) sp).edit().clear().apply();
                                    Log.e(TAG, "SharedPreferences '" + name + "' CLEARED");
                                } catch (Throwable t) { /* silenciar */ }
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) { Log.e(TAG, "SP hook failed: " + t.getMessage()); }

        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.app.SharedPreferencesImpl", lpparam.classLoader),
                "getString",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof String) {
                            String key = ((String) param.args[0]).toLowerCase();
                            if (key.contains("app_guid") || key.contains("appguid")) {
                                param.setResult(SPOOFED_APP_GUID);
                            } else if (key.contains("magnes_guid") || key.contains("magnesguid")) {
                                param.setResult(SPOOFED_MAGNES_GUID);
                            } else if (key.contains("device_id") || key.contains("deviceid") || key.contains("installation_id")) {
                                param.setResult(UUID.randomUUID().toString());
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) { /* silenciar */ }
    }

    private static boolean isMagnesPref(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String pref : MAGNES_PREF_NAMES) {
            if (lower.contains(pref.toLowerCase())) return true;
        }
        return lower.contains("magnes") || lower.contains("paypal") || lower.contains("rda")
            || lower.contains("riskmanager") || lower.contains("viewpkg");
    }

    // ==================== CAMADA 6 — GSF ID ====================

    private void hookGsfId(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString",
                ContentResolver.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 1 && "android_id".equals(param.args[1])) {
                            param.setResult(SPOOFED_ANDROID_ID);
                        }
                    }
                }
            );
        } catch (Throwable t) { /* silenciar */ }

        try {
            XposedBridge.hookAllMethods(ContentResolver.class, "query",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] != null) {
                            String uri = param.args[0].toString();
                            if (uri.contains("com.google.android.gsf") || uri.contains("gservices")) {
                                param.setResult(null);
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) { /* silenciar */ }
    }

    // ==================== CAMADA 7 — ANDROID ID ====================

    private void hookAndroidId(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Settings.Global.class, "getString",
                ContentResolver.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 1 && "android_id".equals(param.args[1])) {
                            param.setResult(SPOOFED_ANDROID_ID);
                        }
                    }
                }
            );
        } catch (Throwable t) { /* silenciar */ }

        try {
            XposedHelpers.setStaticObjectField(Build.class, "SERIAL", "POCO" + randomHex(12).toUpperCase());
        } catch (Throwable t) { /* silenciar */ }

        try {
            XposedHelpers.findAndHookMethod(Build.class, "getSerial",
                XC_MethodReplacement.returnConstant("POCO" + randomHex(12).toUpperCase()));
        } catch (Throwable t) { /* silenciar */ }
    }

    // ==================== CAMADA 8 — DEVICE UPTIME ====================

    private void hookUptimeSpoof(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(SystemClock.class, "elapsedRealtime",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult((Long) param.getResult() + SPOOFED_UPTIME_OFFSET);
                    }
                }
            );
        } catch (Throwable t) { /* silenciar */ }
    }

    // ==================== CAMADA 9 — MAGNES SDK ====================

    private void hookMagnesCollectAndSubmit(LoadPackageParam lpparam) {
        String[] magnesClasses = {
            "lib.android.paypal.com.magnessdk.d",
            "lib.android.paypal.com.magnessdk.MagnesSDK",
            "lib.android.paypal.com.magnessdk.a",
        };

        for (String clsName : magnesClasses) {
            try {
                Class<?> cls = XposedHelpers.findClass(clsName, lpparam.classLoader);
                for (Method m : cls.getDeclaredMethods()) {
                    String name = m.getName();
                    if (name.equals("collectAndSubmit") || name.equals("collect")
                        || name.equals("submit") || name.equals("f")
                        || name.equals("g") || name.equals("h")) {
                        try {
                            Class<?> ret = m.getReturnType();
                            if (ret == void.class) {
                                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                            } else if (ret.getName().contains("String")) {
                                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(SPOOFED_MAGNES_GUID));
                            } else {
                                XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                                    @Override protected Object replaceHookedMethod(MethodHookParam p) { return null; }
                                });
                            }
                        } catch (Throwable t) { /* silenciar */ }
                    }
                }
            } catch (Throwable t) { /* classe não encontrada */ }
        }

        // Magnes C class
        try {
            Class<?> clsC = XposedHelpers.findClass("lib.android.paypal.com.magnessdk.C", lpparam.classLoader);
            for (Method m : clsC.getDeclaredMethods()) {
                try {
                    String retName = m.getReturnType().getName();
                    if (retName.equals("org.json.JSONObject")) {
                        XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                            @Override protected Object replaceHookedMethod(MethodHookParam p) {
                                try {
                                    org.json.JSONObject j = new org.json.JSONObject();
                                    j.put("app_id", "com.gigigo.ipirangaconectcar");
                                    j.put("os_type", "Android");
                                    j.put("os_version", "11");
                                    j.put("is_rooted", false);
                                    j.put("is_emulator", false);
                                    j.put("android_id", SPOOFED_ANDROID_ID);
                                    j.put("app_guid", SPOOFED_APP_GUID);
                                    return j;
                                } catch (Throwable t) { return new org.json.JSONObject(); }
                            }
                        });
                    } else if (retName.contains("List")) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(new ArrayList<>()));
                    } else if (retName.equals("boolean") && !m.getName().equals("equals")) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                    }
                } catch (Throwable t) { /* silenciar */ }
            }
        } catch (Throwable t) { /* silenciar */ }
    }

    // ==================== CAMADA 10 — URL.openConnection ====================

    private void hookUrlConnectionBlocking(LoadPackageParam lpparam) {
        Set<String> allBlocked = new HashSet<>();
        allBlocked.addAll(BLOCKED_DOMAINS);
        allBlocked.addAll(EXTRA_TELEMETRY);

        try {
            XposedHelpers.findAndHookMethod(java.net.URL.class, "openConnection",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        java.net.URL url = (java.net.URL) param.thisObject;
                        if (isBlockedDomain(url.getHost(), allBlocked)) {
                            param.setThrowable(new java.io.IOException("Blocked: " + url.getHost()));
                        }
                    }
                }
            );
        } catch (Throwable t) { /* silenciar */ }

        try {
            XposedHelpers.findAndHookMethod(java.net.URL.class, "openConnection", java.net.Proxy.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        java.net.URL url = (java.net.URL) param.thisObject;
                        if (isBlockedDomain(url.getHost(), allBlocked)) {
                            param.setThrowable(new java.io.IOException("Blocked: " + url.getHost()));
                        }
                    }
                }
            );
        } catch (Throwable t) { /* silenciar */ }
    }

    // ==================== CAMADA 11 — ADVERTISING ID ====================

    private void hookAdvertisingId(LoadPackageParam lpparam) {
        String fakeGaid = UUID.randomUUID().toString();
        try {
            XposedHelpers.findAndHookMethod(
                "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info",
                lpparam.classLoader, "getId",
                XC_MethodReplacement.returnConstant(fakeGaid));
        } catch (Throwable t) { /* silenciar */ }
    }

    // ==================== HELPERS ====================

    private String extractHost(String url, Object urlObj) {
        try {
            return (String) XposedHelpers.callMethod(urlObj, "host");
        } catch (Throwable t) {
            try {
                if (url.contains("://")) return url.split("://")[1].split("/")[0].split(":")[0];
            } catch (Throwable t2) { /* silenciar */ }
        }
        return "";
    }

    private static boolean isBlockedDomain(String host, Set<String> blockedSet) {
        if (host == null) return false;
        host = host.toLowerCase();
        for (String blocked : blockedSet) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) return true;
        }
        // Wildcard para subdomínios AppsFlyer
        if (host.endsWith(".appsflyersdk.com")) return true;
        return false;
    }
}
