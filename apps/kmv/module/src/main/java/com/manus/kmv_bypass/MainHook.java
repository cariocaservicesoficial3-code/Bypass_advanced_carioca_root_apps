package com.manus.kmv_bypass;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {
    public static final String TAG = "[KMVBypass] ";
    public static final String TARGET = "com.gigigo.ipirangaconectcar";
    public static final String VERSION = "1.3.0";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!TARGET.equals(lpparam.packageName)) return;

        log("==========================================");
        log("   KMV Root Bypass v" + VERSION + " — ACTIVATED");
        log("   Target: " + TARGET);
        log("==========================================");

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

    public static void log(String msg) { XposedBridge.log(TAG + msg); }
}
