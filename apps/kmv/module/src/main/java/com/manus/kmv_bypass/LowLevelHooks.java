package com.manus.kmv_bypass;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class LowLevelHooks {

    private static final List<String> BLACKLISTED_PACKAGES = Arrays.asList(
        "com.topjohnwu.magisk", "io.github.huskydg.magisk", "com.thirdparty.superuser",
        "eu.chainfire.supersu", "com.noshufou.android.su", "com.koushikdutta.superuser",
        "com.zachspong.temprootremovejb", "com.ramdroid.appquarantine", "com.formyhm.hideroot",
        "de.robv.android.xposed.installer", "com.saurik.substrate", "com.amphoras.hidemyroot",
        "com.amphoras.hidemyrootadfree", "com.devadvance.rootcloak", "com.devadvance.rootcloakplus",
        "com.android.vending.billing.InAppBillingService.COIN", "com.chelpus.lackypatch",
        "com.dimonvideo.luckypatcher", "com.koushikdutta.rommanager", "com.koushikdutta.rommanager.license",
        "com.stericson.busybox", "com.jrummy.busybox.installer", "com.jrummy.busybox.installer.pro",
        "org.lsposed.manager", "me.weishu.exp", "com.vphonegaga.titan", "com.virresh.hide_my_applist"
    );

    private static final List<String> BLACKLISTED_PATHS = Arrays.asList(
        "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
        "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su", "/magisk",
        "/data/adb/magisk", "/data/adb/lsposed", "/system/framework/XposedBridge.jar",
        "/system/lib/libxposed_art.so", "/system/lib64/libxposed_art.so", "/data/local/tmp/xposed"
    );

    public void install(LoadPackageParam lpparam) {
        hookBuildTags();
        hookPackageManager(lpparam);
        hookFileAccess(lpparam);
        hookRuntimeExec(lpparam);
        hookSystemProperties(lpparam);
    }

    private void hookBuildTags() {
        try {
            XposedHelpers.setStaticObjectField(Build.class, "TAGS", "release-keys");
            MainHook.log("Build.TAGS spoofed to 'release-keys'");
        } catch (Throwable t) {
            MainHook.log("Failed to spoof Build.TAGS: " + t.getMessage());
        }
    }

    private void hookPackageManager(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", lpparam.classLoader, "getPackageInfo", String.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String pkg = (String) param.args[0];
                    if (pkg != null && BLACKLISTED_PACKAGES.contains(pkg)) {
                        param.setThrowable(new android.content.pm.PackageManager.NameNotFoundException(pkg));
                        MainHook.log("PackageManager: Hid package " + pkg);
                    }
                }
            });
            MainHook.log("PackageManager.getPackageInfo hooked");
        } catch (Throwable t) {
            MainHook.log("Could not hook PackageManager: " + t.getMessage());
        }
    }

    private void hookFileAccess(LoadPackageParam lpparam) {
        try {
            XC_MethodHook fileHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    File file = (File) param.thisObject;
                    String path = file.getAbsolutePath();
                    if (path != null) {
                        for (String badPath : BLACKLISTED_PATHS) {
                            if (path.startsWith(badPath)) {
                                param.setResult(false);
                                // MainHook.log("FileAccess: Hid " + path);
                                return;
                            }
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(File.class, "exists", fileHook);
            XposedHelpers.findAndHookMethod(File.class, "canRead", fileHook);
            XposedHelpers.findAndHookMethod(File.class, "canExecute", fileHook);
            MainHook.log("File.exists/canRead/canExecute hooked");
        } catch (Throwable t) {
            MainHook.log("Could not hook File: " + t.getMessage());
        }
    }

    private void hookRuntimeExec(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "exec", String[].class, String[].class, File.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String[] cmdArray = (String[]) param.args[0];
                    if (cmdArray != null && cmdArray.length > 0) {
                        String cmd = cmdArray[0];
                        if (cmd.equals("su") || cmd.equals("magisk") || cmd.equals("which") || cmd.equals("getprop") || cmd.equals("mount")) {
                            param.setThrowable(new java.io.IOException("Cannot run program \"" + cmd + "\": error=2, No such file or directory"));
                            MainHook.log("Runtime.exec: Blocked " + cmd);
                        }
                    }
                }
            });
            MainHook.log("Runtime.exec hooked");
        } catch (Throwable t) {
            MainHook.log("Could not hook Runtime.exec: " + t.getMessage());
        }
    }

    private void hookSystemProperties(LoadPackageParam lpparam) {
        try {
            XC_MethodHook propHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if (key != null) {
                        if (key.equals("ro.debuggable")) param.setResult("0");
                        else if (key.equals("ro.secure")) param.setResult("1");
                        else if (key.equals("ro.build.tags")) param.setResult("release-keys");
                        else if (key.equals("ro.boot.verifiedbootstate")) param.setResult("green");
                        else if (key.equals("ro.boot.flash.locked")) param.setResult("1");
                        else if (key.equals("ro.build.selinux")) param.setResult("1");
                        else if (key.equals("ro.kernel.qemu")) param.setResult("0");
                    }
                }
            };
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", lpparam.classLoader, "get", String.class, propHook);
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", lpparam.classLoader, "get", String.class, String.class, propHook);
            MainHook.log("SystemProperties.get hooked");
        } catch (Throwable t) {
            MainHook.log("Could not hook SystemProperties: " + t.getMessage());
        }
    }
}
