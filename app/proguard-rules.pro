# Obfuscation and Shrinking Rules for CastIt Receiver

# General keep rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# NanoHTTPD keeps
-keep class org.nanohttpd.** { *; }

# Jetpack Media3 (ExoPlayer) keeps
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlin Serialization keeps
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keep class kotlinx.serialization.json.** { *; }

# Log Stripping
# -assumenosideeffects class android.util.Log {
#     public static boolean isLoggable(java.lang.String, int);
#     public static int v(...);
#     public static int d(...);
#     public static int i(...);
# }

# DanmakuFlameMaster keep rules
-keep class master.flame.danmaku.** { *; }
-keep interface master.flame.danmaku.** { *; }
-dontwarn master.flame.danmaku.**
