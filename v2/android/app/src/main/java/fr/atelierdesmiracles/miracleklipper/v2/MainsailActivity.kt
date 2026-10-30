package fr.atelierdesmiracles.miracleklipper.v2

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/** Mainsail served by guest nginx through QEMU loopback-only port forwarding. */
class MainsailActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            text = "Connexion à Mainsail (127.0.0.1:8080)…"
            setPadding(16, 12, 16, 12)
        }
        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                val localHost = uri.host == "127.0.0.1" || uri.host == "localhost"
                if (uri.scheme == "http" && localHost) return false
                if (uri.scheme == "https") {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                }
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                status.text = "Chargement de Mainsail…"
                status.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                status.visibility = View.GONE
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    status.visibility = View.VISIBLE
                    status.text = "Mainsail inaccessible. Vérifiez la VM Alpine, nginx et le transfert QEMU."
                }
            }
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        webView.loadUrl("http://127.0.0.1:8080/")
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
