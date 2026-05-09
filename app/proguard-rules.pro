# Moshi
-keep class com.hyperos.updater.data.remote.dto.** { *; }
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

# Room
-keep class com.hyperos.updater.data.local.entity.** { *; }

# Shizuku
-keep class rikka.shizuku.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
