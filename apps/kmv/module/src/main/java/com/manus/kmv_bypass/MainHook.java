package com.manus.kmv_bypass;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {
    public static final String TAG = "[KMVBypass] ";
    public static final String TAG_LOG = "KMVBypass";   // logcat-friendly tag
    public static final String TARGET = "com.gigigo.ipirangaconectcar";
    public static final String VERSION = "1.5.6";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!TARGET.equals(lpparam.packageName)) return;

        log("==========================================");
        log("   KMV Root Bypass v" + VERSION + " — ACTIVATED");
        log("   Target: " + TARGET);
        log("   Strategy: DNS SINKHOLE + HttpURLConnection + CRASH FIX");
        log("==========================================");

        // ===== ORDEM CRÍTICA =====
        // 1. PersistentIdHooks PRIMEIRO — bloqueia HTTP e rotaciona IDs ANTES de qualquer coleta
        try { new PersistentIdHooks().install(lpparam); log("[OK] PersistentIdHooks v1.5.6 installed (DNS sinkhole + HttpURLConnection hook + Crash Fix)"); }
        catch (Throwable t) { log("[FAIL] PersistentIdHooks: " + t); }

        // 2. IdentitySpoofHooks — spoof de ANDROID_ID, SERIAL, Build.*, install times
        try { new IdentitySpoofHooks().install(lpparam); log("[OK] IdentitySpoofHooks installed"); }
        catch (Throwable t) { log("[FAIL] IdentitySpoofHooks: " + t); }

        // 3. AntiFingerprintHooks — Magnes JSON neutralization, ViewPkg block, NetworkInterface filter, PM filter
        try { new AntiFingerprintHooks().install(lpparam); log("[OK] AntiFingerprintHooks installed"); }
        catch (Throwable t) { log("[FAIL] AntiFingerprintHooks: " + t); }

        // 4. FingerprintHooks — AllowMe/Serasa, Incognia, Zaig
        try { new FingerprintHooks().install(lpparam); log("[OK] FingerprintHooks installed"); }
        catch (Throwable t) { log("[FAIL] FingerprintHooks: " + t); }

        // 5. RootBeerHooks — root detection
        try { new RootBeerHooks().install(lpparam); log("[OK] RootBeerHooks installed"); }
        catch (Throwable t) { log("[FAIL] RootBeerHooks: " + t); }

        // 6. CashShieldHooks — anti-fraud native
        try { new CashShieldHooks().install(lpparam); log("[OK] CashShieldHooks installed"); }
        catch (Throwable t) { log("[FAIL] CashShieldHooks: " + t); }

        // 7. PlayIntegrityHooks — Play Integrity API
        try { new PlayIntegrityHooks().install(lpparam); log("[OK] PlayIntegrityHooks installed"); }
        catch (Throwable t) { log("[FAIL] PlayIntegrityHooks: " + t); }

        // 8. LowLevelHooks — Build.TAGS, SystemProperties, File.exists, Runtime.exec
        try { new LowLevelHooks().install(lpparam); log("[OK] LowLevelHooks installed"); }
        catch (Throwable t) { log("[FAIL] LowLevelHooks: " + t); }

        log("==========================================");
        log("   All " + VERSION + " hook groups installed.");
        log("   DNS sinkhole: paypal, viewpkg, incognia, serasa, appsflyer");
        log("   Interceptor Chain UUID rewrite + ID rotation + HTTP blocking");
        log("==========================================");
    }

    /** Log dual: XposedBridge.log + android.util.Log.e (logcat) */
    public static void log(String msg) {
        XposedBridge.log(TAG + msg);
        Log.e(TAG_LOG, msg);
    }
}
