-keep,allowoptimization,allowobfuscation class com.jaredrummler.android.colorpicker.**
-dontwarn sun.security.internal.spec.**
-dontwarn sun.security.provider.**
-dontwarn com.jaredrummler.android.colorpicker.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.lang.model.element.Modifier

-keepattributes Exceptions,LineNumberTable,Signature,SourceFile

-keepclasseswithmembernames,allowoptimization,allowobfuscation class * {
    native <methods>;
}

-keepclassmembers,allowoptimization,allowobfuscation enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Activity and Fragment names
-keep class com.drdisagree.pixellauncherenhanced.ui.activities.**
-keep class com.drdisagree.pixellauncherenhanced.ui.fragments.**

# Xposed framework stubs
-keep class de.robv.android.xposed.** { *; }

# Xposed entry points (called directly by the framework)
-keep class com.drdisagree.pixellauncherenhanced.xposed.InitHook {
    public <init>();
}
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources

# Optimize method bodies, preserve Xposed lifecycle signatures
-keepclassmembers class * extends com.drdisagree.pixellauncherenhanced.xposed.ModPack {
    public <init>(android.content.Context);
}
-keepclassmembers,allowoptimization,allowobfuscation class com.drdisagree.iconify.xposed.** {
    public <init>();
    public <init>(android.content.Context);
    public void initZygote(de.robv.android.xposed.IXposedHookZygoteInit$StartupParam);
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
    public void handleInitPackageResources(de.robv.android.xposed.callbacks.XC_InitPackageResources$InitPackageResourcesParam);
}

# Hook callbacks
-keepclassmembers,allowoptimization,allowobfuscation class * extends de.robv.android.xposed.XC_MethodHook {
    protected void beforeHookedMethod(de.robv.android.xposed.XC_MethodHook$MethodHookParam);
    protected void afterHookedMethod(de.robv.android.xposed.XC_MethodHook$MethodHookParam);
}
-keepclassmembers,allowoptimization,allowobfuscation class * extends de.robv.android.xposed.XC_MethodReplacement {
    protected java.lang.Object replaceHookedMethod(de.robv.android.xposed.XC_MethodHook$MethodHookParam);
}

# XPrefs: name and public API must be stable for cross-process access
-keepnames class com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs
-keepclassmembers,allowoptimization,allowobfuscation class com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs {
    public *;
}

# Xposed logs
-keep class de.robv.android.xposed.XposedBridge {
    public static void log(java.lang.String);
    public static void log(java.lang.Throwable);
}

# Keep Parcelable Creators
-keepnames class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# AIDL Classes
-keep interface **.I* { *; }
-keep class **.I*$Stub { *; }
-keep class **.I*$Stub$Proxy { *; }

# Obfuscation
-repackageclasses
-allowaccessmodification

# Strip debug log
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}
