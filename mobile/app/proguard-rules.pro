# FORMATCI Media Studio ProGuard rules
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.formatci.mediastudio.domain.model.** { *; }
-keep class com.formatci.mediastudio.data.database.** { *; }

-dontwarn androidx.media3.**

