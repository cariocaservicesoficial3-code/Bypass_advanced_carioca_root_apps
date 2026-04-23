package com.manus.kmv_bypass;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class RootBeerHooks {

    public void install(LoadPackageParam lpparam) {
        // RootBeer class (obfuscated, but class name is preserved)
        String rootBeerClass = "com.scottyab.rootbeer.RootBeer";
        
        String[] methodsToFalse = {
            "a", "b", "c", "checkForDangerousProps", "checkForRWPaths", 
            "checkForRootNative", "d", "detectTestKeys", "e", "f", "g", "h", "j"
        };

        for (String method : methodsToFalse) {
            try {
                if (method.equals("b") || method.equals("f") || method.equals("h")) {
                    // methods with arguments (String or String[])
                    if (method.equals("b")) {
                        XposedHelpers.findAndHookMethod(rootBeerClass, lpparam.classLoader, method, String.class, XC_MethodReplacement.returnConstant(false));
                    } else {
                        XposedHelpers.findAndHookMethod(rootBeerClass, lpparam.classLoader, method, String[].class, XC_MethodReplacement.returnConstant(false));
                    }
                } else {
                    // methods with no arguments
                    XposedHelpers.findAndHookMethod(rootBeerClass, lpparam.classLoader, method, XC_MethodReplacement.returnConstant(false));
                }
                MainHook.log("RootBeer." + method + " hooked -> false");
            } catch (Throwable t) {
                MainHook.log("Could not hook RootBeer." + method + ": " + t.getMessage());
            }
        }

        // RootBeerNative class
        String rootBeerNativeClass = "com.scottyab.rootbeer.RootBeerNative";
        try {
            // wasNativeLibraryLoaded (obfuscated as 'a')
            XposedHelpers.findAndHookMethod(rootBeerNativeClass, lpparam.classLoader, "a", XC_MethodReplacement.returnConstant(true));
            MainHook.log("RootBeerNative.a (wasNativeLibraryLoaded) hooked -> true");
            
            // checkForRoot
            XposedHelpers.findAndHookMethod(rootBeerNativeClass, lpparam.classLoader, "checkForRoot", Object[].class, XC_MethodReplacement.returnConstant(0));
            MainHook.log("RootBeerNative.checkForRoot hooked -> 0");
            
            // setLogDebugMessages
            XposedHelpers.findAndHookMethod(rootBeerNativeClass, lpparam.classLoader, "setLogDebugMessages", boolean.class, XC_MethodReplacement.returnConstant(0));
        } catch (Throwable t) {
            MainHook.log("Could not hook RootBeerNative: " + t.getMessage());
        }
    }
}
