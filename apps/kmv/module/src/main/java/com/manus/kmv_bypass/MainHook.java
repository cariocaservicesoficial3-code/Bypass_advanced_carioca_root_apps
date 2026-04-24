package com.manus.kmv_bypass;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {
    public static final String TAG = "[KMVBypass] ";
    public static final String TAG_LOG = "KMVBypass";   // logcat-friendly tag
    public static final String TARGET = "com.gigigo.ipirangaconectcar";
    public static final String VERSION = "1.4.0";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!TARGET.equals(lpparam.packageName)) return;

        log("==========================================");
        log("   KMV Root Bypass v" + VERSION + " — ACTIVATED");
        log("   Target: " + TARGET);
        log("==========================================");

        // Identity spoof PRIMEIRO (gera nova identidade antes de qualquer coleta)
        try { new IdentitySpoofHooks().install(lpparam); log("[OK] IdentitySpoofHooks installed"); }
        catch (Throwable t) { log("[FAIL] IdentitySpoofHooks: " + t); }

        try { new RootBeerHooks().install(lpparam); log("[OK] RootBeerHooks installed"); }
        catch (Throwable t) { log("[FAIL] RootBeerHooks: " + t); }

        try { new CashShieldHooks().install(lpparam); log("[OK] CashShieldHooks installed"); }
        catch (Throwable t) { log("[FAIL] CashShieldHooks: " + t); }

        try { new PlayIntegrityHooks().install(lpparam); log("[OK] PlayIntegrityHooks installed"); }
        catch (Throwable t) { log("[FAIL] PlayIntegrityHooks: " + t); }

        try { new LowLevelHooks().install(lpparam); log("[OK] LowLevelHooks installed"); }
        catch (Throwable t) { log("[FAIL] LowLevelHooks: " + t); }

        try { new FingerprintHooks().install(lpparam); log("[OK] FingerprintHooks installed"); }
        catch (Throwable t) { log("[FAIL] FingerprintHooks: " + t); }

        try { new AntiFingerprintHooks().install(lpparam); log("[OK] AntiFingerprintHooks installed"); }
        catch (Throwable t) { log("[FAIL] AntiFingerprintHooks: " + t); }

        log("All hook groups installed.");
    }

    /** Log dual: XposedBridge.log + android.util.Log.e (logcat) */
    public static void log(String msg) {
        XposedBridge.log(TAG + msg);
        Log.e(TAG_LOG, msg);
    }
}
