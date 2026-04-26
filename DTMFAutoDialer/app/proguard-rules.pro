# Manter classes do Xposed
-keep class com.carioca.dtmfautodialer.hooks.** { *; }
-keep class de.robv.android.xposed.** { *; }

# Manter nomes de classes para o LSPosed
-keepnames class com.carioca.dtmfautodialer.hooks.MainHook
