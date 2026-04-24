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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * v1.5 — PERSISTENT ID HOOKS + HTTP BLOCKING (Nuclear Strategy)
 *
 * Diagnóstico dos 4 HARs (PRIMEIRO → ÚLTIMO):
 *   - Os hooks v1.4 na classe C do Magnes NÃO impediram o envio do payload completo.
 *   - O Magnes SDK v5.5.1 constrói e envia o payload por um caminho HTTP interno
 *     que bypassa os hooks nos métodos geradores de JSON.
 *   - Dados persistentes NUNCA mudaram entre sessões:
 *     * magnes_guid: 06441a0f-30c2-440d-8ed0-0eea1f645a4f (FIXO)
 *     * app_guid: e787081b-b442-4b5b-9c5b-3fd9db7ca6da (FIXO)
 *     * gsf_id: 34f59cc76e211d30 (FIXO)
 *     * app_first_install_time: 1776989122086 (FIXO)
 *   - ViewPkg continua enviando 18 requisições por sessão.
 *   - VPN (tun0) detectada nos primeiros HARs.
 *
 * Estratégia v1.5 (Nuclear):
 *   1. BLOQUEAR HTTP POST para c.paypal.com e d.viewpkg.com no OkHttpClient
 *   2. Rotacionar magnes_guid e app_guid nas SharedPreferences
 *   3. Spoofar gsf_id (Google Services Framework ID)
 *   4. Interceptar SystemClock.elapsedRealtime() para device_uptime falso
 *   5. Limpar SharedPreferences do Magnes ao iniciar
 *   6. Interceptar o URL builder para bloquear requisições de telemetria
 */
public class PersistentIdHooks {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String TAG = MainHook.TAG_LOG;

    // IDs gerados UMA VEZ por sessão (consistentes durante a execução)
    private static final String SPOOFED_APP_GUID = UUID.randomUUID().toString();
    private static final String SPOOFED_MAGNES_GUID = UUID.randomUUID().toString();
    private static final String SPOOFED_GSF_ID = randomHex(16);
    private static final String SPOOFED_ANDROID_ID = randomHex(16);
    private static final long SPOOFED_UPTIME_OFFSET;
    private static final long SPOOFED_INSTALL_TIME;
    private static final long SPOOFED_UPDATE_TIME;
    private static final String SPOOFED_UUID;

    // Domínios a bloquear
    private static final Set<String> BLOCKED_DOMAINS = new HashSet<>(Arrays.asList(
        "c.paypal.com",
        "d.viewpkg.com",
        "b.stats.paypal.com",
        "t.paypal.com",
        "www.paypalobjects.com"
    ));

    // Chaves de SharedPreferences do Magnes a interceptar
    private static final Set<String> MAGNES_PREF_KEYS = new HashSet<>(Arrays.asList(
        "app_guid", "APP_GUID",
        "magnes_guid", "MAGNES_GUID",
        "magnes_guid_id", "magnes_guid_created_at",
        "risk_session_id", "pairing_id",
        "dc_id", "mg_id",
        "installation_id", "install_id",
        "device_id", "deviceId"
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
        // Install time: entre 60 e 300 dias atrás
        long daysAgo = 60 + (long)(RNG.nextDouble() * 240);
        SPOOFED_INSTALL_TIME = now - (daysAgo * 86400000L);
        SPOOFED_UPDATE_TIME = SPOOFED_INSTALL_TIME + (long)(RNG.nextDouble() * (now - SPOOFED_INSTALL_TIME));
        // Uptime offset: adicionar entre 1 e 7 dias para parecer device ligado há tempo
        SPOOFED_UPTIME_OFFSET = (1 + (long)(RNG.nextDouble() * 6)) * 86400000L;
        // UUID para o header x-mobile
        SPOOFED_UUID = UUID.randomUUID().toString();
    }

    private static String randomHex(int chars) {
        byte[] b = new byte[(chars + 1) / 2];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.substring(0, chars);
    }

    public void install(LoadPackageParam lpparam) {
        Log.e(TAG, "PersistentIdHooks v1.5 starting. NEW IDENTITY:");
        Log.e(TAG, "  APP_GUID     = " + SPOOFED_APP_GUID);
        Log.e(TAG, "  MAGNES_GUID  = " + SPOOFED_MAGNES_GUID);
        Log.e(TAG, "  GSF_ID       = " + SPOOFED_GSF_ID);
        Log.e(TAG, "  ANDROID_ID   = " + SPOOFED_ANDROID_ID);
        Log.e(TAG, "  INSTALL_TIME = " + SPOOFED_INSTALL_TIME);
        Log.e(TAG, "  UUID         = " + SPOOFED_UUID);

        hookOkHttpBlocking(lpparam);
        hookSharedPreferences(lpparam);
        hookGsfId(lpparam);
        hookAndroidId(lpparam);
        hookUptimeSpoof(lpparam);
        hookMagnesCollectAndSubmit(lpparam);
        hookUrlConnectionBlocking(lpparam);
        hookAdvertisingId(lpparam);
    }

    /**
     * CAMADA 1 — BLOQUEIO HTTP NUCLEAR via OkHttpClient.newCall()
     * Intercepta TODAS as requisições HTTP e bloqueia as que vão para domínios de telemetria.
     */
    private void hookOkHttpBlocking(LoadPackageParam lpparam) {
        int blocked = 0;

        // Estratégia 1: Hook no OkHttpClient.newCall(Request)
        try {
            Class<?> okHttpClient = XposedHelpers.findClass("okhttp3.OkHttpClient", lpparam.classLoader);
            Class<?> requestClass = XposedHelpers.findClass("okhttp3.Request", lpparam.classLoader);

            XposedBridge.hookAllMethods(okHttpClient, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0 && param.args[0] != null) {
                        Object request = param.args[0];
                        // Obter a URL do Request
                        Object urlObj = XposedHelpers.callMethod(request, "url");
                        String url = urlObj.toString();
                        String host = "";
                        try {
                            host = (String) XposedHelpers.callMethod(urlObj, "host");
                        } catch (Throwable t) {
                            // Fallback: extrair host da URL string
                            if (url.contains("://")) {
                                host = url.split("://")[1].split("/")[0].split(":")[0];
                            }
                        }

                        if (isBlockedDomain(host)) {
                            Log.e(TAG, "HTTP BLOCKED: " + url);
                            // Criar uma Response fake de sucesso
                            try {
                                Class<?> responseClass = XposedHelpers.findClass("okhttp3.Response", lpparam.classLoader);
                                Class<?> responseBuilderClass = XposedHelpers.findClass("okhttp3.Response$Builder", lpparam.classLoader);
                                Class<?> protocolClass = XposedHelpers.findClass("okhttp3.Protocol", lpparam.classLoader);
                                Class<?> responseBodyClass = XposedHelpers.findClass("okhttp3.ResponseBody", lpparam.classLoader);
                                Class<?> mediaTypeClass = XposedHelpers.findClass("okhttp3.MediaType", lpparam.classLoader);

                                Object protocol = XposedHelpers.getStaticObjectField(protocolClass, "HTTP_1_1");
                                Object mediaType = XposedHelpers.callStaticMethod(mediaTypeClass, "parse", "application/json");
                                Object body = XposedHelpers.callStaticMethod(responseBodyClass, "create", mediaType, "{\"status\":\"OK\"}");

                                Object builder = responseBuilderClass.newInstance();
                                XposedHelpers.callMethod(builder, "request", request);
                                XposedHelpers.callMethod(builder, "protocol", protocol);
                                XposedHelpers.callMethod(builder, "code", 200);
                                XposedHelpers.callMethod(builder, "message", "OK");
                                XposedHelpers.callMethod(builder, "body", body);

                                Object fakeResponse = XposedHelpers.callMethod(builder, "build");

                                // Criar um Call fake que retorna a response
                                Object fakeCall = java.lang.reflect.Proxy.newProxyInstance(
                                    lpparam.classLoader,
                                    new Class[]{XposedHelpers.findClass("okhttp3.Call", lpparam.classLoader)},
                                    (proxy, method, args) -> {
                                        String mName = method.getName();
                                        if ("execute".equals(mName)) return fakeResponse;
                                        if ("enqueue".equals(mName)) {
                                            // Chamar onResponse do callback
                                            if (args != null && args.length > 0 && args[0] != null) {
                                                try {
                                                    Method onResponse = args[0].getClass().getMethod("onResponse",
                                                        XposedHelpers.findClass("okhttp3.Call", lpparam.classLoader),
                                                        responseClass);
                                                    onResponse.invoke(args[0], proxy, fakeResponse);
                                                } catch (Throwable t) {
                                                    Log.e(TAG, "enqueue callback failed: " + t.getMessage());
                                                }
                                            }
                                            return null;
                                        }
                                        if ("cancel".equals(mName)) return null;
                                        if ("isExecuted".equals(mName)) return true;
                                        if ("isCanceled".equals(mName)) return false;
                                        if ("clone".equals(mName)) return proxy;
                                        if ("request".equals(mName)) return request;
                                        if ("timeout".equals(mName)) return XposedHelpers.newInstance(
                                            XposedHelpers.findClass("okio.Timeout", lpparam.classLoader));
                                        return null;
                                    }
                                );
                                param.setResult(fakeCall);
                            } catch (Throwable t) {
                                Log.e(TAG, "Fake response creation failed, throwing IOException: " + t.getMessage());
                                param.setThrowable(new java.io.IOException("Blocked by KMV Bypass: " + host));
                            }
                        }
                    }
                }
            });
            Log.e(TAG, "OkHttpClient.newCall() HTTP blocking installed");
            blocked++;
        } catch (Throwable t) {
            Log.e(TAG, "OkHttpClient.newCall hook failed: " + t.getMessage());
        }

        // Estratégia 2: Hook no RealCall.execute() e RealCall.enqueue() como backup
        try {
            Class<?> realCall = XposedHelpers.findClass("okhttp3.internal.connection.RealCall", lpparam.classLoader);
            hookRealCallMethod(realCall, "execute", lpparam);
            hookRealCallMethod(realCall, "enqueue", lpparam);
            blocked++;
        } catch (Throwable t) {
            // Tentar nome alternativo
            try {
                Class<?> realCall = XposedHelpers.findClass("okhttp3.RealCall", lpparam.classLoader);
                hookRealCallMethod(realCall, "execute", lpparam);
                hookRealCallMethod(realCall, "enqueue", lpparam);
                blocked++;
            } catch (Throwable t2) {
                Log.e(TAG, "RealCall hook failed: " + t2.getMessage());
            }
        }

        Log.e(TAG, "HTTP blocking: " + blocked + " layers installed");
    }

    private void hookRealCallMethod(Class<?> realCallClass, String methodName, LoadPackageParam lpparam) {
        try {
            XposedBridge.hookAllMethods(realCallClass, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object request = XposedHelpers.callMethod(param.thisObject, "request");
                        Object urlObj = XposedHelpers.callMethod(request, "url");
                        String host = "";
                        try {
                            host = (String) XposedHelpers.callMethod(urlObj, "host");
                        } catch (Throwable t) {
                            host = urlObj.toString().split("://")[1].split("/")[0].split(":")[0];
                        }
                        if (isBlockedDomain(host)) {
                            Log.e(TAG, "RealCall." + methodName + " BLOCKED: " + host);
                            if ("execute".equals(methodName)) {
                                param.setThrowable(new java.io.IOException("Blocked by KMV Bypass"));
                            } else {
                                // enqueue — simplesmente não fazer nada
                                param.setResult(null);
                            }
                        }
                    } catch (Throwable t) {
                        // Silenciar para não quebrar requisições normais
                    }
                }
            });
            Log.e(TAG, "RealCall." + methodName + " blocking installed");
        } catch (Throwable t) {
            Log.e(TAG, "RealCall." + methodName + " hook failed: " + t.getMessage());
        }
    }

    private static boolean isBlockedDomain(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        for (String blocked : BLOCKED_DOMAINS) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) {
                return true;
            }
        }
        return false;
    }

    /**
     * CAMADA 2 — SharedPreferences Interception
     * Intercepta TODAS as leituras/escritas de SharedPreferences do Magnes
     * para rotacionar os IDs persistentes.
     */
    private void hookSharedPreferences(LoadPackageParam lpparam) {
        try {
            // Hook no Context.getSharedPreferences para interceptar os prefs do Magnes
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl", lpparam.classLoader,
                "getSharedPreferences", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String name = (String) param.args[0];
                        if (name != null && isMagnesPref(name)) {
                            Object sp = param.getResult();
                            if (sp != null) {
                                // Limpar as SharedPreferences do Magnes
                                try {
                                    SharedPreferences.Editor editor = ((SharedPreferences) sp).edit();
                                    editor.clear();
                                    editor.apply();
                                    Log.e(TAG, "SharedPreferences '" + name + "' CLEARED");
                                } catch (Throwable t) {
                                    Log.e(TAG, "SP clear failed for " + name + ": " + t.getMessage());
                                }
                            }
                        }
                    }
                }
            );
            Log.e(TAG, "SharedPreferences interception installed");
        } catch (Throwable t) {
            Log.e(TAG, "SharedPreferences hook failed: " + t.getMessage());
        }

        // Hook no getString para retornar valores spoofados
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
                                Log.e(TAG, "SP.getString('" + param.args[0] + "') -> " + SPOOFED_APP_GUID);
                            } else if (key.contains("magnes_guid") || key.contains("magnesguid")) {
                                param.setResult(SPOOFED_MAGNES_GUID);
                                Log.e(TAG, "SP.getString('" + param.args[0] + "') -> " + SPOOFED_MAGNES_GUID);
                            } else if (key.contains("device_id") || key.contains("deviceid") || key.contains("installation_id")) {
                                param.setResult(UUID.randomUUID().toString());
                            }
                        }
                    }
                }
            );
            Log.e(TAG, "SharedPreferences.getString spoofing installed");
        } catch (Throwable t) {
            Log.e(TAG, "SP getString hook failed: " + t.getMessage());
        }
    }

    private static boolean isMagnesPref(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String pref : MAGNES_PREF_NAMES) {
            if (lower.contains(pref.toLowerCase())) return true;
        }
        // Também interceptar prefs com "magnes", "paypal", "rda" no nome
        return lower.contains("magnes") || lower.contains("paypal") || lower.contains("rda")
            || lower.contains("riskmanager") || lower.contains("viewpkg");
    }

    /**
     * CAMADA 3 — GSF ID (Google Services Framework)
     * O Magnes lê o gsf_id via ContentResolver query no GServices.
     */
    private void hookGsfId(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Settings.Secure.class,
                "getString",
                ContentResolver.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 1 && param.args[1] instanceof String) {
                            String key = (String) param.args[1];
                            if ("android_id".equals(key)) {
                                param.setResult(SPOOFED_ANDROID_ID);
                            }
                        }
                    }
                }
            );
            Log.e(TAG, "Settings.Secure.getString(android_id) -> " + SPOOFED_ANDROID_ID);
        } catch (Throwable t) {
            Log.e(TAG, "Settings.Secure hook failed: " + t.getMessage());
        }

        // Hook no ContentResolver.query para interceptar gsf_id
        try {
            XposedBridge.hookAllMethods(
                ContentResolver.class,
                "query",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] != null) {
                            String uri = param.args[0].toString();
                            if (uri.contains("com.google.android.gsf") || uri.contains("gservices")) {
                                Log.e(TAG, "ContentResolver.query BLOCKED for GSF: " + uri);
                                param.setResult(null);
                            }
                        }
                    }
                }
            );
            Log.e(TAG, "ContentResolver.query GSF blocking installed");
        } catch (Throwable t) {
            Log.e(TAG, "ContentResolver.query hook failed: " + t.getMessage());
        }
    }

    /**
     * CAMADA 4 — Android ID reforçado
     */
    private void hookAndroidId(LoadPackageParam lpparam) {
        // Já hookado no GSF, mas reforçar com Settings.Global também
        try {
            XposedHelpers.findAndHookMethod(
                Settings.Global.class,
                "getString",
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
        } catch (Throwable t) {
            // Settings.Global pode não ter android_id em todas as versões
        }

        // Hook Build.SERIAL
        try {
            String spoofedSerial = "POCO" + randomHex(12).toUpperCase();
            XposedHelpers.setStaticObjectField(Build.class, "SERIAL", spoofedSerial);
            Log.e(TAG, "Build.SERIAL -> " + spoofedSerial);
        } catch (Throwable t) {
            Log.e(TAG, "Build.SERIAL spoof failed: " + t.getMessage());
        }

        // Hook Build.getSerial()
        try {
            XposedHelpers.findAndHookMethod(Build.class, "getSerial",
                XC_MethodReplacement.returnConstant("POCO" + randomHex(12).toUpperCase()));
        } catch (Throwable t) {
            // Pode não existir em API < 26
        }
    }

    /**
     * CAMADA 5 — Device Uptime Spoof
     * O Magnes envia device_uptime que pode ser usado para correlação.
     */
    private void hookUptimeSpoof(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                SystemClock.class,
                "elapsedRealtime",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        long real = (Long) param.getResult();
                        param.setResult(real + SPOOFED_UPTIME_OFFSET);
                    }
                }
            );
            Log.e(TAG, "SystemClock.elapsedRealtime() offset: +" + SPOOFED_UPTIME_OFFSET + "ms");
        } catch (Throwable t) {
            Log.e(TAG, "Uptime spoof failed: " + t.getMessage());
        }
    }

    /**
     * CAMADA 6 — Magnes collectAndSubmit / setUp interception
     * Hook direto nos métodos de inicialização e coleta do Magnes SDK.
     */
    private void hookMagnesCollectAndSubmit(LoadPackageParam lpparam) {
        // Hook na classe MagnesSDK (d) — setUp e collectAndSubmit
        String[] magnesClasses = {
            "lib.android.paypal.com.magnessdk.d",
            "lib.android.paypal.com.magnessdk.MagnesSDK",
            "lib.android.paypal.com.magnessdk.a",
        };

        for (String clsName : magnesClasses) {
            try {
                Class<?> cls = XposedHelpers.findClass(clsName, lpparam.classLoader);
                int hooked = 0;
                for (Method m : cls.getDeclaredMethods()) {
                    String name = m.getName();
                    // Hook collectAndSubmit, collect, submit, setUp
                    if (name.equals("collectAndSubmit") || name.equals("collect")
                        || name.equals("submit") || name.equals("f")
                        || name.equals("g") || name.equals("h")) {
                        try {
                            Class<?> retType = m.getReturnType();
                            if (retType == void.class) {
                                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                            } else if (retType.getName().contains("String")) {
                                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(SPOOFED_MAGNES_GUID));
                            } else {
                                XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                                    @Override
                                    protected Object replaceHookedMethod(MethodHookParam param) {
                                        return null;
                                    }
                                });
                            }
                            hooked++;
                            Log.e(TAG, "Magnes " + clsName + "." + name + "() NEUTRALIZED");
                        } catch (Throwable t) {
                            // Silenciar
                        }
                    }
                }
                if (hooked > 0) {
                    Log.e(TAG, "Magnes class " + clsName + ": " + hooked + " methods neutralized");
                }
            } catch (Throwable t) {
                // Classe não encontrada, tentar próxima
            }
        }

        // Hook AGRESSIVO: todos os métodos da classe C que retornam JSONObject
        try {
            Class<?> clsC = XposedHelpers.findClass("lib.android.paypal.com.magnessdk.C", lpparam.classLoader);
            int hookedCount = 0;
            for (Method m : clsC.getDeclaredMethods()) {
                try {
                    String retName = m.getReturnType().getName();
                    if (retName.equals("org.json.JSONObject")) {
                        XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) {
                                try {
                                    org.json.JSONObject j = new org.json.JSONObject();
                                    j.put("app_id", "com.gigigo.ipirangaconectcar");
                                    j.put("os_type", "Android");
                                    j.put("os_version", "11");
                                    j.put("is_rooted", false);
                                    j.put("is_emulator", false);
                                    j.put("android_id", SPOOFED_ANDROID_ID);
                                    j.put("app_guid", SPOOFED_APP_GUID);
                                    j.put("magnes_guid", SPOOFED_MAGNES_GUID);
                                    return j;
                                } catch (Throwable t) {
                                    return new org.json.JSONObject();
                                }
                            }
                        });
                        hookedCount++;
                    } else if (retName.equals("java.util.List") || retName.equals("java.util.ArrayList")) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(new ArrayList<>()));
                        hookedCount++;
                    } else if (retName.equals("boolean") && !m.getName().equals("equals")) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                        hookedCount++;
                    }
                } catch (Throwable t) {
                    // Silenciar
                }
            }
            Log.e(TAG, "Magnes C class: " + hookedCount + " methods neutralized");
        } catch (Throwable t) {
            Log.e(TAG, "Magnes C class hook failed: " + t.getMessage());
        }
    }

    /**
     * CAMADA 7 — HttpURLConnection blocking (fallback para requisições não-OkHttp)
     */
    private void hookUrlConnectionBlocking(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                java.net.URL.class,
                "openConnection",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        java.net.URL url = (java.net.URL) param.thisObject;
                        String host = url.getHost();
                        if (isBlockedDomain(host)) {
                            Log.e(TAG, "URL.openConnection BLOCKED: " + url);
                            param.setThrowable(new java.io.IOException("Blocked by KMV Bypass: " + host));
                        }
                    }
                }
            );
            Log.e(TAG, "URL.openConnection blocking installed");
        } catch (Throwable t) {
            Log.e(TAG, "URL.openConnection hook failed: " + t.getMessage());
        }

        // Também bloquear com Proxy
        try {
            XposedHelpers.findAndHookMethod(
                java.net.URL.class,
                "openConnection",
                java.net.Proxy.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        java.net.URL url = (java.net.URL) param.thisObject;
                        String host = url.getHost();
                        if (isBlockedDomain(host)) {
                            Log.e(TAG, "URL.openConnection(Proxy) BLOCKED: " + url);
                            param.setThrowable(new java.io.IOException("Blocked by KMV Bypass: " + host));
                        }
                    }
                }
            );
        } catch (Throwable t) {
            // Silenciar
        }
    }

    /**
     * CAMADA 8 — Advertising ID (GAID) spoof
     * O Magnes pode ler o Google Advertising ID.
     */
    private void hookAdvertisingId(LoadPackageParam lpparam) {
        String fakeGaid = UUID.randomUUID().toString();

        // AdvertisingIdClient.Info.getId()
        try {
            XposedHelpers.findAndHookMethod(
                "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info",
                lpparam.classLoader,
                "getId",
                XC_MethodReplacement.returnConstant(fakeGaid)
            );
            Log.e(TAG, "AdvertisingIdClient.Info.getId() -> " + fakeGaid);
        } catch (Throwable t) {
            // Classe pode não existir
        }

        // AdvertisingIdClient.getAdvertisingIdInfo() — retornar Info com ID fake
        try {
            Class<?> infoClass = XposedHelpers.findClass(
                "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                "com.google.android.gms.ads.identifier.AdvertisingIdClient",
                lpparam.classLoader,
                "getAdvertisingIdInfo",
                "android.content.Context",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // O resultado já terá o getId() hookado acima
                    }
                }
            );
        } catch (Throwable t) {
            // Silenciar
        }

        Log.e(TAG, "Advertising ID hooks installed");
    }
}
