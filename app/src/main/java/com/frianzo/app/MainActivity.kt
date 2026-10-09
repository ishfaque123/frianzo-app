package com.frianzo.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.PermissionRequest
import android.webkit.GeolocationPermissions
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.firebase.messaging.FirebaseMessaging
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var credentialManager: CredentialManager
    @Volatile private var pushToken: String = ""
    @Volatile private var installReferrer: String? = null
    @Volatile private var referralClaimed: Boolean = false
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var pendingWebAudioPermissionRequest: PermissionRequest? = null
    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingWebAudioPermissionRequest
            pendingWebAudioPermissionRequest = null
            if (granted && request != null) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                request?.deny()
            }
        }
    private var pendingGeolocationRequest: Pair<String, GeolocationPermissions.Callback>? = null
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingGeolocationRequest
            pendingGeolocationRequest = null
            request?.second?.invoke(request.first, granted, false)
        }

    inner class NativeBridge {
        @JavascriptInterface
        fun getPushToken(): String = pushToken

        @JavascriptInterface
        fun getInstallReferrer(): String = installReferrer ?: ""

        // Called right after logout clears the auth cookies via JS. WebView
        // normally persists cookies to disk on its own schedule, so if the
        // app is force-closed right after logging out, the deletion can be
        // lost and the old session cookie comes back on next launch. This
        // forces the cleared cookie state to disk immediately.
        @JavascriptInterface
        fun flushCookies() {
            CookieManager.getInstance().flush()
        }

        // Called by the web ThemeProvider whenever the theme is applied, so the
        // native launch colors (splash / window / WebView background) can match
        // the user's selected theme on the next cold start.
        @JavascriptInterface
        fun setAppTheme(theme: String) {
            if (theme != "dark" && theme != "light") return
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_APP_THEME, theme).apply()
        }

        @JavascriptInterface
        fun startReelDownload(url: String): Boolean {
            return enqueueReelDownload(url)
        }
    }
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraCaptureUri: Uri? = null
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val capturedUri = cameraCaptureUri
        cameraCaptureUri = null
        if (capturedUri != null) {
            // We launched a direct camera-capture intent (not the standard
            // document/gallery chooser), so the result doesn't come back in
            // the format FileChooserParams.parseResult() expects. The file
            // was written straight to the Uri we handed the camera app.
            val uris = if (result.resultCode == RESULT_OK) arrayOf(capturedUri) else null
            fileChooserCallback?.onReceiveValue(uris)
        } else {
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileChooserCallback?.onReceiveValue(uris)
        }
        fileChooserCallback = null
    }

    companion object {
        private const val TAG = "FrianzoGoogleAuth"
        private const val SITE_URL = "https://frianzo.online"
        private const val SITE_HOST = "frianzo.online"
        private const val API_URL = "https://api.frianzo.online"
        private const val API_HOST = "api.frianzo.online"
        private const val GOOGLE_START_PATH = "/api/auth/google/start"
        private const val OAUTH_SCHEME = "frianzo"
        private const val OAUTH_HOST = "oauth"
        private const val GOOGLE_CONFIG_PATH = "/api/auth/google/native-config"
        private const val GOOGLE_NATIVE_LOGIN_PATH = "/api/auth/google/native"
        private const val HTTP_TIMEOUT_MS = 15000
        private const val PREFS_NAME = "frianzo_prefs"
        private const val KEY_APP_THEME = "app_theme"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Pick the launch theme from the web theme the app last reported
        // ('dark'/'light'), falling back to the system night mode on first
        // run. Must run before super.onCreate() so the window is created with
        // the theme-matching splash instead of the static manifest one.
        val darkTheme = isDarkTheme()
        setTheme(if (darkTheme) R.style.Theme_Frianzo_Splash_Dark else R.style.Theme_Frianzo_Splash_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        webView.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (darkTheme) R.color.frianzo_dark_bg else android.R.color.white
            )
        )
        swipeRefresh = findViewById(R.id.swipe_refresh)
        credentialManager = CredentialManager.create(this)
        webView.addJavascriptInterface(NativeBridge(), "FrianzoNative")
        loadInstallReferrer()
        setupPush()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            // Reels start muted, then try to unmute on their own. By default
            // WebView blocks sound for videos that weren't started by a tap,
            // so some reels stayed muted. Allow autoplay with sound.
            mediaPlaybackRequiresUserGesture = false
        }
        webView.settings.setGeolocationEnabled(true)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (!wantsAudio) {
                        request.deny()
                        return@runOnUiThread
                    }

                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        return@runOnUiThread
                    }

                    pendingWebAudioPermissionRequest?.deny()
                    pendingWebAudioPermissionRequest = request
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                val hasFine = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (hasFine || hasCoarse) {
                    callback.invoke(origin, true, false)
                    return
                }
                pendingGeolocationRequest?.second?.invoke(pendingGeolocationRequest?.first ?: origin, false, false)
                pendingGeolocationRequest = origin to callback
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                // When the web page's <input capture> attribute asks for a direct
                // camera capture (our "Camera" button), launch the camera app
                // itself instead of the normal file/gallery picker.
                val acceptTypes = fileChooserParams.acceptTypes
                val wantsVideo = acceptTypes.any { it.startsWith("video/") }
                val wantsImage = acceptTypes.any { it.startsWith("image/") }
                if (fileChooserParams.isCaptureEnabled && (wantsVideo || wantsImage)) {
                    try {
                        val extension = if (wantsVideo) "mp4" else "jpg"
                        val file = File.createTempFile("capture_", ".$extension", cacheDir)
                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                        val captureAction = if (wantsVideo) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE
                        val captureIntent = Intent(captureAction).apply {
                            putExtra(MediaStore.EXTRA_OUTPUT, uri)
                            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        cameraCaptureUri = uri
                        fileChooserLauncher.launch(captureIntent)
                        return true
                    } catch (e: Exception) {
                        cameraCaptureUri = null
                        Log.w(TAG, "Direct camera capture failed, falling back to chooser", e)
                    }
                }

                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, _, mimeType, _ ->
            enqueueReelDownload(url, userAgent, mimeType)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                updateSwipeRefreshForUrl(request.url)
                return handleUrl(request.url)
            }

            @Deprecated("Deprecated in API 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val uri = Uri.parse(url)
                updateSwipeRefreshForUrl(uri)
                return handleUrl(uri)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!url.isNullOrEmpty()) updateSwipeRefreshForUrl(Uri.parse(url))
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrEmpty()) updateSwipeRefreshForUrl(Uri.parse(url))
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrEmpty()) updateSwipeRefreshForUrl(Uri.parse(url))
                swipeRefresh.isRefreshing = false
                tryClaimReferral()
            }
        }

        // The web page's own header (Frianzo logo bar) and 4-icon nav
        // bar sit inside the WebView's HTML, so the native pull-to-refresh
        // spinner doesn't know about them by default and starts at the very
        // top of the screen. Push it down below that chrome, like Facebook.
        run {
            val density = resources.displayMetrics.density
            val chromeHeightDp = 120 // approx height of the header + 4-icon nav bar
            val startOffsetPx = (chromeHeightDp * density).toInt()
            val endOffsetPx = ((chromeHeightDp + 64) * density).toInt()
            swipeRefresh.setProgressViewOffset(false, startOffsetPx, endOffsetPx)
        }

        swipeRefresh.setOnRefreshListener { webView.reload() }

        if (savedInstanceState == null) {
            val oauthUri = intent?.data
            if (oauthUri != null && isOAuthCallback(oauthUri)) {
                webView.post { handleOAuthCallback(oauthUri) }
            } else {
                webView.loadUrl(intentTargetUrl(intent) ?: defaultStartUrl())
            }
        }
    }

    private fun enqueueReelDownload(
        url: String,
        userAgent: String? = null,
        mimeType: String? = null
    ): Boolean {
        return try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType ?: "video/mp4")
                setTitle("Frianzo video")
                setDescription("Saving video from Frianzo")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Frianzo-" + System.currentTimeMillis() + ".mp4")
                userAgent?.let { addRequestHeader("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)?.let { cookies ->
                    if (cookies.isNotBlank()) addRequestHeader("Cookie", cookies)
                }
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)
            runOnUiThread { Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show() }
            monitorReelDownload(downloadId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Video save failed", e)
            runOnUiThread { Toast.makeText(this, "Unable to save video", Toast.LENGTH_SHORT).show() }
            false
        }
    }

    private fun monitorReelDownload(downloadId: Long) {
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val handler = android.os.Handler(mainLooper)
        val poller = object : Runnable {
            override fun run() {
                val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
                cursor.use {
                    if (!it.moveToFirst()) { dispatchDownloadEvent(downloadId, "error", 0); return }
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val soFar = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val progress = if (total > 0) ((soFar * 100L) / total).toInt().coerceIn(0, 100) else 0
                    when (status) {
                        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> {
                            dispatchDownloadEvent(downloadId, "progress", progress)
                            handler.postDelayed(this, 300)
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> dispatchDownloadEvent(downloadId, "complete", 100)
                        DownloadManager.STATUS_FAILED -> dispatchDownloadEvent(downloadId, "error", progress)
                        else -> handler.postDelayed(this, 500)
                    }
                }
            }
        }
        handler.post(poller)
    }

    private fun dispatchDownloadEvent(downloadId: Long, status: String, progress: Int) {
        val script = "window.dispatchEvent(new CustomEvent('frianzo-download-progress',{detail:{id:" + downloadId + ",status:'" + status + "',progress:" + progress + "}}));"
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun loadInstallReferrer() {
        lifecycleScope.launch(Dispatchers.IO) {
            val client = InstallReferrerClient.newBuilder(this@MainActivity).build()
            try {
                client.startConnection(object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                            try {
                                installReferrer = client.installReferrer.installReferrer
                                Log.i(TAG, "Play Install Referrer loaded")
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not read Play Install Referrer", e)
                            }
                        } else {
                            Log.i(TAG, "Play Install Referrer unavailable: $responseCode")
                        }
                        client.endConnection()
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        Log.i(TAG, "Play Install Referrer service disconnected")
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Play Install Referrer setup failed", e)
                try { client.endConnection() } catch (_: Exception) {}
            }
        }
    }

    private fun getReferralDeviceFingerprint(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val input = "com.frianzo.app:$androidId"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun tryClaimReferral() {
        if (referralClaimed) return
        val referrer = installReferrer?.trim().orEmpty()
        if (referrer.isEmpty()) return

        lifecycleScope.launch {
            try {
                val authToken = getCookieValue("vynzo_auth_token") ?: getCookieValue("vynzo_token")
                if (authToken.isNullOrEmpty()) return@launch
                val accepted = withContext(Dispatchers.IO) {
                    claimReferralOnBackend(authToken, referrer, getReferralDeviceFingerprint())
                }
                if (accepted) {
                    referralClaimed = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Referral claim failed; will retry on next page", e)
            }
        }
    }

    private fun claimReferralOnBackend(authToken: String, referrer: String, deviceFingerprint: String): Boolean {
        val connection = (URL("$API_URL/api/users/me/referral/claim").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Cookie", "vynzo_auth_token=$authToken")
            setRequestProperty("Origin", SITE_URL)
        }

        val body = JSONObject().apply {
            put("installReferrer", referrer)
            put("deviceFingerprint", deviceFingerprint)
        }.toString()

        return try {
            connection.outputStream.bufferedWriter().use { it.write(body) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                Log.w(TAG, "Referral claim HTTP $responseCode: $response")
                return false
            }
            val data = JSONObject(response).optJSONObject("data") ?: return false
            val accepted = data.optBoolean("accepted", false)
            if (!accepted) {
                val reason = data.optString("reason")
                if (reason == "ACCOUNT_ALREADY_REFERRED" || reason == "DEVICE_ALREADY_REFERRED" || reason == "REFERRAL_ALREADY_CLAIMED") {
                    referralClaimed = true
                }
            }
            accepted || referralClaimed
        } finally {
            connection.disconnect()
        }
    }

    private fun setupPush() {
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("FrianzoPush", "FCM token fetch failed", task.exception)
                return@addOnCompleteListener
            }
            pushToken = task.result ?: ""
            webView.post {
                webView.evaluateJavascript("window.dispatchEvent(new Event('frianzo-push-token'))", null)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("frianzo_default", "Notifications", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notificationTargetUrl(intent: Intent?): String? {
        val path = intent?.getStringExtra("url") ?: return null
        return if (path.startsWith("/") && !path.startsWith("//")) "$SITE_URL$path" else null
    }

    /**
     * Where an incoming intent wants the WebView to go. Notification taps carry
     * a "url" path extra; app shortcuts and app links arrive as a VIEW data Uri
     * for a frianzo.online page. Returns null for anything else (including the
     * frianzo:// OAuth callback, which is handled separately).
     */
    private fun intentTargetUrl(intent: Intent?): String? {
        notificationTargetUrl(intent)?.let { return it }
        val data = intent?.data ?: return null
        val scheme = data.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = data.host?.lowercase() ?: return null
        if (host != SITE_HOST && host != "www.$SITE_HOST") return null
        val path = data.path ?: return null
        if (!path.startsWith("/") || path.startsWith("//")) return null
        val query = data.encodedQuery
        return "$SITE_URL$path" + (if (query != null) "?$query" else "")
    }

    /**
     * Whether the launch UI should use the dark variant. Reads the theme the
     * web app last reported via NativeBridge.setAppTheme(); on first run
     * (nothing reported yet) falls back to the system night mode, mirroring
     * the web ThemeProvider's 'system' behavior.
     */
    private fun isDarkTheme(): Boolean {
        when (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_APP_THEME, null)) {
            "dark" -> return true
            "light" -> return false
        }
        val nightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMask == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Start on /login when there is no auth session, so a logged-out user never
     * sees the SSR marketing page flash inside the app's WebView before the
     * web client redirects. Logged-in users still land on the feed via SITE_URL.
     */
    private fun defaultStartUrl(): String =
        if (hasAuthSession()) SITE_URL else "$SITE_URL/login"

    private fun hasAuthSession(): Boolean =
        !getCookieValue("vynzo_auth_token").isNullOrBlank() ||
            !getCookieValue("vynzo_token").isNullOrBlank()

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentTargetUrl(intent)?.let { webView.loadUrl(it) }
        val oauthUri = intent?.data
        if (oauthUri != null && isOAuthCallback(oauthUri)) {
            handleOAuthCallback(oauthUri)
        }
    }

    private fun updateSwipeRefreshForUrl(uri: Uri) {
        val isFrianzoHost = uri.scheme.equals("https", ignoreCase = true) &&
            (uri.host.equals(SITE_HOST, ignoreCase = true) ||
                uri.host.equals("www.$SITE_HOST", ignoreCase = true))
        val isReelsSection = isFrianzoHost &&
            (uri.path == "/reels" || uri.path?.startsWith("/reels/") == true)

        // Reel feed uses vertical swipe navigation. Disable pull-to-refresh only
        // while the WebView is inside the reels section so a downward reel swipe
        // can never trigger SwipeRefreshLayout.reload(). Other app pages keep
        // their existing pull-to-refresh behavior.
        swipeRefresh.isEnabled = !isReelsSection
        if (isReelsSection) swipeRefresh.isRefreshing = false
    }

    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase()
        val path = uri.path

        if ((scheme == "http" || scheme == "https") &&
            (host == SITE_HOST || host == "www.$SITE_HOST")
        ) return false

        if (scheme == "https" && host == API_HOST && path == GOOGLE_START_PATH) {
            Log.i(TAG, "Intercepted Google start URL, launching native sign-in")
            launchNativeGoogleSignIn()
            return true
        }

        // Keep reel downloads inside the Android app instead of opening Chrome.
        if (scheme == "https" && host == API_HOST &&
            path?.startsWith("/api/reels/") == true && path.endsWith("/download")) {
            Log.i(TAG, "Intercepted reel download URL, starting native download")
            enqueueReelDownload(uri.toString())
            return true
        }

        try {
            when (scheme) {
                "http", "https" -> return openExternal(uri)
                "whatsapp" -> return openExternal(uri)
                "intent" -> {
                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrEmpty()) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
                        }
                    }
                    return true
                }
                "market" -> {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    return true
                }
                "tel", "mailto" -> {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    return true
                }
                else -> {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    return true
                }
            }
        } catch (e: Exception) {
            if (scheme == "http" || scheme == "https") {
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
            }
            return true
        }
    }

    private suspend fun getGoogleCredential(serverClientId: String): GoogleCredentialResult {
        // The website's Google button can reach accounts that require reauthentication.
        // Android's documented Sign in with Google button flow is intended for that case.
        val nonce = generateSecureRandomNonce()
        val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(serverClientId)
            .setNonce(nonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .build()

        Log.i(TAG, "Requesting Google Sign in button flow")
        return try {
            val response = credentialManager.getCredential(
                request = request,
                context = MutableContextWrapper(this@MainActivity)
            )
            Log.i(TAG, "Google Sign in button flow succeeded")
            GoogleCredentialResult(response, nonce)
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Google Sign in button flow failed; trying all-accounts Google ID flow", e)

            val fallbackNonce = generateSecureRandomNonce()
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .setNonce(fallbackNonce)
                .build()
            val fallbackRequest = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            try {
                val response = credentialManager.getCredential(
                    request = fallbackRequest,
                    context = MutableContextWrapper(this@MainActivity)
                )
                Log.i(TAG, "All-accounts Google ID fallback succeeded")
                GoogleCredentialResult(response, fallbackNonce)
            } catch (fallbackError: GetCredentialException) {
                Log.e(TAG, "Google ID fallback failed: " + fallbackError::class.java.name + ": " + fallbackError.message, fallbackError)
                throw IllegalStateException(
                    "Google Credential Manager failed in both flows. Primary: " +
                        e::class.java.simpleName + ": " + e.message +
                        "; Fallback: " + fallbackError::class.java.simpleName + ": " + fallbackError.message,
                    fallbackError
                )
            }
        }
    }

    private fun launchNativeGoogleSignIn() {
        lifecycleScope.launch {
            swipeRefresh.isRefreshing = false
            try {
                Log.i(TAG, "Step 1: Fetching server client ID")
                val serverClientId = withContext(Dispatchers.IO) { fetchGoogleServerClientId() }
                require(serverClientId.isNotBlank()) { "Google server client ID is empty" }
                Log.i(TAG, "Step 1 OK: clientId length=${serverClientId.length}")
                Log.i(TAG, "Step 2: Requesting Google credential")
                val credentialResult = getGoogleCredential(serverClientId)
                val credential = credentialResult.response.credential
                val nonce = credentialResult.nonce
                Log.i(TAG, "Step 2 OK: credential type=${credential.type}")

                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    throw IllegalStateException("Unsupported Google credential type: ${credential.type}")
                }

                val googleCredential = try {
                    GoogleIdTokenCredential.createFrom(credential.data)
                } catch (e: GoogleIdTokenParsingException) {
                    throw IllegalStateException("Invalid Google ID credential", e)
                }
                Log.i(TAG, "Step 3: Got ID token, calling backend /native")

                val deviceToken = getCookieValue("vynzo_device")
                val loginResult = withContext(Dispatchers.IO) {
                    nativeGoogleLogin(
                        idToken = googleCredential.idToken,
                        nonce = nonce,
                        deviceToken = deviceToken
                    )
                }
                Log.i(TAG, "Step 4 OK: backend auth success, isNewUser=${loginResult.isNewUser}")

                setAuthCookies(loginResult.token, loginResult.deviceToken)
                val destination = if (loginResult.isNewUser) "/profile-setup" else "/"
                webView.loadUrl("$SITE_URL$destination")
                Log.i(TAG, "Step 5: Cookies set, navigating to $destination")
            } catch (e: Exception) {
                val cause = generateDiagnosticMessage(e)
                Log.e(TAG, "Native Google sign-in failed: $cause", e)
                Toast.makeText(this@MainActivity, cause, Toast.LENGTH_LONG).show()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Google Login Failed")
                    .setMessage(cause)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun generateDiagnosticMessage(error: Throwable): String {
        return buildString {
            append("Message: ")
            append(error.message ?: error.localizedMessage ?: "<none>")
            append("\nCause: ")
            append(error.cause?.message ?: "<none>")
            append("\nException: ")
            append(error.toString())
        }.take(2000)
    }

    private fun fetchGoogleServerClientId(): String {
        val connection = (URL("$API_URL$GOOGLE_CONFIG_PATH").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Origin", SITE_URL)
        }

        return try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Empty Google config response (HTTP $responseCode)")
            if (responseCode !in 200..299) {
                throw IllegalStateException("Google config request failed: HTTP $responseCode $response")
            }
            val root = JSONObject(response)
            root.getJSONObject("data").getString("clientId")
        } finally {
            connection.disconnect()
        }
    }

    private fun nativeGoogleLogin(idToken: String, nonce: String, deviceToken: String?): NativeLoginResult {
        val connection = (URL("$API_URL$GOOGLE_NATIVE_LOGIN_PATH").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", SITE_URL)
        }

        val body = JSONObject().apply {
            put("idToken", idToken)
            put("nonce", nonce)
            if (!deviceToken.isNullOrEmpty()) put("deviceToken", deviceToken)
        }.toString()

        return try {
            connection.outputStream.bufferedWriter().use { it.write(body) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Empty authentication response (HTTP $responseCode)")

            if (responseCode !in 200..299) {
                throw IllegalStateException("Google authentication failed: HTTP $responseCode $response")
            }

            val data = JSONObject(response).getJSONObject("data")
            NativeLoginResult(
                token = data.getString("token"),
                deviceToken = data.getString("deviceToken"),
                isNewUser = data.getBoolean("isNewUser")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun setAuthCookies(token: String, deviceToken: String) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val opts = "Path=/; Secure; SameSite=None; Domain=.frianzo.online"

        cookieManager.setCookie(API_URL, "vynzo_auth_token=$token; $opts")
        cookieManager.setCookie(API_URL, "vynzo_token=$token; $opts")
        cookieManager.setCookie(API_URL, "vynzo_device=$deviceToken; $opts")
        cookieManager.setCookie(SITE_URL, "vynzo_auth_token=$token; $opts")
        cookieManager.setCookie(SITE_URL, "vynzo_token=$token; $opts")
        cookieManager.setCookie(SITE_URL, "vynzo_device=$deviceToken; $opts")
        cookieManager.flush()
    }

    private fun getCookieValue(name: String): String? {
        val cookieManager = CookieManager.getInstance()
        val fromApi = cookieManager.getCookie(API_URL)
        val fromSite = cookieManager.getCookie(SITE_URL)
        val all = listOfNotNull(fromApi, fromSite).joinToString(";")
        if (all.isBlank()) return null
        return all.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
    }

    private fun generateSecureRandomNonce(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    private fun openExternal(uri: Uri): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (_: ActivityNotFoundException) { false }

    private fun isOAuthCallback(uri: Uri): Boolean =
        uri.scheme.equals(OAUTH_SCHEME, ignoreCase = true) &&
            uri.host.equals(OAUTH_HOST, ignoreCase = true) &&
            uri.path.equals("/callback", ignoreCase = true)

    private fun handleOAuthCallback(uri: Uri) {
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrEmpty()) {
            webView.loadUrl("$SITE_URL/login?error=google_auth_failed")
            return
        }

        val token = uri.getQueryParameter("token")
        val deviceToken = uri.getQueryParameter("device")
        val isNewUser = uri.getQueryParameter("newUser") == "1"

        if (token.isNullOrEmpty() || deviceToken.isNullOrEmpty()) {
            webView.loadUrl("$SITE_URL/login?error=google_auth_failed")
            return
        }

        setAuthCookies(token, deviceToken)
        val destination = if (isNewUser) "/profile-setup" else "/"
        webView.loadUrl("$SITE_URL$destination")
    }

    private data class GoogleCredentialResult(
        val response: androidx.credentials.GetCredentialResponse,
        val nonce: String
    )

    private data class NativeLoginResult(
        val token: String,
        val deviceToken: String,
        val isNewUser: Boolean
    )

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }
}
