package com.pdfepub.converter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class HelpActivity : AppCompatActivity() {

    private val webViews = mutableListOf<WebView>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        supportActionBar?.title = getString(R.string.help_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupGifCard(
            R.id.webKindleEmail, R.id.btnExpandKindleEmail,
            "ajuda_email_kindle.gif",
            getString(R.string.help_kindle_email_title)
        )
        setupGifCard(
            R.id.webAuthSender, R.id.btnExpandAuthSender,
            "ajuda_habilitar_remetente.gif",
            getString(R.string.help_authorize_sender_title)
        )

        findViewById<MaterialButton>(R.id.btnHelpClose).apply {
            text = getString(R.string.btn_help_close)
            setOnClickListener { finish() }
        }
    }

    private fun setupGifCard(webId: Int, expandId: Int, gifFile: String, gifTitle: String) {
        val wv = findViewById<WebView>(webId)
        webViews += wv

        wv.settings.apply {
            javaScriptEnabled                    = false
            loadWithOverviewMode                 = true
            useWideViewPort                      = true
            builtInZoomControls                  = false
            displayZoomControls                  = false
            cacheMode                            = WebSettings.LOAD_NO_CACHE
            allowFileAccess                      = false
            allowContentAccess                   = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs          = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs     = false
            domStorageEnabled                    = false
            databaseEnabled                      = false
            setGeolocationEnabled(false)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !request.url.toString().startsWith("file:///android_asset/")
            }
        }

        wv.setBackgroundColor(0x00000000)

        val html = """
            <!DOCTYPE html><html>
            <head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body{margin:0;padding:0;background:transparent;}
              img{width:100%;height:auto;display:block;border-radius:8px;}
            </style>
            </head>
            <body><img src="$gifFile" alt=""/></body>
            </html>
        """.trimIndent()
        wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)

        val btnExpand = findViewById<FrameLayout>(expandId)
        btnExpand.setOnClickListener {
            startActivity(Intent(this, FullscreenGifActivity::class.java).apply {
                putExtra("gif_file", gifFile)
                putExtra("gif_title", gifTitle)
            })
        }
    }

    override fun onDestroy() {
        webViews.forEach { it.stopLoading(); it.destroy() }
        webViews.clear()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
}
