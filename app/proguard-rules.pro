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
