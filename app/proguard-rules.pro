# ProGuard rules for NcmDecrypt
-keepclassmembers class com.ncmdecrypt.** { *; }
-keep class com.ncmdecrypt.** { *; }
-keep class org.json.** { *; }

# jAudioTagger — heavy reflection over tag/field classes; keep it whole.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
