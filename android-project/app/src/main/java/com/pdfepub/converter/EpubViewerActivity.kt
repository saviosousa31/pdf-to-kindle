package com.pdfepub.converter

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
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
    private lateinit var epubViewerDir: File
    private lateinit var assetLoader: WebViewAssetLoader
    private var epubLoaded = false

    companion object {
        const val EXTRA_EPUB_PATH = "epub_path"
        const val EXTRA_EPUB_TITLE = "epub_title"

        private const val TAG = "EpubViewerActivity"
        private const val APP_ASSET_HOST = "appassets.androidplatform.net"
        private val VIEWER_URL = "https://$APP_ASSET_HOST/assets/epub_viewer/index.html"
        private val EPUB_URL = "https://$APP_ASSET_HOST/epub/book.epub"
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

        // Stage the EPUB inside cacheDir so it can be safely exposed via HTTPS.
        epubViewerDir = File(cacheDir, "epub_viewer").apply { mkdirs() }
        val stagedEpub = File(epubViewerDir, "book.epub")

        try {
            epubFile.inputStream().use { input ->
                stagedEpub.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage EPUB for preview", e)
            tvLoading.text = getString(R.string.viewer_error, e.message ?: "Failed to prepare EPUB")
            progressBar.visibility = View.GONE
            bottomBar.visibility = View.VISIBLE
            return
        }

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/epub/", WebViewAssetLoader.InternalStoragePathHandler(this, epubViewerDir))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true

            // Use WebViewAssetLoader (HTTPS appassets) instead of file:// access.
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
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (epubLoaded) return
                epubLoaded = true

                if (!stagedEpub.exists() || !stagedEpub.isFile) {
                    tvLoading.text = getString(R.string.viewer_error, "Staged EPUB not found")
                    progressBar.visibility = View.GONE
                    bottomBar.visibility = View.VISIBLE
                    return
                }

                view.post {
                    view.evaluateJavascript("loadEpubFromUrl('$EPUB_URL')", null)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    progressBar.visibility = View.GONE
                    tvLoading.visibility = View.VISIBLE
                    tvLoading.text = getString(R.string.viewer_error, error.description?.toString() ?: "WebView error")
                    bottomBar.visibility = View.VISIBLE
                }
            }
        }

        webView.loadUrl(VIEWER_URL)
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
        runCatching { if (::epubViewerDir.isInitialized) File(epubViewerDir, "book.epub").delete() }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
