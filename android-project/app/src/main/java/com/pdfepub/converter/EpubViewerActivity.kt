package com.pdfepub.converter

import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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

    private lateinit var webView        : WebView
    private lateinit var progressBar    : ProgressBar
    private lateinit var tvLoading      : TextView
    private lateinit var btnPrevChapter : MaterialButton
    private lateinit var btnNextChapter : MaterialButton
    private lateinit var tvPageInfo     : TextView
    private lateinit var bottomBar      : LinearLayout

    companion object {
        const val EXTRA_EPUB_PATH = "epub_path"
        const val EXTRA_EPUB_TITLE = "epub_title"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epub_viewer)

        val epubPath  = intent.getStringExtra(EXTRA_EPUB_PATH) ?: run { finish(); return }
        val epubTitle = intent.getStringExtra(EXTRA_EPUB_TITLE) ?: getString(R.string.viewer_title)

        supportActionBar?.title = epubTitle
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        webView        = findViewById(R.id.webViewEpub)
        progressBar    = findViewById(R.id.progressViewer)
        tvLoading      = findViewById(R.id.tvViewerLoading)
        btnPrevChapter = findViewById(R.id.btnPrevChapter)
        btnNextChapter = findViewById(R.id.btnNextChapter)
        tvPageInfo     = findViewById(R.id.tvPageInfo)
        bottomBar      = findViewById(R.id.bottomBarViewer)

        setupWebView(epubPath)

        btnPrevChapter.setOnClickListener { webView.evaluateJavascript("prevPage()", null) }
        btnNextChapter.setOnClickListener { webView.evaluateJavascript("nextPage()", null) }
    }

    private fun setupWebView(epubPath: String) {
        webView.settings.apply {
            javaScriptEnabled                = true   // necessário para epub.js
            allowFileAccess                  = true   // necessário para ler o epub local
            allowContentAccess               = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs      = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            domStorageEnabled                = true
            cacheMode                        = WebSettings.LOAD_NO_CACHE
            setSupportZoom(true)
            builtInZoomControls              = true
            displayZoomControls              = false
            useWideViewPort                  = true
            loadWithOverviewMode             = true
        }
        webView.settings.apply {
        }

        // Interface JS → Kotlin para receber eventos do epub.js
        webView.addJavascriptInterface(EpubJsInterface(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress == 100) {
                    progressBar.visibility = View.GONE
                    tvLoading.visibility   = View.GONE
                    bottomBar.visibility   = View.VISIBLE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            override fun onPageFinished(view: WebView, url: String) {
                if (epubLoaded) return
                epubLoaded = true
            
                // Passa apenas o caminho local do arquivo; evita enviar o EPUB inteiro via JS.
                val epubUrl = File(epubPath).toURI().toString()
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                view.evaluateJavascript("loadEpubFromUrl('$epubUrl')", null)
            }
        }

        // Carregar o HTML do viewer (armazenado em assets)
        webView.loadUrl("file:///android_asset/epub_viewer/index.html")
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
                tvLoading.text = getString(R.string.viewer_error, msg)
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
            R.id.action_font_larger  -> { webView.evaluateJavascript("changeFontSize(2)", null); true }
            R.id.action_night_mode   -> { webView.evaluateJavascript("toggleNightMode()", null); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
