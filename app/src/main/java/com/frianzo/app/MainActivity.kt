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
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
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
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            Log.i(TAG, "Cleared previous credential state")
        } catch (e: Exception) {
            Log.w(TAG, "clearCredentialState failed (safe to ignore)", e)
        }

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
        val messages = mutableListOf<String>()
        var current: Throwable? = error
        while (current != null && messages.size < 4) {
            val message = current.message?.trim()
            if (!message.isNullOrEmpty() && !messages.contains(message)) {
                messages.add(message)
            }
            current = current.cause
        }
        return buildString {
            append(error.javaClass.simpleName)
            if (messages.isNotEmpty()) {
                append(": ")
                append(messages.joinToString(" | "))
            }
        }.take(1000)
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

        cookieManager.setCookie(API_URL, "vynzo_token=$token; $opts")
        cookieManager.setCookie(API_URL, "vynzo_device=$deviceToken; $opts")
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
