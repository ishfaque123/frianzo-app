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
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.core.content.FileProvider
import androidx.activity.OnBackPressedCallback
import java.io.File
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.ProgressBar
import android.widget.Toast
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import android.view.View
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.firebase.messaging.FirebaseMessaging
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var credentialManager: CredentialManager
    private lateinit var loadingOverlay: View
    private val dotAnimators = mutableListOf<ObjectAnimator>()
    private var loadingOverlayHidden = false
    // FB-style offline recovery: while our own load-error screen is up we
    // remember the failed URL so the app can reload it by itself the moment
    // the network comes back (and pull-to-refresh retries the same page).
    @Volatile private var loadErrorShowing = false
    @Volatile private var loadErrorUrl: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
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
    private var pendingWebCameraPermissionRequest: PermissionRequest? = null
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val request = pendingWebCameraPermissionRequest
            pendingWebCameraPermissionRequest = null
            val cameraGranted = grants[Manifest.permission.CAMERA] == true
            if (cameraGranted && request != null) {
                // Grant both video and audio if both were requested
                val resources = request.resources.filter {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }.toTypedArray()
                request.grant(resources)
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
        fun isBiometricAvailable(): Boolean {
            val mgr = BiometricManager.from(this@MainActivity)
            return mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }

        @JavascriptInterface
        fun isBiometricEnabled(): Boolean {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_BIOMETRIC_ENABLED, false)
        }

        @JavascriptInterface
        fun setBiometricEnabled(enabled: Boolean) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
        }

        @JavascriptInterface
        fun authenticateWithBiometric() {
            runOnUiThread { showBiometricPrompt() }
        }

        @JavascriptInterface
        fun startReelDownload(url: String): Boolean {
            return enqueueReelDownload(url)
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new Event('frianzo-biometric-success'))", null)
                }
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new Event('frianzo-biometric-failed'))", null)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new Event('frianzo-biometric-error'))", null)
                }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Frianzo Login")
            .setSubtitle("Confirm your fingerprint to log in")
            .setNegativeButtonText("Use password instead")
            .build()
        prompt.authenticate(promptInfo)
    }

    private var pendingStartUrl: String? = null

    private fun isBiometricEnabledForLaunch(): Boolean {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }

    private fun showBiometricPromptForLaunch() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    pendingStartUrl?.let { webView.loadUrl(it) }
                    pendingStartUrl = null
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Cancel/error: wipe ONLY the login session (device cookie stays),
                    // then open password login — it cannot auto-login now
                    pendingStartUrl = null
                    val cookieManager = CookieManager.getInstance()
                    val expire = "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; SameSite=None; Domain=.frianzo.online"
                    cookieManager.setCookie(API_URL, "vynzo_auth_token=; $expire")
                    cookieManager.setCookie(API_URL, "vynzo_token=; $expire")
                    cookieManager.setCookie(SITE_URL, "vynzo_auth_token=; $expire")
                    cookieManager.setCookie(SITE_URL, "vynzo_token=; $expire")
                    cookieManager.flush()
                    webView.loadUrl("$SITE_URL/login")
                }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Frianzo")
            .setSubtitle("Unlock with your fingerprint")
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(promptInfo)
    }

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** Picked videos at or above this size are compressed on-device before upload. */
    private val videoCompressMinBytes = 8L * 1024 * 1024
    private var cameraCaptureUri: Uri? = null

    /** A file staged from an incoming Android share-sheet intent, served to the
     * WebView at /__share/<token> so the compose page can attach it. */
    private data class StagedShare(val file: File, val mimeType: String, val name: String)
    private val stagedShares = ConcurrentHashMap<String, StagedShare>()
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val capturedUri = cameraCaptureUri
        cameraCaptureUri = null
        val uris = if (capturedUri != null) {
            // We launched a direct camera-capture intent (not the standard
            // document/gallery chooser), so the result doesn't come back in
            // the format FileChooserParams.parseResult() expects. The file
            // was written straight to the Uri we handed the camera app.
            if (result.resultCode == RESULT_OK) arrayOf(capturedUri) else null
        } else {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        }
        deliverChooserResult(uris)
    }

    /**
     * Hands a file-chooser result to the web page. A single picked video of
     * 8 MB or more is first compressed on the device (720p box) so uploads
     * cost far less data; the server still compresses again as a safety net.
     * Fail-safe: ANY problem (unknown size/type, transcode error, user
     * cancel) delivers the ORIGINAL selection untouched.
     */
    private fun deliverChooserResult(uris: Array<Uri>?) {
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return
        if (uris == null || uris.size != 1) {
            callback.onReceiveValue(uris)
            return
        }
        val uri = uris[0]
        val mime = try { contentResolver.getType(uri) } catch (e: Exception) { null }
        if (mime == null || !mime.startsWith("video/")) {
            callback.onReceiveValue(uris)
            return
        }
        val size = queryUriSize(uri)
        if (size < videoCompressMinBytes) {
            callback.onReceiveValue(uris)
            return
        }
        compressVideoThenDeliver(uri, size, callback)
    }

    private fun queryUriSize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private fun compressVideoThenDeliver(source: Uri, sourceSize: Long, callback: ValueCallback<Array<Uri>>) {
        val outFile = File(cacheDir, "compressed_${System.currentTimeMillis()}.mp4")
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(56, 40, 56, 8)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Compressing video\u2026")
            .setView(bar)
            .setCancelable(true)
            .create()
        var delivered = false
        fun deliver(result: Array<Uri>?) {
            if (delivered) return
            delivered = true
            try { if (dialog.isShowing) dialog.dismiss() } catch (e: Exception) { }
            callback.onReceiveValue(result)
        }
        val future = Transcoder.into(outFile.absolutePath)
            .addDataSource(this, source)
            .setVideoTrackStrategy(DefaultVideoStrategy.atMost(720, 1280).build())
            .setListener(object : TranscoderListener {
                override fun onTranscodeProgress(progress: Double) {
                    runOnUiThread { bar.progress = (progress * 100).toInt() }
                }
                override fun onTranscodeCompleted(successCode: Int) {
                    runOnUiThread {
                        val smaller = outFile.exists() && outFile.length() > 0 && outFile.length() < sourceSize
                        if (smaller) {
                            try {
                                val outUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", outFile)
                                deliver(arrayOf(outUri))
                            } catch (e: Exception) {
                                deliver(arrayOf(source))
                            }
                        } else {
                            deliver(arrayOf(source))
                        }
                    }
                }
                override fun onTranscodeCanceled() {
                    runOnUiThread { deliver(arrayOf(source)) }
                }
                override fun onTranscodeFailed(exception: Throwable) {
                    Log.w(TAG, "Video compression failed; using original", exception)
                    runOnUiThread { deliver(arrayOf(source)) }
                }
            })
            .transcode()
        dialog.setOnCancelListener {
            try { future.cancel(true) } catch (e: Exception) { }
            deliver(arrayOf(source))
        }
        if (!isFinishing && !isDestroyed) {
            try { dialog.show() } catch (e: Exception) { }
        }
    }

    companion object {
        private const val TAG = "FrianzoGoogleAuth"
        private const val SITE_URL = "https://frianzo.online"
        private const val LOAD_ERROR_MARKER = "__frianzo_load_error"
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
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_REFERRAL_CLAIMED = "referral_claimed"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Pick the launch theme from the web theme the app last reported
        // ('dark'/'light'), falling back to the system night mode on first
        // run. Must run before super.onCreate() so the window is created with
        // the theme-matching splash instead of the static manifest one.
        val darkTheme = isDarkTheme()
        // Only use splash theme on fresh launch. On recreation (e.g. returning
        // from gallery picker), use regular theme to avoid logo flash.
        if (savedInstanceState == null) {
            setTheme(if (darkTheme) R.style.Theme_Frianzo_Splash_Dark else R.style.Theme_Frianzo_Splash_Light)
        } else {
            setTheme(if (darkTheme) R.style.Theme_Frianzo_Dark else R.style.Theme_Frianzo_Light)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Restore the referral marker: without this, every cold start re-posts
        // the claim (the server rejects it, but it is a wasted request).
        referralClaimed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_REFERRAL_CLAIMED, false)

        webView = findViewById(R.id.webview)

        // BACK (Android 16 / targetSdk 36): predictive back is on by default and
        // KEYCODE_BACK is no longer delivered to onKeyDown, so back must go
        // through the dispatcher or the app closes instead of going back in
        // web history.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        webView.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (darkTheme) R.color.frianzo_dark_bg else android.R.color.white
            )
        )
        swipeRefresh = findViewById(R.id.swipe_refresh)
        loadingOverlay = findViewById(R.id.loading_overlay)
        loadingOverlay.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (darkTheme) R.color.frianzo_dark_bg else android.R.color.white
            )
        )
        startLoadingDots()
        // Safety: never leave the loading overlay covering the app if the
        // first page load somehow never reports finished.
        loadingOverlay.postDelayed({ hideLoadingOverlay() }, 15000)
        credentialManager = CredentialManager.create(this)
        webView.addJavascriptInterface(NativeBridge(), "FrianzoNative")
        loadInstallReferrer()
        setupPush()
        registerNetworkRecoveryCallback()

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
            // PERFORMANCE (2026-10-17): reduce glitching on low-end devices
            loadsImagesAutomatically = true
            blockNetworkImage = false
            // Enable smooth scrolling and hardware rendering
            setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
        }
        webView.settings.setGeolocationEnabled(true)
        // PERFORMANCE: force hardware layer for smoother scrolling/video
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        // Disable native long-press context menu (link/image popup).
        // The web app handles long-press itself via JavaScript touch events
        // (e.g. post description copy, message action menu).
        webView.setOnLongClickListener { true }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    val wantsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (!wantsAudio && !wantsVideo) {
                        request.deny()
                        return@runOnUiThread
                    }

                    // Camera requested — handle video (+audio if both)
                    if (wantsVideo) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            val toGrant = request.resources.filter {
                                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                            }.toTypedArray()
                            // Only grant audio too if we already have mic permission
                            val finalGrant = toGrant.filter {
                                it != PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            }.toTypedArray()
                            request.grant(if (finalGrant.isNotEmpty()) finalGrant else arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                            return@runOnUiThread
                        }
                        pendingWebCameraPermissionRequest?.deny()
                        pendingWebCameraPermissionRequest = request
                        cameraPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
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
                    // Use a clean GET_CONTENT intent instead of createIntent()
                    // to avoid the confusing app-branded picker overlay
                    val acceptTypes = fileChooserParams.acceptTypes
                    val mimeType = when {
                        acceptTypes.any { it.startsWith("video/") } && acceptTypes.any { it.startsWith("image/") } -> "image/*,video/*"
                        acceptTypes.any { it.startsWith("video/") } -> "video/*"
                        else -> "image/*"
                    }
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = if (mimeType.contains(",")) "*/*" else mimeType
                        if (mimeType.contains(",")) {
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                        }
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    fileChooserLauncher.launch(intent)
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
                if (!url.isNullOrEmpty()) {
                    updateSwipeRefreshForUrl(Uri.parse(url))
                    val started = Uri.parse(url)
                    if (started.scheme == "https" && started.host == API_HOST &&
                        started.path?.startsWith("/api/auth/google") == true) {
                        markOAuthInProgress()
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // A real content page finished loading: the load-error state
                // is over. The error page itself reports the site root URL
                // (its base URL), so the root is not treated as recovery.
                if (loadErrorShowing && !url.isNullOrEmpty() &&
                    url != SITE_URL && url != "$SITE_URL/" && !url.contains(LOAD_ERROR_MARKER)
                ) {
                    loadErrorShowing = false
                    loadErrorUrl = null
                }
                if (!url.isNullOrEmpty()) updateSwipeRefreshForUrl(Uri.parse(url))
                swipeRefresh.isRefreshing = false
                hideLoadingOverlay()
                tryClaimReferral()
                // Proactively request camera permission on camera page so that
                // when the website calls getUserMedia, the permission is already
                // granted and onPermissionRequest can immediately grant it.
                // This fixes the "Camera access denied" timing issue.
                if (!url.isNullOrEmpty() && url.contains("/reels/camera")) {
                    val cameraGranted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    val micGranted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!cameraGranted || !micGranted) {
                        cameraPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }
                }
                // After first page load, replace the splash window background with a
                // plain color so the Frianzo logo never flashes when returning from
                // external activities (e.g. gallery picker).
                window.setBackgroundDrawableResource(
                    if (isDarkTheme()) R.color.frianzo_dark_bg else android.R.color.white
                )
            }

            // Auto-retry on network errors (fixes net::ERR_FAILED permanent)
            private var retryCount = 0
            private var lastFailedUrl: String? = null

            // Renderer-crash loop guard: same idea as the network retry limit
            // above — a few reloads are fine, endless crash -> reload is not.
            private var rendererCrashCount = 0
            private var lastRendererCrashMs = 0L

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    val url = request.url.toString()
                    val errorCode = error.errorCode
                    // Retry on network errors: ERR_FAILED, timeouts, connection issues
                    if (errorCode == android.webkit.WebViewClient.ERROR_CONNECT ||
                        errorCode == android.webkit.WebViewClient.ERROR_TIMEOUT ||
                        errorCode == android.webkit.WebViewClient.ERROR_HOST_LOOKUP ||
                        errorCode == android.webkit.WebViewClient.ERROR_UNKNOWN
                    ) {
                        if (url == lastFailedUrl) retryCount++ else { retryCount = 1; lastFailedUrl = url }
                        if (retryCount <= 3) {
                            val delay = when (retryCount) { 1 -> 2000L; 2 -> 5000L; else -> 10000L }
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                view.loadUrl(url)
                            }, delay)
                            return // Don't show error page, retrying
                        }
                    }
                    retryCount = 0
                    lastFailedUrl = null
                    // Retries exhausted: FB-style recovery — our own error screen
                    // with a Retry button instead of WebView's dead net::ERR_*
                    // page, and stop the refresh spinner so it never freezes.
                    swipeRefresh.isRefreshing = false
                    this@MainActivity.showLoadErrorPage(view, url)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // WebView renderer crashed - reload a few times, then stop:
                // without a limit, a page that crashes on load loops forever
                // (crash -> reload -> crash). After 3 crashes within 60s,
                // show the error screen and let the user retry manually.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastRendererCrashMs > 60_000L) rendererCrashCount = 0
                lastRendererCrashMs = now
                rendererCrashCount++
                if (rendererCrashCount > 3) {
                    rendererCrashCount = 0
                    Log.e(TAG, "WebView renderer crashed repeatedly, showing error page")
                    swipeRefresh.isRefreshing = false
                    this@MainActivity.showLoadErrorPage(view, SITE_URL)
                    return true
                }
                Log.e(TAG, "WebView renderer crashed, reloading")
                try {
                    view.loadUrl(SITE_URL)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reload WebView", e)
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // Serve files staged from an incoming share-sheet intent so the
                // compose page can fetch and attach them: /__share/<token>.
                // Same-origin with the site, so no CORS issues.
                val url = request.url
                if (url.scheme == "https" && url.host == SITE_HOST &&
                    url.pathSegments.size >= 2 && url.pathSegments[0] == "__share"
                ) {
                    val staged = stagedShares[url.pathSegments[1]] ?: return null
                    return try {
                        WebResourceResponse(staged.mimeType, null, staged.file.inputStream())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to serve staged share", e)
                        null
                    }
                }
                return null
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

        swipeRefresh.setOnRefreshListener {
            // While the load-error screen is up, pull-to-refresh must retry
            // the FAILED page — reloading would just reload the error page.
            val failed = loadErrorUrl
            if (loadErrorShowing && !failed.isNullOrBlank()) {
                loadErrorShowing = false
                loadErrorUrl = null
                webView.loadUrl(failed)
            } else {
                webView.reload()
            }
        }

        // Check for app updates in background
        checkForAppUpdate()

        if (savedInstanceState == null) {
            val action = intent?.action
            if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
                intent?.let { handleShareIntent(it) }
            } else {
                val oauthUri = intent?.data
                if (oauthUri != null && isOAuthCallback(oauthUri)) {
                    webView.post { handleOAuthCallback(oauthUri) }
                } else {
                    val startUrl = notificationTargetUrl(intent) ?: defaultStartUrl()
                    // If biometric login is enabled, ask for fingerprint before loading
                    if (isBiometricEnabledForLaunch()) {
                        pendingStartUrl = startUrl
                        showBiometricPromptForLaunch()
                    } else {
                        webView.loadUrl(startUrl)
                    }
                }
            }
        }
    }

    /**
     * FB-style reconnect: when the network comes back while our load-error
     * screen is showing, reload the failed page automatically — the user
     * does not have to tap Retry. Registered once for the activity.
     */
    private fun registerNetworkRecoveryCallback() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread {
                        val target = loadErrorUrl
                        if (loadErrorShowing && !target.isNullOrBlank()) {
                            // Only auto-reload while the error screen is still
                            // up (it reports the site root as its URL). If the
                            // user already navigated to a real page, skip.
                            val current = try { webView.url } catch (e: Exception) { null }
                            val stillOnError = current == null || current == SITE_URL ||
                                current == "$SITE_URL/" || current.contains(LOAD_ERROR_MARKER)
                            if (stillOnError) {
                                loadErrorShowing = false
                                loadErrorUrl = null
                                Log.i(TAG, "Network is back - auto-reloading failed page")
                                try { webView.loadUrl(target) } catch (e: Exception) {
                                    Log.e(TAG, "Auto-reload after reconnect failed", e)
                                }
                            }
                        }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network recovery callback", e)
        }
    }

    override fun onDestroy() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val callback = networkCallback
            if (cm != null && callback != null) cm.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // Never let cleanup crash the activity teardown.
        }
        networkCallback = null
        super.onDestroy()
    }

    /**
     * FB-style load-failure screen: our own dark page with a Retry button,
     * shown when a page load fails after auto-retries are exhausted — instead
     * of WebView's dead net::ERR_* page (which made the app look frozen).
     * The Retry link reloads the failed URL inside the WebView (same-host
     * links are allowed through handleUrl).
     */
    private fun showLoadErrorPage(view: WebView, failedUrl: String) {
        loadErrorShowing = true
        loadErrorUrl = failedUrl
        val retryUrl = failedUrl
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { margin:0; font-family: sans-serif; background:#0f172a; color:#e2e8f0;
                     display:flex; align-items:center; justify-content:center;
                     min-height:100vh; text-align:center; padding:24px; box-sizing:border-box; }
              .logo { font-size:24px; font-weight:800; color:#3b82f6; letter-spacing:-0.5px; margin-bottom:28px; }
              h1 { font-size:20px; margin:20px 0 8px; font-weight:700; }
              p { color:#94a3b8; font-size:14px; line-height:1.55; margin:0 0 10px; }
              p.small { font-size:12.5px; color:#64748b; margin:0 0 28px; }
              a.btn { display:inline-block; background:#2563eb; color:#ffffff; text-decoration:none;
                      padding:12px 38px; border-radius:999px; font-weight:600; font-size:15px; }
            </style>
            </head>
            <body>
              <div>
                <div class="logo">Frianzo</div>
                <svg width="76" height="76" viewBox="0 0 24 24" fill="none" stroke="#64748b" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="M22.61 16.95A5 5 0 0 0 18 10h-1.26a8 8 0 0 0-7.05-6M5 5a8 8 0 0 0 4 15h9a5 5 0 0 0 1.7-.3"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                <h1>No internet connection</h1>
                <p>Make sure Wi-Fi or mobile data is turned on,<br>then try again.</p>
                <p class="small">Frianzo will reconnect on its own when your internet returns.</p>
                <a class="btn" href="$retryUrl">Retry</a>
              </div>
            </body>
            </html>
        """.trimIndent()
        try {
            view.loadDataWithBaseURL("$SITE_URL/#$LOAD_ERROR_MARKER", html, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show load-error page", e)
        }
    }

    /** Check GitHub for a newer APK version. Shows update dialog if available.
     *  Skipped for Play Store installs - Play Store handles its own updates. */
    private fun checkForAppUpdate() {
        // Play Store installs get updates from Play Store, not GitHub
        val installer = packageManager.getInstallerPackageName(packageName)
        if (installer == "com.android.vending") {
            Log.d("Frianzo", "Play Store install - skipping GitHub update check")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/ishfaque123/frianzo-app/releases/tags/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) return@launch
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val releaseBody = json.optString("body", "")
                // Release body contains "version_code:XX"
                val match = Regex("version_code:(\\d+)").find(releaseBody)
                val latestCode = match?.groupValues?.get(1)?.toIntOrNull() ?: return@launch
                val currentCode = packageManager.getPackageInfo(packageName, 0).let {
                    if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
                }
                if (latestCode > currentCode) {
                    val apkUrl = "https://github.com/ishfaque123/frianzo-app/releases/download/latest/Frianzo.apk"
                    withContext(Dispatchers.Main) { showUpdateDialog(apkUrl, latestCode) }
                }
            } catch (e: Exception) {
                Log.d("Frianzo", "Update check failed: ${e.message}")
            }
        }
    }

    private fun showUpdateDialog(apkUrl: String, versionCode: Int) {
        AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage("A new version of Frianzo is available. Update now for the latest features and fixes.")
            .setPositiveButton("Update") { _, _ ->
                try {
                    val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                        setTitle("Frianzo Update")
                        setDescription("Downloading new version...")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Frianzo-update.apk")
                        setMimeType("application/vnd.android.package-archive")
                    }
                    val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(request)
                    Toast.makeText(this, "Downloading update...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    // Fallback: open in browser
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))
                }
            }
            .setNegativeButton("Later", null)
            .show()
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

    // Persist the marker: the in-memory flag alone resets on every cold start,
    // re-posting an already-settled claim. Only ever set on accepted or
    // already-referred responses — never on network failure, so retries keep working.
    private fun markReferralClaimed() {
        referralClaimed = true
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_REFERRAL_CLAIMED, true).apply()
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
                    markReferralClaimed()
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
                    markReferralClaimed()
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
        val path = intent?.getStringExtra("fcm_url") ?: intent?.getStringExtra("url") ?: return null
        return if (path.startsWith("/") && !path.startsWith("//")) "$SITE_URL$path" else null
    }

    /**
     * Handles an incoming Android share-sheet intent (ACTION_SEND /
     * ACTION_SEND_MULTIPLE): stages shared image/video files into the app
     * cache and opens the compose page, which fetches the staged file from
     * /__share/<token> and attaches it. Shared text goes in as prefilled
     * compose content via query param.
     */
    private fun handleShareIntent(intent: Intent) {
        clearStagedShares()
        val mime = intent.type ?: ""
        if (intent.action == Intent.ACTION_SEND && mime.startsWith("text/")) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            webView.loadUrl(
                if (text != null) "$SITE_URL/compose?shared_text=${Uri.encode(text)}"
                else "$SITE_URL/compose"
            )
            return
        }
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.parcelableUriListExtra(Intent.EXTRA_STREAM)
        } else {
            listOfNotNull(intent.parcelableUriExtra(Intent.EXTRA_STREAM))
        }.filter { uri ->
            val t = contentResolver.getType(uri) ?: ""
            t.startsWith("image/") || t.startsWith("video/")
        }
        // Compose takes a single attachment; use the first shared item.
        // (A shared video is routed to the reels flow by the compose page.)
        // A shared caption (e.g. WhatsApp) is prefilled as the post text.
        // ANR FIX (2026-10-20): staging copies the whole file on the calling
        // thread — from onCreate/onNewIntent that is the MAIN thread, so a
        // large shared video freezes the app ("Input dispatching timed out"
        // ANR in Play Console). Stage on IO, then navigate back on Main.
        val first = uris.firstOrNull()
        if (first == null) {
            webView.loadUrl("$SITE_URL/compose")
            return
        }
        val caption = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) { stageSharedUri(first) }
            if (staged == null) {
                webView.loadUrl("$SITE_URL/compose")
                return@launch
            }
            val (token, share) = staged
            var url = "$SITE_URL/compose?share=$token&name=${Uri.encode(share.name)}"
            if (caption != null) url += "&shared_text=${Uri.encode(caption)}"
            webView.loadUrl(url)
        }
    }

    private fun stageSharedUri(uri: Uri): Pair<String, StagedShare>? {
        return try {
            val resolver = contentResolver
            val mime = resolver.getType(uri) ?: return null
            var name = "shared_${System.currentTimeMillis()}"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
            }
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            val dir = File(cacheDir, "share_inbox").apply { mkdirs() }
            val dest = File(dir, "${UUID.randomUUID()}${if (!ext.isNullOrEmpty()) ".$ext" else ""}")
            resolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (!dest.exists() || dest.length() == 0L) {
                dest.delete()
                return null
            }
            val token = UUID.randomUUID().toString()
            val share = StagedShare(dest, mime, name)
            stagedShares[token] = share
            token to share
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stage shared file", e)
            null
        }
    }

    private fun clearStagedShares() {
        stagedShares.values.forEach { runCatching { it.file.delete() } }
        stagedShares.clear()
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUriExtra(name: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Uri::class.java)
        } else {
            getParcelableExtra(name)
        }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUriListExtra(name: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(name, Uri::class.java)?.toList() ?: emptyList()
        } else {
            getParcelableArrayListExtra<Uri>(name)?.toList() ?: emptyList()
        }

    /**
     * Whether the launch UI should use the dark variant. Reads the theme the
     * web app last reported via NativeBridge.setAppTheme(); on first run
     * (nothing reported yet) falls back to the system night mode, mirroring
     * the web ThemeProvider's 'system' behavior.
     */
    /** Start the FB-style pulsing dots on the first-load overlay. */
    private fun startLoadingDots() {
        val dotIds = listOf(R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4, R.id.dot5, R.id.dot6)
        dotIds.forEachIndexed { i, id ->
            val dot = findViewById<View>(id)
            val anim = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.25f, 1f, 0.25f).apply {
                duration = 720
                startDelay = (i * 120).toLong()
                repeatCount = ValueAnimator.INFINITE
            }
            dotAnimators.add(anim)
            anim.start()
        }
    }

    /** Fade the first-load overlay out (idempotent: only the first call acts). */
    private fun hideLoadingOverlay() {
        if (loadingOverlayHidden) return
        loadingOverlayHidden = true
        dotAnimators.forEach { it.cancel() }
        dotAnimators.clear()
        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction { loadingOverlay.visibility = View.GONE }
            .start()
    }

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
        val action = intent?.action
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            intent?.let { handleShareIntent(it) }
            return
        }
        notificationTargetUrl(intent)?.let { webView.loadUrl(it) }
        val oauthUri = intent?.data
        if (oauthUri != null && isOAuthCallback(oauthUri)) {
            handleOAuthCallback(oauthUri)
        }
    }

    private fun updateSwipeRefreshForUrl(uri: Uri) {
        val isFrianzoHost = uri.scheme.equals("https", ignoreCase = true) &&
            (uri.host.equals(SITE_HOST, ignoreCase = true) ||
                uri.host.equals("www.$SITE_HOST", ignoreCase = true))
        val isNoRefreshSection = isFrianzoHost &&
            (uri.path == "/reels" || uri.path?.startsWith("/reels/") == true ||
             uri.path == "/messages" || uri.path?.startsWith("/messages/") == true)

        // Reel feed uses vertical swipe navigation, and chat uses its own scroll
        // to older messages. Disable pull-to-refresh only while the WebView is
        // inside the reels or messages sections so a downward swipe can never
        // trigger SwipeRefreshLayout.reload(). Other app pages keep their
        // existing pull-to-refresh behavior.
        swipeRefresh.isEnabled = !isNoRefreshSection
        if (isNoRefreshSection) swipeRefresh.isRefreshing = false
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
            markOAuthInProgress()
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
                // USER CANCELED (2026-10-17): if the user dismissed the account picker,
                // silently abort — don't show a scary error dialog. Re-throw the
                // cancellation so the caller can return quietly.
                if (e is GetCredentialCancellationException ||
                    fallbackError is GetCredentialCancellationException) {
                    Log.i(TAG, "User canceled Google sign-in; aborting silently")
                    throw fallbackError
                }
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
            } catch (e: GetCredentialCancellationException) {
                // User dismissed the Google account picker — not an error.
                // Silently return to the login screen (fix 2026-10-17).
                Log.i(TAG, "Google sign-in canceled by user; no error shown")
            } catch (e: Exception) {
                // Full technical detail stays in the log for developers only;
                // the user sees a clean, professional message (2026-10-21).
                val cause = generateDiagnosticMessage(e)
                Log.e(TAG, "Native Google sign-in failed: $cause", e)
                val isNotActive = cause.contains("ACCOUNT_NOT_ACTIVE")
                val dialogTitle = if (isNotActive) "Account Suspended" else "Login Failed"
                val dialogMessage = if (isNotActive) {
                    "Your account has been suspended, so you can't log in right now.\n\n" +
                        "If you believe this is a mistake, please contact us at support@frianzo.online."
                } else {
                    "Something went wrong while signing in. Please check your internet connection and try again."
                }
                // BadTokenException guard (Play Console crash, v18): if the user
                // backgrounded/closed the app while sign-in was in flight, the
                // activity is finishing/destroyed by the time we get here and
                // showing a dialog crashes the app. Only show UI when alive.
                if (!isFinishing && !isDestroyed) {
                    try {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(dialogTitle)
                            .setMessage(dialogMessage)
                            .setPositiveButton("OK", null)
                            .show()
                    } catch (be: android.view.WindowManager.BadTokenException) {
                        Log.w(TAG, "Login-failed dialog skipped: activity window gone", be)
                    }
                } else {
                    Log.w(TAG, "Login-failed UI skipped: activity finishing/destroyed")
                }
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

        val opts = "Path=/; Secure; HttpOnly; SameSite=None; Domain=.frianzo.online"

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

    // OAuth CSRF protection: only accept frianzo://oauth/callback if THIS app
    // started a Google OAuth flow recently. Blocks malicious apps from firing
    // the deep link with their own token to hijack the victim's session.
    private var oAuthInProgress = false
    private var oAuthStartTime = 0L

    private fun markOAuthInProgress() {
        oAuthInProgress = true
        oAuthStartTime = System.currentTimeMillis()
        Log.i(TAG, "OAuth flow started - callback accepted for 10 min")
    }

    private fun handleOAuthCallback(uri: Uri) {
        val elapsed = System.currentTimeMillis() - oAuthStartTime
        if (!oAuthInProgress || elapsed > 10 * 60 * 1000) {
            Log.w(TAG, "Rejected OAuth callback - no OAuth flow in progress (possible CSRF)")
            oAuthInProgress = false
            webView.loadUrl("$SITE_URL/login?error=google_auth_failed")
            return
        }
        oAuthInProgress = false // single-use

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }
}
