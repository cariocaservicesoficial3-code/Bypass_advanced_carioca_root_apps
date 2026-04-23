package com.manus.kmv_bypass;

import android.content.Context;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class CashShieldHooks {

    public void install(LoadPackageParam lpparam) {
        String nativeUtilsClass = "com.shield.ptr.internal.NativeUtils";
        
        String[] methodsToFalse = {
            "isAccessedSuperuserApk", "isChaosDetected", "isDetectedDevKeys", 
            "isDetectedTestKeys", "isFoundBusyboxBinary", "isFoundDangerousProps", 
            "isFoundMagisk", "isFoundResetprop", "isFoundSuBinary", "isFoundSubstrate", 
            "isFoundWrongPathPermission", "isFoundXposed", "isFridaDetected", 
            "isGboxDetected", "isJiaguDetected", "isLsplantDetected", "isNotFoundReleaseKeys", 
            "isPermissiveSelinux", "isSandHookDetected", "isSuExists", "isTaichiDetected", 
            "isVirtualAndroidDetected", "isVirtualCameraAppDetected", "isVirtualXposedDetected", 
            "isZygiskDetected", "listenForFrida"
        };

        for (String method : methodsToFalse) {
            try {
                XposedHelpers.findAndHookMethod(nativeUtilsClass, lpparam.classLoader, method, XC_MethodReplacement.returnConstant(false));
                MainHook.log("CashShield." + method + " hooked -> false");
            } catch (Throwable t) {
                MainHook.log("Could not hook CashShield." + method + ": " + t.getMessage());
            }
        }

        // Methods returning 0 (int)
        try {
            XposedHelpers.findAndHookMethod(nativeUtilsClass, lpparam.classLoader, "getArpCache", int.class, XC_MethodReplacement.returnConstant(0));
            XposedHelpers.findAndHookMethod(nativeUtilsClass, lpparam.classLoader, "isPathExists", String.class, XC_MethodReplacement.returnConstant(0));
            XposedHelpers.findAndHookMethod(nativeUtilsClass, lpparam.classLoader, "jitCacheCount", XC_MethodReplacement.returnConstant(0));
            MainHook.log("CashShield int methods hooked -> 0");
        } catch (Throwable t) {
            MainHook.log("Could not hook CashShield int methods: " + t.getMessage());
        }

        // Methods returning Strings
        try {
            XposedHelpers.findAndHookMethod(nativeUtilsClass, lpparam.classLoader, "getHostsModifiedTime", XC_MethodReplacement.returnConstant("0"));
            XposedHelpers.findAndHookMethod(nativeUtilsClass, lpparam.classLoader, "getBaseApkPath", XC_MethodReplacement.returnConstant("/data/app/~~random==/com.gigigo.ipirangaconectcar-random==/base.apk"));
            XposedHelpers.findAndHookMethod(nativeUtilsClass, lpparam.classLoader, "getNativeAppVersion", Context.class, XC_MethodReplacement.returnConstant("4.83.101"));
            XposedHelpers.findAndHookMethod(nativeUtilsClass, lpparam.classLoader, "getNativePackage", Context.class, XC_MethodReplacement.returnConstant("com.gigigo.ipirangaconectcar"));
            MainHook.log("CashShield String methods hooked -> safe values");
        } catch (Throwable t) {
            MainHook.log("Could not hook CashShield String methods: " + t.getMessage());
        }
    }
}
