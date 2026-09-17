package com.frianzo.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialException
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var credentialManager: CredentialManager

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
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        credentialManager = CredentialManager.create(this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleUrl(request.url)
            }

            @Deprecated("Deprecated in API 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrl(Uri.parse(url))
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
            }
        }

        swipeRefresh.setOnRefreshListener { webView.reload() }

        if (savedInstanceState == null) {
            val oauthUri = intent?.data
            if (oauthUri != null && isOAuthCallback(oauthUri)) {
                webView.post { handleOAuthCallback(oauthUri) }
            } else {
                webView.loadUrl(SITE_URL)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val oauthUri = intent?.data
        if (oauthUri != null && isOAuthCallback(oauthUri)) {
            handleOAuthCallback(oauthUri)
        }
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
        val nonce = generateSecureRandomNonce()
        val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(serverClientId)
            .setNonce(nonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .build()

        Log.i(TAG, "Requesting GetSignInWithGoogleOption with fresh nonce")
        val response = credentialManager.getCredential(
            request = request,
            context = this@MainActivity
        )
        return GoogleCredentialResult(response, nonce)
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
            } catch (e: GetCredentialException) {
                val cause = generateDiagnosticMessage(e)
                Log.e(TAG, "Credential Manager sign-in failed: $cause", e)
                showGoogleLoginError("Credential Manager", cause)
            } catch (e: Exception) {
                val cause = generateDiagnosticMessage(e)
                Log.e(TAG, "Native Google sign-in failed: $cause", e)
                showGoogleLoginError("Google Login", cause)
            }
        }
    }

    private fun showGoogleLoginError(stage: String, cause: String) {
        val diagnostic = buildString {
            appendLine("Stage: $stage")
            appendLine("Error: $cause")
            appendLine()
            appendLine("App package: com.frianzo.app")
            appendLine("Site: frianzo.online")
            appendLine("Credential Manager: 1.7.0-alpha03")
            appendLine("Google ID library: 1.2.0")
            appendLine()
            appendLine("This failure occurred before backend login if Stage is Credential Manager.")
        }.take(1800)

        Toast.makeText(this, cause, Toast.LENGTH_LONG).show()
        AlertDialog.Builder(this)
            .setTitle("Google Login Failed")
            .setMessage(diagnostic)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun generateDiagnosticMessage(error: Throwable): String {
        val messages = mutableListOf<String>()
        var current: Throwable? = error
        while (current != null && messages.size < 8) {
            val message = current.message?.trim()
            if (!message.isNullOrEmpty() && !messages.contains(message)) {
                messages.add(message)
            }
            current = current.cause
        }
        return buildString {
            append(error.javaClass.name)
            if (messages.isNotEmpty()) {
                append(": ")
                append(messages.joinToString(" | "))
            }
        }.take(1400)
    }
