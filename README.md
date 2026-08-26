# EarnPro Android App (WebView Wrapper)

Ye custom Android app hai jo `https://earnpro.site` ko native app ki tarah load karti hai. Koi third-party branding nahi — pura source code aapka apna hai.

## Kya included hai
- `app/` — Android project (Kotlin, WebView, pull-to-refresh, back button support)
- `.github/workflows/build-apk.yml` — GitHub Actions workflow jo automatically APK build karta hai (PC ki zaroorat nahi)
- Placeholder app icon (navy + gold "E" design) — replace karna optional hai

## APK banwane ka tareeqa (bina PC ke, sirf phone se)

1. **GitHub par account banayen** (agar nahi hai) — github.com, phone browser se ho jata hai.
2. **Naya repository banayen** — Name: `earnpro-app`, Public ya Private dono chalega.
3. Is poore folder (`EarnProApp`) ke **saare files aur folders** us repo mein upload karein.
   - GitHub app ya website ke "Add file → Upload files" se drag-drop kar sakti hain.
   - Zaroori: `.github` folder bhi upload karna hai (ye hidden dikh sakta hai, "Show hidden files" on karein file manager mein).
4. Upload ke baad GitHub repo ke **"Actions"** tab par jayen.
5. Wahan "Build EarnPro APK" workflow khud chal jayega (agar na chale to "Run workflow" button dabayein).
6. 2-3 minute mein build complete ho jayegi. Usi run ko open karke neeche **"Artifacts"** section se `EarnPro-debug-apk` download kar lein — ye ek `.zip` hoga jisme aapki `.apk` file hogi.
7. Phone par woh APK install kar lein (agar "Unknown sources" ki warning aaye to allow kar dein — kyunke Play Store se nahi hai).

Is debug APK se aap turant testing kar sakti hain. Play Store par publish karne ke liye baad mein "signed release APK/AAB" banani hogi — jab zaroorat ho batayein, woh step bhi kara doongi.

## App icon replace karna (optional, apna asli logo lagane ke liye)

Abhi ek simple placeholder icon (navy background + gold "E") laga hai. Apna asli EarnPro logo lagane ke liye:

1. Kisi bhi free tool jaise **appicon.co** ya **easyappicon.com** par jayen (browser se, PC ki zaroorat nahi).
2. Apna logo image upload karein, "Android" icon set generate karayen.
3. Jo files milengi (mipmap-mdpi, mipmap-hdpi, etc. folders), unhe isi project ke andar `app/src/main/res/` mein same-named folders mein overwrite kar dein (GitHub par upload karte waqt "replace" ho jayega).
4. Dobara push/upload karein — Actions apne aap naya build bana degi.

## App ka naam / package badalna
- App name: `app/src/main/res/values/strings.xml` mein `app_name` change karein.
- Package/Application ID: `app/build.gradle.kts` mein `applicationId = "com.earnpro.app"` line.

## Site URL
Agar domain kabhi badle, to `app/src/main/java/com/earnpro/app/MainActivity.kt` mein `SITE_URL` constant update karein.
