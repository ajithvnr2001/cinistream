# CineStream R8 / ProGuard keep rules (Media3 1.11.1, NextLib FFmpeg, WireGuard GoBackend, Cronet, Room)

-keep class com.example.BuildConfig { *; }
-keep class com.example.model.** { *; }

# NextLib FFmpeg JNI native bindings (FfmpegLibrary / FfmpegVideoRenderer / FfmpegAudioRenderer)
-keep class io.github.anilbeesetti.nextlib.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# WireGuard GoBackend tunnel & Go native callbacks
-keep class com.wireguard.** { *; }
-dontwarn com.wireguard.**

# AndroidX Media3 extensions & renderers resolved via reflection
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.exoplayer.dash.** { *; }
-keep class androidx.media3.datasource.cronet.** { *; }
-keep class androidx.media3.datasource.okhttp.** { *; }
-keep class androidx.media3.extractor.text.** { *; }

# Play Services Cronet dynamically loaded provider
-keep class org.chromium.net.** { *; }
-dontwarn org.chromium.net.**
-keep class com.google.android.gms.net.** { *; }

# Room entities/DAOs and DataStore Preferences
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.data.** { *; }
-keepclassmembers class * extends com.google.protobuf.** { *; }

# OkHttp / Conscrypt optional platform suppressions
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
