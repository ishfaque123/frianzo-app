package com.frianzo.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    companion object {
        private const val SITE_URL = "https://frianzo.online"
        private const val SITE_HOST = "frianzo.online"
        private const val API_HOST = "api.frianzo.online"
        private const val GOOGLE_START_PATH = "/api/auth/google/start"
        private const val OAUTH_SCHEME = "frianzo"
        private const val OAUTH_HOST = "oauth"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipe_refresh)

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

        // Keep Frianzo website inside the app
        if ((scheme == "http" || scheme == "https") &&
            (host == SITE_HOST || host == "www.$SITE_HOST")
        ) {
            return false
        }

        // Google OAuth cannot run inside Android WebView. Open the system
        // browser, but mark the flow so the backend returns to this app.
        if (scheme == "https" && host == API_HOST && path == GOOGLE_START_PATH) {
            val appUri = uri.buildUpon()
                .clearQuery()
                .appendQueryParameter("app", "1")
                .build()
            return openExternal(appUri)
        }

        // Open external links using Android system
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

        val cookieManager = CookieManager.getInstance()
        cookieManager.setCookie(
            "https://$API_HOST",
            "vynzo_token=$token; Path=/; Secure; HttpOnly"
        )
        cookieManager.setCookie(
            "https://$API_HOST",
            "vynzo_device=$deviceToken; Path=/; Secure; HttpOnly"
        )
        cookieManager.flush()

        val destination = if (isNewUser) "/profile-setup" else "/"
        webView.loadUrl("$SITE_URL$destination")
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
