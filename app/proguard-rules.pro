-dontwarn sun.security.internal.spec.**
-dontwarn sun.security.provider.**
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

# libxposed
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Mod packs are instantiated reflectively
-keepclassmembers class * extends com.drdisagree.pixellauncherenhanced.xposed.core.ModPack {
    public <init>(android.content.Context);
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
