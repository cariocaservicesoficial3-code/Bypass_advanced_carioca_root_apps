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
 * v1.5.1 — DNS SINKHOLE + HTTP BLOCKING + ID ROTATION + X-MOBILE UUID
 *
 * Diagnóstico pós v1.5.0 (hj.har):
 *   - c.paypal.com BLOQUEADO com sucesso (0 requisições Magnes)
 *   - d.viewpkg.com AINDA ATIVO (18 requisições) — OkHttp hook não pegou
 *     porque ViewPkg usa classloader/instância OkHttp diferente
 *   - UUID no header x-mobile FIXO: 870949b0-2a4b-4a70-...(hash)
 *   - Incognia ainda ativo (service2/4.br.incognia.com)
 *   - AppsFlyer enviando 66 requisições com fingerprint
 *
 * Estratégia v1.5.1:
 *   1. DNS SINKHOLE: InetAddress.getByName/getAllByName → 127.0.0.1 para domínios bloqueados
 *      (impossível de bypassar — funciona para QUALQUER client HTTP)
 *   2. HTTP BLOCKING: OkHttpClient.newCall() mantido como camada extra
 *   3. X-MOBILE HEADER: Interceptar OkHttp Request.Builder.addHeader/header para rotacionar UUID
 *   4. SharedPreferences: Limpar prefs do Magnes
 *   5. GSF ID + Android ID: Spoof completo
 *   6. Device Uptime: Offset aleatório
 *   7. Magnes SDK: Neutralizar coleta
 *   8. URL.openConnection: Fallback blocking
 *   9. Advertising ID: Spoof GAID
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
    private static final String SPOOFED_UUID_HASH;

    // Domínios a bloquear via DNS sinkhole + HTTP
    private static final Set<String> BLOCKED_DOMAINS = new HashSet<>(Arrays.asList(
        "c.paypal.com",
        "d.viewpkg.com",
        "b.stats.paypal.com",
        "t.paypal.com",
        "www.paypalobjects.com",
        "api-m.paypal.com"
    ));

    // Domínios de telemetria adicionais a bloquear
    private static final Set<String> TELEMETRY_DOMAINS = new HashSet<>(Arrays.asList(
        "service2.br.incognia.com",
        "service3.br.incognia.com",
        "service4.br.incognia.com",
        "idf-api.serasaexperian.com.br"
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
        SPOOFED_UPDATE_TIME = SPOOFED_INSTALL_TIME + (long)(RNG.nextDouble() * (now - SPOOFED_INSTALL_TIME));
        SPOOFED_UPTIME_OFFSET = (1 + (long)(RNG.nextDouble() * 6)) * 86400000L;
        // UUID composto para x-mobile: UUID + hash SHA1-like
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
        Log.e(TAG, "PersistentIdHooks v1.5.1 starting. NEW IDENTITY:");
        Log.e(TAG, "  APP_GUID     = " + SPOOFED_APP_GUID);
        Log.e(TAG, "  MAGNES_GUID  = " + SPOOFED_MAGNES_GUID);
        Log.e(TAG, "  GSF_ID       = " + SPOOFED_GSF_ID);
        Log.e(TAG, "  ANDROID_ID   = " + SPOOFED_ANDROID_ID);
        Log.e(TAG, "  INSTALL_TIME = " + SPOOFED_INSTALL_TIME);
        Log.e(TAG, "  UUID         = " + SPOOFED_UUID);
        Log.e(TAG, "  UUID_HASH    = " + SPOOFED_UUID_HASH);

        hookDnsSinkhole(lpparam);           // CAMADA 1 — DNS sinkhole (impossível bypassar)
        hookOkHttpBlocking(lpparam);        // CAMADA 2 — OkHttp blocking (backup)
        hookXMobileHeader(lpparam);         // CAMADA 3 — x-mobile UUID rotation
        hookSharedPreferences(lpparam);     // CAMADA 4 — SharedPreferences
        hookGsfId(lpparam);                 // CAMADA 5 — GSF ID
        hookAndroidId(lpparam);             // CAMADA 6 — Android ID
        hookUptimeSpoof(lpparam);           // CAMADA 7 — Device Uptime
        hookMagnesCollectAndSubmit(lpparam); // CAMADA 8 — Magnes SDK
        hookUrlConnectionBlocking(lpparam); // CAMADA 9 — URL.openConnection
        hookAdvertisingId(lpparam);         // CAMADA 10 — Advertising ID
    }

    /**
     * CAMADA 1 — DNS SINKHOLE
     * Intercepta InetAddress.getByName() e getAllByName() para resolver domínios
     * bloqueados para 127.0.0.1. Isso é IMPOSSÍVEL de bypassar porque QUALQUER
     * client HTTP (OkHttp, HttpURLConnection, etc.) precisa resolver DNS primeiro.
     */
    private void hookDnsSinkhole(LoadPackageParam lpparam) {
        Set<String> allBlocked = new HashSet<>();
        allBlocked.addAll(BLOCKED_DOMAINS);
        allBlocked.addAll(TELEMETRY_DOMAINS);

        // Hook InetAddress.getByName(String host)
        try {
            XposedHelpers.findAndHookMethod(
                InetAddress.class,
                "getByName",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String host = (String) param.args[0];
                        if (host != null && isBlockedDomain(host, allBlocked)) {
                            Log.e(TAG, "DNS SINKHOLE getByName: " + host + " → 127.0.0.1");
                            param.setResult(InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1}));
                        }
                    }
                }
            );
            Log.e(TAG, "DNS sinkhole InetAddress.getByName() installed");
        } catch (Throwable t) {
            Log.e(TAG, "DNS getByName hook failed: " + t.getMessage());
        }

        // Hook InetAddress.getAllByName(String host)
        try {
            XposedHelpers.findAndHookMethod(
                InetAddress.class,
                "getAllByName",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String host = (String) param.args[0];
                        if (host != null && isBlockedDomain(host, allBlocked)) {
                            Log.e(TAG, "DNS SINKHOLE getAllByName: " + host + " → [127.0.0.1]");
                            InetAddress loopback = InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1});
                            param.setResult(new InetAddress[]{loopback});
                        }
                    }
                }
            );
            Log.e(TAG, "DNS sinkhole InetAddress.getAllByName() installed");
        } catch (Throwable t) {
            Log.e(TAG, "DNS getAllByName hook failed: " + t.getMessage());
        }

        Log.e(TAG, "DNS SINKHOLE: " + allBlocked.size() + " domains will resolve to 127.0.0.1");
    }

    /**
     * CAMADA 2 — HTTP BLOCKING via OkHttpClient.newCall()
     */
    private void hookOkHttpBlocking(LoadPackageParam lpparam) {
        try {
            Class<?> okHttpClient = XposedHelpers.findClass("okhttp3.OkHttpClient", lpparam.classLoader);

            XposedBridge.hookAllMethods(okHttpClient, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0 && param.args[0] != null) {
                        Object request = param.args[0];
                        Object urlObj = XposedHelpers.callMethod(request, "url");
                        String url = urlObj.toString();
                        String host = extractHost(url, urlObj);

                        Set<String> allBlocked = new HashSet<>();
                        allBlocked.addAll(BLOCKED_DOMAINS);
                        allBlocked.addAll(TELEMETRY_DOMAINS);

                        if (isBlockedDomain(host, allBlocked)) {
                            Log.e(TAG, "HTTP BLOCKED newCall: " + url);
                            param.setThrowable(new java.io.IOException("Blocked by KMV Bypass: " + host));
                        }
                    }
                }
            });
            Log.e(TAG, "OkHttpClient.newCall() HTTP blocking installed");
        } catch (Throwable t) {
            Log.e(TAG, "OkHttpClient.newCall hook failed: " + t.getMessage());
        }

        // Backup: RealCall
        String[] realCallClasses = {
            "okhttp3.internal.connection.RealCall",
            "okhttp3.RealCall"
        };
        for (String clsName : realCallClasses) {
            try {
                Class<?> realCall = XposedHelpers.findClass(clsName, lpparam.classLoader);
                for (String method : new String[]{"execute", "enqueue"}) {
                    hookRealCallMethod(realCall, method, lpparam);
                }
                Log.e(TAG, "RealCall blocking installed via " + clsName);
                break;
            } catch (Throwable t) {
                // Tentar próxima classe
            }
        }
    }

    private void hookRealCallMethod(Class<?> realCallClass, String methodName, LoadPackageParam lpparam) {
        try {
            Set<String> allBlocked = new HashSet<>();
            allBlocked.addAll(BLOCKED_DOMAINS);
            allBlocked.addAll(TELEMETRY_DOMAINS);

            XposedBridge.hookAllMethods(realCallClass, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object request = XposedHelpers.callMethod(param.thisObject, "request");
                        Object urlObj = XposedHelpers.callMethod(request, "url");
                        String host = extractHost(urlObj.toString(), urlObj);
                        if (isBlockedDomain(host, allBlocked)) {
                            Log.e(TAG, "RealCall." + methodName + " BLOCKED: " + host);
                            if ("execute".equals(methodName)) {
                                param.setThrowable(new java.io.IOException("Blocked by KMV Bypass"));
                            } else {
                                param.setResult(null);
                            }
                        }
                    } catch (Throwable t) {
                        // Silenciar
                    }
                }
            });
        } catch (Throwable t) {
            // Silenciar
        }
    }

    private String extractHost(String url, Object urlObj) {
        try {
            return (String) XposedHelpers.callMethod(urlObj, "host");
        } catch (Throwable t) {
            try {
                if (url.contains("://")) {
                    return url.split("://")[1].split("/")[0].split(":")[0];
                }
            } catch (Throwable t2) {
                // Silenciar
            }
        }
        return "";
    }

    /**
     * CAMADA 3 — X-MOBILE HEADER UUID ROTATION
     * O header x-mobile contém um UUID fixo que identifica o dispositivo.
     * Formato: MARCA=POCO,...,UUID=<uuid><hash>,deviceFingerprintSessionId=<session>
     * Interceptamos o Request.Builder para substituir o UUID.
     */
    private void hookXMobileHeader(LoadPackageParam lpparam) {
        // Hook no OkHttp Request.Builder.addHeader() e header()
        String[] methods = {"addHeader", "header"};
        for (String methodName : methods) {
            try {
                Class<?> builderClass = XposedHelpers.findClass("okhttp3.Request$Builder", lpparam.classLoader);
                XposedHelpers.findAndHookMethod(builderClass, methodName,
                    String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String name = (String) param.args[0];
                            String value = (String) param.args[1];
                            if (name != null && value != null && "x-mobile".equalsIgnoreCase(name)) {
                                String newValue = rewriteXMobileHeader(value);
                                if (!newValue.equals(value)) {
                                    param.args[1] = newValue;
                                    Log.e(TAG, "x-mobile header UUID ROTATED");
                                }
                            }
                        }
                    }
                );
                Log.e(TAG, "Request.Builder." + methodName + "() x-mobile hook installed");
            } catch (Throwable t) {
                Log.e(TAG, "Request.Builder." + methodName + " hook failed: " + t.getMessage());
            }
        }

        // Também hook no HttpURLConnection.setRequestProperty
        try {
            XposedHelpers.findAndHookMethod(
                java.net.HttpURLConnection.class,
                "setRequestProperty",
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
        } catch (Throwable t) {
            // Silenciar
        }
    }

    private String rewriteXMobileHeader(String value) {
        // Formato: ...UUID=<uuid><hash>,...
        // O UUID original é: 870949b0-2a4b-4a70-9f8d-9c80a1bb433a + 40684ca1383bd79201f005ce8b246e755e41b1e6
        // Substituir por nosso UUID + hash
        if (value.contains("UUID=")) {
            // Extrair a parte antes e depois do UUID
            int uuidStart = value.indexOf("UUID=") + 5;
            int uuidEnd = value.indexOf(",", uuidStart);
            if (uuidEnd == -1) uuidEnd = value.length();

            String newUuid = SPOOFED_UUID.replace("-", "") + "-" +
                SPOOFED_UUID.substring(0, 4) + "-" +
                SPOOFED_UUID.substring(4, 8) + "-" +
                SPOOFED_UUID.substring(8, 12) + "-" +
                SPOOFED_UUID_HASH;

            // Formato mais simples: UUID padrão + hash hex
            newUuid = SPOOFED_UUID + SPOOFED_UUID_HASH;

            value = value.substring(0, uuidStart) + newUuid + value.substring(uuidEnd);
            Log.e(TAG, "x-mobile UUID rewritten to: " + newUuid.substring(0, 20) + "...");
        }
        return value;
    }

    /**
     * CAMADA 4 — SharedPreferences Interception
     */
    private void hookSharedPreferences(LoadPackageParam lpparam) {
        try {
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

        // Hook getString para retornar valores spoofados
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
        return lower.contains("magnes") || lower.contains("paypal") || lower.contains("rda")
            || lower.contains("riskmanager") || lower.contains("viewpkg");
    }

    /**
     * CAMADA 5 — GSF ID (Google Services Framework)
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
        } catch (Throwable t) {
            Log.e(TAG, "Settings.Secure hook failed: " + t.getMessage());
        }

        // Block ContentResolver.query for GSF
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
        } catch (Throwable t) {
            Log.e(TAG, "ContentResolver.query hook failed: " + t.getMessage());
        }
    }

    /**
     * CAMADA 6 — Android ID reforçado
     */
    private void hookAndroidId(LoadPackageParam lpparam) {
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
            // Silenciar
        }

        try {
            String spoofedSerial = "POCO" + randomHex(12).toUpperCase();
            XposedHelpers.setStaticObjectField(Build.class, "SERIAL", spoofedSerial);
            Log.e(TAG, "Build.SERIAL -> " + spoofedSerial);
        } catch (Throwable t) {
            Log.e(TAG, "Build.SERIAL spoof failed: " + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(Build.class, "getSerial",
                XC_MethodReplacement.returnConstant("POCO" + randomHex(12).toUpperCase()));
        } catch (Throwable t) {
            // Silenciar
        }
    }

    /**
     * CAMADA 7 — Device Uptime Spoof
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
     * CAMADA 8 — Magnes collectAndSubmit interception
     */
    private void hookMagnesCollectAndSubmit(LoadPackageParam lpparam) {
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
                        } catch (Throwable t) {
                            // Silenciar
                        }
                    }
                }
                if (hooked > 0) {
                    Log.e(TAG, "Magnes class " + clsName + ": " + hooked + " methods neutralized");
                }
            } catch (Throwable t) {
                // Classe não encontrada
            }
        }

        // Hook na classe C do Magnes — JSONObject methods
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
     * CAMADA 9 — URL.openConnection blocking
     */
    private void hookUrlConnectionBlocking(LoadPackageParam lpparam) {
        Set<String> allBlocked = new HashSet<>();
        allBlocked.addAll(BLOCKED_DOMAINS);
        allBlocked.addAll(TELEMETRY_DOMAINS);

        try {
            XposedHelpers.findAndHookMethod(
                java.net.URL.class,
                "openConnection",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        java.net.URL url = (java.net.URL) param.thisObject;
                        String host = url.getHost();
                        if (isBlockedDomain(host, allBlocked)) {
                            Log.e(TAG, "URL.openConnection BLOCKED: " + url);
                            param.setThrowable(new java.io.IOException("Blocked by KMV Bypass: " + host));
                        }
                    }
                }
            );
        } catch (Throwable t) {
            Log.e(TAG, "URL.openConnection hook failed: " + t.getMessage());
        }

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
                        if (isBlockedDomain(host, allBlocked)) {
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
     * CAMADA 10 — Advertising ID spoof
     */
    private void hookAdvertisingId(LoadPackageParam lpparam) {
        String fakeGaid = UUID.randomUUID().toString();

        try {
            XposedHelpers.findAndHookMethod(
                "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info",
                lpparam.classLoader,
                "getId",
                XC_MethodReplacement.returnConstant(fakeGaid)
            );
            Log.e(TAG, "AdvertisingIdClient.Info.getId() -> " + fakeGaid);
        } catch (Throwable t) {
            // Silenciar
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.google.android.gms.ads.identifier.AdvertisingIdClient",
                lpparam.classLoader,
                "getAdvertisingIdInfo",
                "android.content.Context",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // getId() já está hookado acima
                    }
                }
            );
        } catch (Throwable t) {
            // Silenciar
        }
    }

    private static boolean isBlockedDomain(String host, Set<String> blockedSet) {
        if (host == null) return false;
        host = host.toLowerCase();
        for (String blocked : blockedSet) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) {
                return true;
            }
        }
        return false;
    }
}
