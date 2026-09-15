# Frianzo Android App (WebView Wrapper)

Ye Frianzo ka official Android WebView app hai jo `https://frianzo.online` ko native app ki tarah load karti hai.

## Kya included hai
- `app/` — Android project (Kotlin, WebView, pull-to-refresh, back button support)
- `.github/workflows/build-apk.yml` — GitHub Actions workflow jo APK aur AAB build karta hai
- Frianzo branding aur Android package `com.frianzo.app`

## Build

GitHub Actions mein **Build Frianzo Android App** workflow push par automatically run hota hai. Debug APK aur release AAB build ke baad `Frianzo-Android` artifact mein milte hain.

## App identity
- App name: `Frianzo`
- Application ID: `com.frianzo.app`
- Website: `https://frianzo.online`
- Android project name: `Frianzo`

## Source structure
- Main activity package: `com.frianzo.app`
- Main activity: `app/src/main/java/com/frianzo/app/MainActivity.kt`
- App label: `app/src/main/res/values/strings.xml`
- Theme: `Theme.Frianzo`
- Colors: `frianzo_navy` and `frianzo_gold`
