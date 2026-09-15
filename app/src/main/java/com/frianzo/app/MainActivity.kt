package com.frianzo.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
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

        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return handleUrl(request.url)
            }

            @Deprecated("Deprecated in API 24")
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String
            ): Boolean {
                return handleUrl(Uri.parse(url))
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
            }
        }

        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }

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
        ) {
            return false
        }

        if (scheme == "https" && host == API_HOST && path == GOOGLE_START_PATH) {
            launchNativeGoogleSignIn()
            return true
        }

        try {
            when (scheme) {
                "http", "https" -> {
                    return openExternal(uri)
                }

                "whatsapp" -> {
                    return openExternal(uri)
                }

                "intent" -> {
                    val intent = Intent.parseUri(
                        uri.toString(),
                        Intent.URI_INTENT_SCHEME
                    )

                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")

                        if (!fallbackUrl.isNullOrEmpty()) {
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(fallbackUrl)
                                )
                            )
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
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                }
            }
            return true
        }
    }

    private suspend fun getGoogleCredential(
        serverClientId: String,
        nonce: String
    ): androidx.credentials.GetCredentialResponse {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .setNonce(nonce)
            .build()

        val googleIdRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        // Credential Manager's Google bottom sheet must use a foreground-aware
        // activity context. A MutableContextWrapper also survives activity
        // recreation and avoids undefined system-UI launch behavior.
        val mutableContext = MutableContextWrapper(this@MainActivity)
        return credentialManager.getCredential(
            request = googleIdRequest,
            context = mutableContext
        )
    }

    private fun launchNativeGoogleSignIn() {
        lifecycleScope.launch {
            swipeRefresh.isRefreshing = false

            try {
                val serverClientId = withContext(Dispatchers.IO) {
                    fetchGoogleServerClientId()
                }
                require(serverClientId.isNotBlank()) { "Google server client ID is empty" }

                val nonce = generateSecureRandomNonce()
                val result = getGoogleCredential(serverClientId, nonce)

                val credential = result.credential
                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    throw IllegalStateException(
                        "Unsupported Google credential type: ${credential.type}"
                    )
                }

                val googleCredential = try {
                    GoogleIdTokenCredential.createFrom(credential.data)
                } catch (e: GoogleIdTokenParsingException) {
                    throw IllegalStateException("Invalid Google ID credential", e)
                }

                val deviceToken = getCookieValue("vynzo_device")
                val loginResult = withContext(Dispatchers.IO) {
                    nativeGoogleLogin(
                        idToken = googleCredential.idToken,
                        nonce = nonce,
                        deviceToken = deviceToken
                    )
                }

                setAuthCookies(loginResult.token, loginResult.deviceToken)
                val destination = if (loginResult.isNewUser) "/profile-setup" else "/"
                webView.loadUrl("$SITE_URL$destination")
            } catch (e: Exception) {
                // Keep the user-facing message simple, but log the exact native
                // failure so the next diagnosis does not hide the real cause.
                Log.e(TAG, "Native Google sign-in failed", e)
                webView.loadUrl("$SITE_URL/login?error=google_auth_failed")
            }
        }
    }

    private fun fetchGoogleServerClientId(): String {
        val connection = (URL("$API_URL$GOOGLE_CONFIG_PATH").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(response)
            val data = root.getJSONObject("data")
            data.getString("clientId")
        } finally {
            connection.disconnect()
        }
    }

    private fun nativeGoogleLogin(
        idToken: String,
        nonce: String,
        deviceToken: String?
    ): NativeLoginResult {
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
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Empty authentication response")

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "Google authentication failed: HTTP ${connection.responseCode} $response"
                )
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
        cookieManager.setCookie(
            API_URL,
            "vynzo_token=$token; Path=/; Secure; HttpOnly"
        )
        cookieManager.setCookie(
            API_URL,
            "vynzo_device=$deviceToken; Path=/; Secure; HttpOnly"
        )
        cookieManager.flush()
    }

    private fun getCookieValue(name: String): String? {
        val cookies = CookieManager.getInstance().getCookie(API_URL) ?: return null
        return cookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
    }

    private fun generateSecureRandomNonce(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
        )
    }

    private fun openExternal(uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun isOAuthCallback(uri: Uri): Boolean {
        return uri.scheme.equals(OAUTH_SCHEME, ignoreCase = true) &&
            uri.host.equals(OAUTH_HOST, ignoreCase = true) &&
            uri.path.equals("/callback", ignoreCase = true)
    }

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

    private data class NativeLoginResult(
        val token: String,
        val deviceToken: String,
        val isNewUser: Boolean
    )

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
