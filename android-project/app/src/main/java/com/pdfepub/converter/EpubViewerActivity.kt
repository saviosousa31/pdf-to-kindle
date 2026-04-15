package com.pdfepub.converter

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File

class EpubViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLoading: TextView
    private lateinit var btnPrevChapter: MaterialButton
    private lateinit var btnNextChapter: MaterialButton
    private lateinit var tvPageInfo: TextView
    private lateinit var bottomBar: LinearLayout

    private lateinit var epubFile: File
    private var epubLoaded = false

    companion object {
        const val EXTRA_EPUB_PATH = "epub_path"
        const val EXTRA_EPUB_TITLE = "epub_title"

        private const val TAG = "EpubViewerActivity"
        private const val VIEWER_URL = "https://localhost/epub_viewer/index.html"
        private const val EPUB_URL = "https://localhost/epub/book.epub"
        private const val ASSET_INDEX_PATH = "/epub_viewer/index.html"
        private const val ASSET_JS_PATH = "/epub_viewer/epub.min.js"
        private const val EPUB_PATH = "/epub/book.epub"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epub_viewer)

        val epubPath = intent.getStringExtra(EXTRA_EPUB_PATH) ?: run { finish(); return }
        val epubTitle = intent.getStringExtra(EXTRA_EPUB_TITLE) ?: getString(R.string.viewer_title)

        supportActionBar?.title = epubTitle
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        webView = findViewById(R.id.webViewEpub)
        progressBar = findViewById(R.id.progressViewer)
        tvLoading = findViewById(R.id.tvViewerLoading)
        btnPrevChapter = findViewById(R.id.btnPrevChapter)
        btnNextChapter = findViewById(R.id.btnNextChapter)
        tvPageInfo = findViewById(R.id.tvPageInfo)
        bottomBar = findViewById(R.id.bottomBarViewer)

        setupWebView(epubPath)

        btnPrevChapter.setOnClickListener { webView.evaluateJavascript("prevPage()", null) }
        btnNextChapter.setOnClickListener { webView.evaluateJavascript("nextPage()", null) }
    }

    private fun setupWebView(epubPath: String) {
        epubFile = File(epubPath)
        if (!epubFile.exists() || !epubFile.isFile) {
            tvLoading.text = getString(R.string.viewer_error, "EPUB file not found")
            progressBar.visibility = View.GONE
            bottomBar.visibility = View.VISIBLE
            return
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true

            // The viewer is loaded from our own intercepted HTTPS origin.
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }

        webView.addJavascriptInterface(EpubJsInterface(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                Log.d(
                    TAG,
                    "JS:${consoleMessage.messageLevel()} ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                )
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress >= 100 && progressBar.visibility == View.VISIBLE) {
                    progressBar.visibility = View.GONE
                    tvLoading.visibility = View.GONE
                    bottomBar.visibility = View.VISIBLE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                val path = url.path ?: return null

                return when {
                    url.scheme == "https" && path == ASSET_INDEX_PATH -> serveAsset("epub_viewer/index.html", "text/html; charset=UTF-8")
                    url.scheme == "https" && path == ASSET_JS_PATH -> serveAsset("epub_viewer/epub.min.js", "application/javascript; charset=UTF-8")
                    url.scheme == "https" && path == EPUB_PATH && epubFile.exists() && epubFile.isFile -> serveEpub()
                    else -> null
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (epubLoaded) return
                epubLoaded = true

                view.post {
                    view.evaluateJavascript("loadEpubFromUrl('$EPUB_URL')", null)
                }
            }
        }

        webView.loadUrl(VIEWER_URL)
    }

    private fun serveAsset(assetPath: String, mimeType: String): WebResourceResponse? {
        return try {
            val input = assets.open(assetPath)
            WebResourceResponse(mimeType, "UTF-8", input)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load asset: $assetPath", e)
            null
        }
    }

    private fun serveEpub(): WebResourceResponse? {
        return try {
            WebResourceResponse(
                "application/epub+zip",
                null,
                epubFile.inputStream()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open EPUB file", e)
            null
        }
    }

    inner class EpubJsInterface {
        @JavascriptInterface
        fun onPageChanged(current: Int, total: Int) {
            runOnUiThread {
                tvPageInfo.text = getString(R.string.viewer_page_info, current, total)
                btnPrevChapter.isEnabled = current > 1
                btnNextChapter.isEnabled = current < total
            }
        }

        @JavascriptInterface
        fun onError(msg: String) {
            runOnUiThread {
                progressBar.visibility = View.GONE
                tvLoading.visibility = View.VISIBLE
                tvLoading.text = getString(R.string.viewer_error, msg)
                bottomBar.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_font_smaller -> { webView.evaluateJavascript("changeFontSize(-2)", null); true }
            R.id.action_font_larger -> { webView.evaluateJavascript("changeFontSize(2)", null); true }
            R.id.action_night_mode -> { webView.evaluateJavascript("toggleNightMode()", null); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        epubLoaded = false
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
