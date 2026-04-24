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
 * v1.5.7 — DNS SINKHOLE FIX + UUID IMORTAL BYPASS (FONTE) + OKHTTP REQUEST BUILDER
 *
 * ============================================================
 * DIAGNÓSTICO COMPLETO (engenharia reversa APK 4.83.101):
 * ============================================================
 *
 * CRASH (ClassCastException em getAllByName):
 *   - InetAddress.getAllByName() retorna InetAddress[] (array)
 *   - O hook v1.5.6 retornava InetAddress (singular) → ClassCastException
 *   - CORREÇÃO: param.setResult(new InetAddress[]{...})
 *
 * UUID IMORTAL (870949b0-... nunca substituído):
 *   - Origem: C0415r.m1865D(Context)
 *     → lê "PREF_UNIQUE_ID" do SharedPreferences "PREF_UNIQUE_ID"
 *     → concatena sufixo fixo "40684ca1383bd79201f005ce8b246e755e41b1e6"
 *   - Montagem: C0201g.m1226m(Map) chama c0415r.m1898v(context) que chama m1865D()
 *     → constrói string: "MARCA=...,UUID=<uuid>,deviceFingerprintSessionId=..."
 *     → codifica com C0395m.m1796o() (apenas filtra chars especiais)
 *     → coloca no header "x-mobile"
 *   - O hook v1.5.6 interceptava OkHttp Headers.get() (leitura) mas o UUID
 *     já estava montado na string ANTES de chegar no OkHttp
 *   - CORREÇÃO: Hook na FONTE — SharedPreferences "PREF_UNIQUE_ID" e Device.getUuid()
 *
 * REPROVED no check-status:
 *   - O processId PSF-fde2f3c9 foi reprovado em sessão anterior com UUID imortal
 *   - Com o UUID substituído, o novo processo terá um device ID diferente
 *   - O Zaig/backend não terá histórico negativo para o novo UUID
 *
 * deviceFingerprintSessionId:
 *   - Gerado em ZaigManager (C0295S.m1440d): UUID.randomUUID() + "-" + timestamp
 *   - Já é aleatório por sessão — não precisa de hook
 *
 * ============================================================
 * ESTRATÉGIA v1.5.7:
 * ============================================================
 *   1. DNS SINKHOLE FIX: getAllByName() retorna InetAddress[] (ARRAY)
 *   2. UUID SOURCE HOOK: SharedPreferences "PREF_UNIQUE_ID" → UUID fake
 *   3. UUID SOURCE HOOK: C0415r.m1865D() → UUID fake + hash fake
 *   4. UUID SOURCE HOOK: Device.getUuid() → UUID fake (fallback)
 *   5. OkHttp Request.Builder.addHeader/header → substituição de segurança
 *   6. HttpURLConnection: mantido da v1.5.6
 *   7. OkHttp Headers.get: mantido como última camada
 */
public class PersistentIdHooks {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String TAG = MainHook.TAG_LOG;

    // UUID imortal que o app persiste no SharedPreferences
    private static final String BAD_UUID_PART = "870949b0-2a4b-4a70-9f8d-9c80a1bb433a";
    // Sufixo fixo que o app concatena ao UUID (hash hardcoded no APK)
    private static final String BAD_UUID_SUFFIX = "40684ca1383bd79201f005ce8b246e755e41b1e6";

    private static final String SPOOFED_ANDROID_ID = randomHex(16);
    private static final String SPOOFED_SERIAL = "POCO" + randomHex(12).toUpperCase();
    private static final long SPOOFED_UPTIME_OFFSET;
    // UUID que substitui o PREF_UNIQUE_ID no SharedPreferences
    private static final String SPOOFED_PREF_UUID;
    // Hash que substitui o sufixo fixo "40684ca1383bd79201f005ce8b246e755e41b1e6"
    private static final String SPOOFED_UUID_HASH;
    // UUID completo que vai no header x-mobile (PREF_UUID + HASH)
    private static final String SPOOFED_FULL_UUID;
    // UUID para substituição de segurança no header (caso o hook de fonte falhe)
    private static final String SPOOFED_HEADER_UUID;

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
        SPOOFED_HEADER_UUID = UUID.randomUUID().toString();
    }

    private static String randomHex(int chars) {
        byte[] b = new byte[(chars + 1) / 2];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.substring(0, chars);
    }

    public void install(LoadPackageParam lpparam) {
        Log.e(TAG, "PersistentIdHooks v1.5.7 starting.");
        Log.e(TAG, "SPOOFED_PREF_UUID: " + SPOOFED_PREF_UUID);
        Log.e(TAG, "SPOOFED_FULL_UUID: " + SPOOFED_FULL_UUID.substring(0, 16) + "...");

        // ORDEM CRÍTICA: hooks de fonte ANTES dos hooks de header
        hookDnsSinkhole(lpparam);            // FIX CRÍTICO: ClassCastException em getAllByName
        hookSharedPreferencesUuid(lpparam);  // FONTE: interceptar PREF_UNIQUE_ID na leitura
        hookUuidSourceClass(lpparam);        // FONTE: interceptar C0415r.m1865D()
        hookDeviceGetUuid(lpparam);          // FONTE: interceptar Device.getUuid()
        hookOkHttpRequestBuilder(lpparam);   // SEGURANÇA: Request.Builder.addHeader/header
        hookHttpURLConnection(lpparam);      // SEGURANÇA: Java Native Networking
        hookOkHttpStable(lpparam);           // SEGURANÇA: OkHttp Headers.get (última camada)
        hookSharedPreferencesClean(lpparam); // LIMPEZA: prefs de telemetria
        hookGsfId(lpparam);
        hookAndroidId(lpparam);
        hookUptimeSpoof(lpparam);
        hookMagnesCollectAndSubmit(lpparam);
        hookUrlConnectionBlocking(lpparam);
    }

    // ==================== CAMADA 0 — DNS SINKHOLE (FIX CRÍTICO) ====================
    private void hookDnsSinkhole(LoadPackageParam lpparam) {
        try {
            // getByName retorna InetAddress (singular) — OK
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

            // getAllByName retorna InetAddress[] (ARRAY) — CORREÇÃO DO CRASH v1.5.6
            XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String host = (String) param.args[0];
                    if (host != null && isBlockedDomain(host)) {
                        Log.e(TAG, "DNS SINKHOLE (getAllByName): " + host);
                        // CRÍTICO: retornar InetAddress[] não InetAddress
                        param.setResult(new InetAddress[]{InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1})});
                    }
                }
            });
            Log.e(TAG, "[OK] DNS Sinkhole v1.5.7 (getAllByName fix)");
        } catch (Throwable t) {
            Log.e(TAG, "[FAIL] DNS Sinkhole: " + t.getMessage());
        }
    }

    private static boolean isBlockedDomain(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        for (String b : BLOCKED_DOMAINS) { if (host.contains(b)) return true; }
        return host.endsWith(".appsflyersdk.com");
    }

    // ==================== CAMADA 1 — PREF_UNIQUE_ID HOOK (FONTE DO UUID) ====================
    // O app lê "PREF_UNIQUE_ID" do SharedPreferences "PREF_UNIQUE_ID" em C0415r.m1865D()
    private void hookSharedPreferencesUuid(LoadPackageParam lpparam) {
        try {
            // Hook na implementação interna do Android SharedPreferences
            XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", lpparam.classLoader,
                "getString", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if ("PREF_UNIQUE_ID".equals(key)) {
                        param.setResult(SPOOFED_PREF_UUID);
                        Log.e(TAG, "SharedPrefs PREF_UNIQUE_ID -> SPOOFED: " + SPOOFED_PREF_UUID.substring(0, 8) + "...");
                    }
                }
            });
            Log.e(TAG, "[OK] PREF_UNIQUE_ID hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "[FAIL] PREF_UNIQUE_ID hook: " + t.getMessage());
        }
    }

    // ==================== CAMADA 2 — C0415r.m1865D() HOOK (FONTE DO UUID) ====================
    // m1865D() lê PREF_UNIQUE_ID e concatena o sufixo "40684ca1..."
    private void hookUuidSourceClass(LoadPackageParam lpparam) {
        // Hook 1: Tentar pelo nome obfuscado m1865D
        try {
            Class<?> utilClass = XposedHelpers.findClass(
                "com.gigigo.ipirangaconectcar.support.util.C0415r", lpparam.classLoader);
            for (Method m : utilClass.getDeclaredMethods()) {
                if (m.getName().equals("m1865D") || m.getName().equals("D")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            Log.e(TAG, "C0415r.m1865D() -> FULL UUID REPLACED: " + SPOOFED_FULL_UUID.substring(0, 16) + "...");
                            return SPOOFED_FULL_UUID;
                        }
                    });
                    Log.e(TAG, "[OK] C0415r.m1865D() UUID source hooked");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "[FAIL] C0415r UUID source hook: " + t.getMessage());
        }

        // Hook 2: Tentar pelo nome original "D" (fallback para versões sem deobf)
        try {
            Class<?> utilClass = XposedHelpers.findClass(
                "com.gigigo.ipirangaconectcar.support.util.C0415r", lpparam.classLoader);
            // Procurar método que retorna String e tem Context como argumento
            for (Method m : utilClass.getDeclaredMethods()) {
                if (m.getReturnType() == String.class
                    && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].getName().contains("Context")) {
                    // Verificar se o método contém a string de sufixo (via análise do bytecode)
                    // Não podemos fazer isso diretamente, então hookamos todos os métodos
                    // que retornam String com Context como argumento
                    final String methodName = m.getName();
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            String result = (String) param.getResult();
                            if (result != null && result.contains(BAD_UUID_PART)) {
                                String newResult = result.replace(BAD_UUID_PART, SPOOFED_PREF_UUID);
                                newResult = newResult.replace(BAD_UUID_SUFFIX, SPOOFED_UUID_HASH);
                                param.setResult(newResult);
                                Log.e(TAG, "C0415r." + methodName + "() -> UUID replaced in result");
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "[FAIL] C0415r fallback hook: " + t.getMessage());
        }
    }

    // ==================== CAMADA 3 — Device.getUuid() HOOK ====================
    private void hookDeviceGetUuid(LoadPackageParam lpparam) {
        try {
            Class<?> deviceClass = XposedHelpers.findClass(
                "com.gigigo.ipirangaconectcar.support.util.Device", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(deviceClass, "getUuid", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    Log.e(TAG, "Device.getUuid() -> FULL UUID REPLACED");
                    return SPOOFED_FULL_UUID;
                }
            });
            Log.e(TAG, "[OK] Device.getUuid() hooked");
        } catch (Throwable t) {
            Log.e(TAG, "[FAIL] Device.getUuid() hook: " + t.getMessage());
        }
    }

    // ==================== CAMADA 4 — OKHTTP REQUEST BUILDER HOOK ====================
    private void hookOkHttpRequestBuilder(LoadPackageParam lpparam) {
        try {
            Class<?> builderClass = XposedHelpers.findClass("okhttp3.Request$Builder", lpparam.classLoader);

            // addHeader(String, String)
            XposedHelpers.findAndHookMethod(builderClass, "addHeader", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[0];
                    String value = (String) param.args[1];
                    if ("x-mobile".equalsIgnoreCase(name) && value != null && value.contains(BAD_UUID_PART)) {
                        param.args[1] = value.replace(BAD_UUID_PART, SPOOFED_PREF_UUID)
                                              .replace(BAD_UUID_SUFFIX, SPOOFED_UUID_HASH);
                        Log.e(TAG, "OkHttp Request.Builder.addHeader('x-mobile'): UUID REPLACED");
                    }
                }
            });

            // header(String, String)
            XposedHelpers.findAndHookMethod(builderClass, "header", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[0];
                    String value = (String) param.args[1];
                    if ("x-mobile".equalsIgnoreCase(name) && value != null && value.contains(BAD_UUID_PART)) {
                        param.args[1] = value.replace(BAD_UUID_PART, SPOOFED_PREF_UUID)
                                              .replace(BAD_UUID_SUFFIX, SPOOFED_UUID_HASH);
                        Log.e(TAG, "OkHttp Request.Builder.header('x-mobile'): UUID REPLACED");
                    }
                }
            });
            Log.e(TAG, "[OK] OkHttp Request.Builder hooks installed");
        } catch (Throwable t) {
            Log.e(TAG, "[FAIL] OkHttp Request.Builder hook: " + t.getMessage());
        }
    }

    // ==================== CAMADA 5 — HttpURLConnection HOOK ====================
    private void hookHttpURLConnection(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(URLConnection.class, "setRequestProperty", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    String value = (String) param.args[1];
                    if ("x-mobile".equalsIgnoreCase(key) && value != null && value.contains(BAD_UUID_PART)) {
                        param.args[1] = value.replace(BAD_UUID_PART, SPOOFED_PREF_UUID)
                                             .replace(BAD_UUID_SUFFIX, SPOOFED_UUID_HASH);
                        Log.e(TAG, "URLConnection.setRequestProperty('x-mobile'): UUID REPLACED");
                    }
                }
            });

            XposedHelpers.findAndHookMethod(HttpURLConnection.class, "getRequestProperty", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    String value = (String) param.getResult();
                    if ("x-mobile".equalsIgnoreCase(key) && value != null && value.contains(BAD_UUID_PART)) {
                        param.setResult(value.replace(BAD_UUID_PART, SPOOFED_PREF_UUID)
                                            .replace(BAD_UUID_SUFFIX, SPOOFED_UUID_HASH));
                    }
                }
            });
        } catch (Throwable t) { Log.e(TAG, "HttpURLConnection hook failed: " + t.getMessage()); }
    }

    // ==================== CAMADA 6 — OKHTTP STABLE HOOKS (ÚLTIMA CAMADA) ====================
    private void hookOkHttpStable(LoadPackageParam lpparam) {
        try {
            Class<?> headersClass = XposedHelpers.findClass("okhttp3.Headers", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(headersClass, "get", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[0];
                    String value = (String) param.getResult();
                    if ("x-mobile".equalsIgnoreCase(name) && value != null && value.contains(BAD_UUID_PART)) {
                        param.setResult(value.replace(BAD_UUID_PART, SPOOFED_PREF_UUID)
                                            .replace(BAD_UUID_SUFFIX, SPOOFED_UUID_HASH));
                        Log.e(TAG, "OkHttp Headers.get('x-mobile'): UUID REPLACED (last resort)");
                    }
                }
            });
        } catch (Throwable t) {}
    }

    // ==================== IDs & CRASH FIX (AllowMe) ====================
    private void hookAndroidId(LoadPackageParam lpparam) {
        try {
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

    private void hookSharedPreferencesClean(LoadPackageParam lpparam) {
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
