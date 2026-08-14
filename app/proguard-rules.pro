# Add project-specific ProGuard rules here.
-dontwarn androidx.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn kotlin.**
-keep class kotlin.** { *; }
# Keep TTS interface
-keep class * extends android.speech.tts.TextToSpeech { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
