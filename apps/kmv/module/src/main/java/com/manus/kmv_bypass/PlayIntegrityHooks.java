package com.manus.kmv_bypass;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class PlayIntegrityHooks {

    public void install(LoadPackageParam lpparam) {
        String fakeJwt = "eyJhbGciOiJSUzI1NiJ9.BYPASSED.SIGNATURE";

        try {
            XposedHelpers.findAndHookMethod(
                "com.google.android.play.core.integrity.IntegrityTokenResponse", 
                lpparam.classLoader, 
                "token", 
                XC_MethodReplacement.returnConstant(fakeJwt)
            );
            MainHook.log("PlayIntegrity legacy token() hooked");
        } catch (Throwable t) {
            MainHook.log("Could not hook PlayIntegrity legacy token: " + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityToken", 
                lpparam.classLoader, 
                "token", 
                XC_MethodReplacement.returnConstant(fakeJwt)
            );
            MainHook.log("PlayIntegrity standard token() hooked");
        } catch (Throwable t) {
            MainHook.log("Could not hook PlayIntegrity standard token: " + t.getMessage());
        }
    }
}
