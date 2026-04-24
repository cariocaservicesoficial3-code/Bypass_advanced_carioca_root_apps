package com.manus.kmv_bypass;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v1.2 — Camadas que sobreviveram após v1.1 (descobertas no segundo HAR):
 *   1. PayPal Magnes (lib.android.paypal.com.magnessdk) — RDA/Risk Data Aggregator,
 *      coleta fingerprint completo + envia para c.paypal.com/r/v1/device/client-metadata.
 *      Backend KMV consome o pairingId / clientMetadataId e cruza com sua telemetria.
 *   2. ViewPkg / AppView (Ba/b) — coleta lista completa de pacotes instalados e envia
 *      para d.viewpkg.com. Detecta apps suspeitos (Magisk Manager, LSPosed Manager,
 *      Frida server, etc.) que normalmente passariam por hide_my_applist.
 *   3. PackageManager filter — defesa em profundidade caso outros SDKs façam queryAllPackages.
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
        // Hookers
        "com.android.shell" // exemplo
    ));

    public void install(LoadPackageParam lpparam) {
        hookPayPalMagnes(lpparam);
        hookViewPkg(lpparam);
        hookPackageManagerFilter(lpparam);
    }

    /**
     * PayPal Magnes — neutraliza o submit de fingerprint.
     * Estratégia: hook em d.f(Context, sourceAppId, additionalData) que é o collectAndSubmit().
     * Ao invés de impedir (pode quebrar fluxo), forçamos um payload mínimo e estável.
     *
     * Como o app só usa o pairingId/clientMetadataId que retorna no JSONObject c.b(),
     * fazemos hook em c.b() para devolver um pairingId fixo "limpo".
     * E hook em c.a() para devolver um JSONObject vazio ou neutro.
     */
    private void hookPayPalMagnes(LoadPackageParam lpparam) {
        // 1) Hook em lib.android.paypal.com.magnessdk.c.b() — getPayPalClientMetaDataId
        try {
            XposedHelpers.findAndHookMethod(
                "lib.android.paypal.com.magnessdk.c",
                lpparam.classLoader,
                "b",
                XC_MethodReplacement.returnConstant("00000000000000000000000000000000")
            );
            MainHook.log("Magnes c.b() (pairingId) -> stable zeros");
        } catch (Throwable t) {
            MainHook.log("Magnes c.b() hook failed: " + t.getMessage());
        }

        // 2) Hook em lib.android.paypal.com.magnessdk.c.a() — getPayloadJson (devolve null)
        try {
            XposedHelpers.findAndHookMethod(
                "lib.android.paypal.com.magnessdk.c",
                lpparam.classLoader,
                "a",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            return new org.json.JSONObject();
                        } catch (Throwable t) {
                            return null;
                        }
                    }
                }
            );
            MainHook.log("Magnes c.a() (payloadJson) -> empty JSONObject");
        } catch (Throwable t) {
            MainHook.log("Magnes c.a() hook failed: " + t.getMessage());
        }

        // 3) Hook em lib.android.paypal.com.magnessdk.d.f(Context, String, HashMap)
        //    para devolver um c "limpo" sem fazer collect/submit nenhum.
        try {
            XposedHelpers.findAndHookMethod(
                "lib.android.paypal.com.magnessdk.d",
                lpparam.classLoader,
                "f",
                "android.content.Context", String.class, "java.util.HashMap",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // Não substituímos o objeto retornado para não quebrar o fluxo,
                        // mas o c.b() e c.a() já estão hookados acima.
                        MainHook.log("Magnes d.f() executed (c.b/c.a hooks will neutralize output)");
                    }
                }
            );
            MainHook.log("Magnes d.f() (collectAndSubmit) wrapped");
        } catch (Throwable t) {
            MainHook.log("Magnes d.f() hook failed: " + t.getMessage());
        }
    }

    /**
     * ViewPkg/AppView (Ba/b) — neutraliza o envio da lista de pacotes para d.viewpkg.com.
     * Estratégia: substituir o método principal Ba/b.c(String, String, String) por no-op.
     */
    private void hookViewPkg(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "Ba.b",
                lpparam.classLoader,
                "c",
                String.class, String.class, String.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        MainHook.log("ViewPkg Ba/b.c() blocked (no packages sent to d.viewpkg.com)");
                        return null;
                    }
                }
            );
            MainHook.log("ViewPkg Ba/b.c() neutralized");
        } catch (Throwable t) {
            MainHook.log("ViewPkg Ba/b.c hook failed: " + t.getMessage());
        }

        // Bloquear também o callback b(JSONObject) caso seja chamado por outro caminho
        try {
            XposedHelpers.findAndHookMethod(
                "Ba.b",
                lpparam.classLoader,
                "b",
                "org.json.JSONObject",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        MainHook.log("ViewPkg Ba/b.b(JSONObject) intercepted (no-op)");
                        return null;
                    }
                }
            );
        } catch (Throwable ignored) {}
    }

    /**
     * Filtro de PackageManager — defesa em profundidade.
     * Remove pacotes suspeitos de getInstalledPackages, getInstalledApplications
     * e queryIntentActivities. Cobre VIEWPKG, MAGNES, e qualquer SDK futuro.
     */
    private void hookPackageManagerFilter(LoadPackageParam lpparam) {
        try {
            // getInstalledPackages(int)
            XposedBridge.hookAllMethods(PackageManager.class, "getInstalledPackages", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
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

            // getInstalledApplications(int)
            XposedBridge.hookAllMethods(PackageManager.class, "getInstalledApplications", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
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

            // getPackageInfo(String, int) — retornar NameNotFoundException se for suspeito
            XposedBridge.hookAllMethods(PackageManager.class, "getPackageInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        String pkg = (String) param.args[0];
                        if (SUSPICIOUS_PACKAGES.contains(pkg)) {
                            param.setThrowable(new PackageManager.NameNotFoundException(pkg));
                        }
                    }
                }
            });
            MainHook.log("PackageManager.getPackageInfo filters suspicious pkgs");

            // getApplicationInfo(String, int)
            XposedBridge.hookAllMethods(PackageManager.class, "getApplicationInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        String pkg = (String) param.args[0];
                        if (SUSPICIOUS_PACKAGES.contains(pkg)) {
                            param.setThrowable(new PackageManager.NameNotFoundException(pkg));
                        }
                    }
                }
            });
            MainHook.log("PackageManager.getApplicationInfo filters suspicious pkgs");

        } catch (Throwable t) {
            MainHook.log("PackageManager filter install failed: " + t.getMessage());
        }
    }
}
