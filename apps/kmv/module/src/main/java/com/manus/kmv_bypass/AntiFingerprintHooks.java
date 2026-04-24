package com.manus.kmv_bypass;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Method;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v1.3 — Correção cirúrgica após diagnóstico:
 *   A v1.2 falhou porque o hook em `magnessdk.c.a()/.b()` só modifica o DTO retornado ao caller,
 *   mas o HTTP POST para c.paypal.com JÁ FOI FEITO antes. Precisamos atacar o coletor real.
 *   Também `Ba.b.c()` não é o método que envia — é só um logger Datadog.
 *
 * Agora atacamos:
 *   1. **lib.android.paypal.com.magnessdk.C.x(...)** (7 args) — gera o JSONObject final.
 *      Hook retorna JSONObject vazio → c.paypal.com recebe `{}`.
 *   2. **Ba.b.b(JSONObject)** — é o método que realmente POSTa para d.viewpkg.com via OkHttp.
 *      Hook retorna void → nenhum POST é enviado.
 *   3. **NetworkInterface.getNetworkInterfaces()** — filtra tun*, ppp*, ipsec* globalmente.
 *      Neutraliza VPN_setting=tun0 e ip_addresses VPN (dispara qualquer anti-fraude).
 *   4. PackageManager filter (mantido da v1.2).
 */
public class AntiFingerprintHooks {

    private static final Set<String> SUSPICIOUS_PACKAGES = new HashSet<>(Arrays.asList(
        // Magisk
        "com.topjohnwu.magisk", "io.github.huskydg.magisk", "com.android.magisk",
        // LSPosed/Xposed
        "org.lsposed.manager", "org.lsposed.lspatch", "org.lsposed.lspd",
        "de.robv.android.xposed.installer", "io.va.exposed", "io.va.exposed64",
        "com.solohsu.android.edxp.manager", "org.meowcat.edxposed.manager",
        // Frida / hooks
        "re.frida.server",
        // Riru / Zygisk
        "moe.shizuku.riru",
        // Custom ROM helpers / hacking tools
        "ru.maximoff.apktool", "com.guoshi.httpcanary", "app.greyshirts.sslcapture",
        "com.android.vending.billing.InAppBillingService.LACK", "com.chelpus.luckypatcher",
        "com.dimonvideo.luckypatcher",
        // Bridges/SuperSU
        "eu.chainfire.supersu", "com.koushikdutta.superuser", "com.noshufou.android.su",
        "com.thirdparty.superuser", "com.zachspong.temprootremovejb",
        "com.ramdroid.appquarantine",
        // Hide tools
        "com.devadvance.rootcloak", "com.devadvance.rootcloakplus",
        "com.formyhm.hideroot", "com.formyhm.hiderootPremium",
        "com.amphoras.hidemyroot", "com.amphoras.hidemyrootadfree",
        // Sniffers / proxies
        "com.guoshi.httpcanary.premium", "com.ylhyh.httpcanary",
        "com.minhui.networkcapture", "jp.co.taosoftware.android.packetcapture",
        "com.egorovandreyrm.pcapremote", "app.greyshirts.firewall",
        // Reqable / packet sniffers modernos
        "com.reqable.android", "com.reqable",
        "com.evbadroid.proxymonclassic", "com.evbadroid.proxymon"
    ));

    private static final String[] VPN_INTERFACE_PREFIXES = {
        "tun", "tap", "ppp", "ipsec", "utun", "vxlan"
    };

    public void install(LoadPackageParam lpparam) {
        hookPayPalMagnesReal(lpparam);
        hookViewPkgReal(lpparam);
        hookNetworkInterfaceGlobal(lpparam);
        hookPackageManagerFilter(lpparam);
    }

    /**
     * CAMADA 1 — PayPal Magnes ATACA REAL
     *
     * A classe `lib.android.paypal.com.magnessdk.C` (em smali_classes9) tem:
     *   - x(e, w, x, String, String, HashMap, Handler) : JSONObject → GERADOR FINAL
     *   - d() : JSONObject
     *   - A() : List (network interfaces)
     *   - L(Context) : JSONObject
     *   - t(WifiManager) : ArrayList (MAC addrs)
     *
     * Hookando x() retornando JSONObject vazio, o payload que vai pro POST é "{}".
     */
    private void hookPayPalMagnesReal(LoadPackageParam lpparam) {
        // 1) Hook no gerador do JSONObject final (C.x com 7 args)
        try {
            Class<?> clsC = XposedHelpers.findClass("lib.android.paypal.com.magnessdk.C", lpparam.classLoader);
            int hooked = 0;
            for (Method m : clsC.getDeclaredMethods()) {
                if (m.getName().equals("x") && m.getParameterTypes().length == 7 && m.getReturnType().getName().equals("org.json.JSONObject")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            try {
                                org.json.JSONObject j = new org.json.JSONObject();
                                j.put("is_rooted", false);
                                j.put("is_emulator", false);
                                j.put("app_id", "com.gigigo.ipirangaconectcar");
                                j.put("os_type", "Android");
                                j.put("os_version", "11");
                                return j;
                            } catch (Throwable t) {
                                return null;
                            }
                        }
                    });
                    MainHook.log("Magnes C.x(7args) NEUTRALIZED -> minimal JSON");
                    hooked++;
                }
            }
            if (hooked == 0) MainHook.log("Magnes C.x(7args) NOT FOUND (class changed?)");
        } catch (Throwable t) {
            MainHook.log("Magnes C.x() hook failed: " + t.getMessage());
        }

        // 2) Hook em C.d() → retorna JSONObject vazio (fallback adicional)
        try {
            XposedHelpers.findAndHookMethod(
                "lib.android.paypal.com.magnessdk.C",
                lpparam.classLoader,
                "d",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return new org.json.JSONObject();
                    }
                }
            );
            MainHook.log("Magnes C.d() -> empty JSONObject");
        } catch (Throwable t) {
            MainHook.log("Magnes C.d() hook failed: " + t.getMessage());
        }

        // 3) Hook em C.A() → lista vazia (sem network interfaces)
        try {
            XposedHelpers.findAndHookMethod(
                "lib.android.paypal.com.magnessdk.C",
                lpparam.classLoader,
                "A",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return new ArrayList<>();
                    }
                }
            );
            MainHook.log("Magnes C.A() -> empty list (no network interfaces)");
        } catch (Throwable t) {
            MainHook.log("Magnes C.A() hook failed: " + t.getMessage());
        }

        // 4) Hook em C.J(Context) → boolean false (is_rooted check interno)
        try {
            XposedHelpers.findAndHookMethod(
                "lib.android.paypal.com.magnessdk.C",
                lpparam.classLoader,
                "J",
                "android.content.Context",
                XC_MethodReplacement.returnConstant(false)
            );
            MainHook.log("Magnes C.J(Context) -> false (root check)");
        } catch (Throwable t) {
            MainHook.log("Magnes C.J(Context) hook failed: " + t.getMessage());
        }

        // 5) Hook em C.t(WifiManager) → ArrayList vazio (MAC addrs)
        try {
            XposedHelpers.findAndHookMethod(
                "lib.android.paypal.com.magnessdk.C",
                lpparam.classLoader,
                "t",
                "android.net.wifi.WifiManager",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return new ArrayList<>();
                    }
                }
            );
            MainHook.log("Magnes C.t(WifiManager) -> empty ArrayList (no MAC addrs)");
        } catch (Throwable t) {
            MainHook.log("Magnes C.t() hook failed: " + t.getMessage());
        }
    }

    /**
     * CAMADA 2 — ViewPkg REAL: o POST é feito em Ba.b.b(JSONObject).
     * O método Ba.b.c() é só um logger Datadog (inofensivo).
     */
    private void hookViewPkgReal(LoadPackageParam lpparam) {
        // 1) Hook Ba.b.b(JSONObject) → MethodReplacement void (bloqueia POST)
        try {
            XposedHelpers.findAndHookMethod(
                "Ba.b",
                lpparam.classLoader,
                "b",
                "org.json.JSONObject",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        MainHook.log("ViewPkg Ba.b.b(JSONObject) BLOCKED -> no POST to d.viewpkg.com");
                        return null; // void return
                    }
                }
            );
            MainHook.log("ViewPkg Ba.b.b(JSONObject) BLOCKED");
        } catch (Throwable t) {
            MainHook.log("ViewPkg Ba.b.b hook failed: " + t.getMessage());
        }

        // 2) Hook Ba.b.a(JSONObject) synthetic (chama b via try/catch)
        try {
            XposedHelpers.findAndHookMethod(
                "Ba.b",
                lpparam.classLoader,
                "a",
                "org.json.JSONObject",
                XC_MethodReplacement.returnConstant(null)
            );
            MainHook.log("ViewPkg Ba.b.a(JSONObject) synthetic BLOCKED");
        } catch (Throwable t) {
            MainHook.log("ViewPkg Ba.b.a hook failed: " + t.getMessage());
        }
    }

    /**
     * CAMADA 3 — NetworkInterface GLOBAL spoof.
     * Remove VPN interfaces (tun, tap, ppp, ipsec, utun, vxlan) do getNetworkInterfaces().
     * Isso neutraliza VPN detection tanto em Magnes quanto em qualquer outro SDK futuro.
     */
    private void hookNetworkInterfaceGlobal(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                NetworkInterface.class,
                "getNetworkInterfaces",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object r = param.getResult();
                        if (r instanceof Enumeration) {
                            List<NetworkInterface> kept = new ArrayList<>();
                            Enumeration<?> en = (Enumeration<?>) r;
                            while (en.hasMoreElements()) {
                                Object o = en.nextElement();
                                if (o instanceof NetworkInterface) {
                                    NetworkInterface ni = (NetworkInterface) o;
                                    String name = ni.getName().toLowerCase();
                                    boolean suspicious = false;
                                    for (String pref : VPN_INTERFACE_PREFIXES) {
                                        if (name.startsWith(pref)) { suspicious = true; break; }
                                    }
                                    if (!suspicious) kept.add(ni);
                                }
                            }
                            param.setResult(Collections.enumeration(kept));
                            MainHook.log("NetworkInterface.getNetworkInterfaces() filtered ("
                                + kept.size() + " kept)");
                        }
                    }
                }
            );
            MainHook.log("NetworkInterface.getNetworkInterfaces() VPN filter installed");
        } catch (Throwable t) {
            MainHook.log("NetworkInterface filter failed: " + t.getMessage());
        }

        // getByName("tun0") / getByName("tun1") → null
        try {
            XposedHelpers.findAndHookMethod(
                NetworkInterface.class,
                "getByName",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof String) {
                            String n = ((String) param.args[0]).toLowerCase();
                            for (String pref : VPN_INTERFACE_PREFIXES) {
                                if (n.startsWith(pref)) {
                                    param.setResult(null);
                                    return;
                                }
                            }
                        }
                    }
                }
            );
            MainHook.log("NetworkInterface.getByName filter installed");
        } catch (Throwable t) {
            MainHook.log("NetworkInterface.getByName hook failed: " + t.getMessage());
        }
    }

    /**
     * CAMADA 4 — PackageManager filter (mantida da v1.2, ampliada com sniffers).
     */
    private void hookPackageManagerFilter(LoadPackageParam lpparam) {
        try {
            XposedBridge.hookAllMethods(PackageManager.class, "getInstalledPackages", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result instanceof List<?>) {
                        List<PackageInfo> filtered = new ArrayList<>();
                        for (Object o : (List<?>) result) {
                            if (o instanceof PackageInfo) {
                                PackageInfo pi = (PackageInfo) o;
                                if (!SUSPICIOUS_PACKAGES.contains(pi.packageName)) {
                                    filtered.add(pi);
                                }
                            }
                        }
                        param.setResult(filtered);
                    }
                }
            });
            MainHook.log("PackageManager.getInstalledPackages filtered");

            XposedBridge.hookAllMethods(PackageManager.class, "getInstalledApplications", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result instanceof List<?>) {
                        List<ApplicationInfo> filtered = new ArrayList<>();
                        for (Object o : (List<?>) result) {
                            if (o instanceof ApplicationInfo) {
                                ApplicationInfo ai = (ApplicationInfo) o;
                                if (!SUSPICIOUS_PACKAGES.contains(ai.packageName)) {
                                    filtered.add(ai);
                                }
                            }
                        }
                        param.setResult(filtered);
                    }
                }
            });
            MainHook.log("PackageManager.getInstalledApplications filtered");

            XposedBridge.hookAllMethods(PackageManager.class, "getPackageInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        String pkg = (String) param.args[0];
                        if (SUSPICIOUS_PACKAGES.contains(pkg)) {
                            param.setThrowable(new PackageManager.NameNotFoundException(pkg));
                        }
                    }
                }
            });

            XposedBridge.hookAllMethods(PackageManager.class, "getApplicationInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        String pkg = (String) param.args[0];
                        if (SUSPICIOUS_PACKAGES.contains(pkg)) {
                            param.setThrowable(new PackageManager.NameNotFoundException(pkg));
                        }
                    }
                }
            });
            MainHook.log("PackageManager.getPackageInfo/getApplicationInfo filtered");
        } catch (Throwable t) {
            MainHook.log("PackageManager filter install failed: " + t.getMessage());
        }
    }
}
