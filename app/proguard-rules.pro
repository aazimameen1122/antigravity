-keep class com.aazim.antigravity.data.** { *; }
-keep class com.aazim.antigravity.service.** { *; }
-keep class com.aazim.antigravity.oauth.** { *; }
-keep class com.aazim.antigravity.install.** { *; }

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

-keep class coil.** { *; }
-dontwarn coil.**

-keep class com.aazim.antigravity.BuildConfig { *; }

# JNA + Vosk (offline voice): JNA resolves its Pointer.peer field and
# Vosk's structures by name through JNI — R8 renaming/stripping them
# crashes at startup with "Can't obtain peer field ID for class
# com.sun.jna.Pointer" (hit on v1.0.8). Keep both fully.
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn com.sun.jna.**
-dontwarn org.vosk.**
