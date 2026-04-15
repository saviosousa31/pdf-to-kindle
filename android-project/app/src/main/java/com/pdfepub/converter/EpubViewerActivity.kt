package com.pdfepub.converter

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
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
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.File

class EpubViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLoading: TextView
    private lateinit var btnPrevChapter: MaterialButton
    private lateinit var btnNextChapter: MaterialButton
    private lateinit var tvPageInfo: TextView
    private lateinit var bottomBar: LinearLayout

    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var stagedEpubFile: File
    private var viewerBootstrapRequested = false

    companion object {
        const val EXTRA_EPUB_PATH = "epub_path"
        const val EXTRA_EPUB_TITLE = "epub_title"

        private const val TAG = "EpubViewerActivity"
        private const val ASSET_VIEWER_URL =
            "https://appassets.androidplatform.net/assets/epub_viewer/index.html"
        private const val EPUB_URL =
            "https://appassets.androidplatform.net/epub/book.epub"
        private const val EPUB_CACHE_DIR_NAME = "epub_viewer"
        private const val EPUB_FILE_NAME = "book.epub"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epub_viewer)

        val epubPath = intent.getStringExtra(EXTRA_EPUB_PATH) ?: run {
            finish()
            return
        }
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

        btnPrevChapter.isEnabled = false
        btnNextChapter.isEnabled = false
        tvLoading.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        bottomBar.visibility = View.GONE
        tvLoading.text = "Loading EPUB..."

        try {
            stagedEpubFile = stageEpubForWebView(epubPath)
        } catch (e: Exception) {
            showError("Failed to prepare EPUB: ${e.localizedMessage ?: e.javaClass.simpleName}")
            Log.e(TAG, "Failed to stage EPUB", e)
            return
        }

        setupWebView()
        loadViewerPage()
        wireControls()
    }

    private fun stageEpubForWebView(sourcePath: String): File {
        val sourceFile = File(sourcePath)
        require(sourceFile.exists() && sourceFile.isFile) {
            "EPUB file not found"
        }

        val targetDir = File(cacheDir, EPUB_CACHE_DIR_NAME)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("Unable to create cache directory")
        }

        val targetFile = File(targetDir, EPUB_FILE_NAME)

        val sourceCanonical = sourceFile.canonicalFile
        val targetCanonical = targetFile.canonicalFile
        if (sourceCanonical.path == targetCanonical.path && sourceCanonical.exists()) {
            return sourceCanonical
        }

        sourceFile.inputStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return targetFile
    }

    private fun setupWebView() {
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler(
                "/epub/",
                WebViewAssetLoader.InternalStoragePathHandler(
                    this,
                    File(cacheDir, EPUB_CACHE_DIR_NAME)
                )
            )
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE

            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.addJavascriptInterface(EpubJsBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val level = consoleMessage.messageLevel().name
                Log.d(
                    TAG,
                    "JS[$level] ${consoleMessage.message()} @ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                )
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (viewerBootstrapRequested) return
                if (url != ASSET_VIEWER_URL) return

                viewerBootstrapRequested = true

                view.post {
                    val jsUrl = JSONObject.quote(EPUB_URL)
                    view.evaluateJavascript("loadEpubFromUrl($jsUrl)", null)
                }
            }
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.loadUrl(ASSET_VIEWER_URL)
    }

    private fun loadViewerPage() {
        // Keep loading indicator visible until the JS side confirms the EPUB renderer is ready.
        progressBar.visibility = View.VISIBLE
        tvLoading.visibility = View.VISIBLE
        bottomBar.visibility = View.GONE
    }

    private fun wireControls() {
        btnPrevChapter.setOnClickListener {
            webView.evaluateJavascript("prevPage()", null)
        }
        btnNextChapter.setOnClickListener {
            webView.evaluateJavascript("nextPage()", null)
        }
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        tvLoading.visibility = View.VISIBLE
        tvLoading.text = message
    }

    inner class EpubJsBridge {
        @JavascriptInterface
        fun onViewerReady() {
            runOnUiThread {
                progressBar.visibility = View.GONE
                tvLoading.visibility = View.GONE
                bottomBar.visibility = View.VISIBLE
            }
        }

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
                showError(getString(R.string.viewer_error, msg))
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_font_smaller -> {
                webView.evaluateJavascript("changeFontSize(-2)", null)
                true
            }

            R.id.action_font_larger -> {
                webView.evaluateJavascript("changeFontSize(2)", null)
                true
            }

            R.id.action_night_mode -> {
                webView.evaluateJavascript("toggleNightMode()", null)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }
}
