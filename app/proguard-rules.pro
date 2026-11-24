# Frianzo project-specific R8 / ProGuard rules.

# --- JavaScript bridge (critical) -------------------------------------------
# The web page calls MainActivity.NativeBridge methods by name through
# window.FrianzoNative, and WebView locates them reflectively via the
# @JavascriptInterface annotation. Without these keeps, R8 would strip or
# rename the bridge class and its methods and every web-to-native call in
# the release build (push token, install referrer, biometric, review,
# downloads, theme, cookie flush) would silently fail.
-keep class com.frianzo.app.MainActivity$NativeBridge {
    <init>();
    *;
}

# Safety net: never strip or rename any @JavascriptInterface method in any
# class, present or future.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# The bridge lookup reads the annotation at runtime, so it must survive.
-keepattributes RuntimeVisibleAnnotations
