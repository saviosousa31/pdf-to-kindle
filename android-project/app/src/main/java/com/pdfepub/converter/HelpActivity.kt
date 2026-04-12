package com.pdfepub.converter

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        supportActionBar?.title = "Ajuda — Configuração Kindle"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupWebView(R.id.webKindleEmail,     "ajuda_email_kindle.gif")
        setupWebView(R.id.webAuthSender,      "ajuda_habilitar_remetente.gif")

        findViewById<MaterialButton>(R.id.btnHelpClose).setOnClickListener { finish() }
    }

    private fun setupWebView(id: Int, assetFile: String) {
        val wv = findViewById<WebView>(id)
        wv.settings.apply {
            javaScriptEnabled = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        wv.setBackgroundColor(0x00000000)
        // Carrega o GIF do assets via HTML simples — garante animação
        val html = """
            <!DOCTYPE html>
            <html><head>
            <style>body{margin:0;padding:0;background:transparent;}
            img{width:100%;height:auto;display:block;border-radius:12px;}</style>
            </head><body>
            <img src="file:///android_asset/$assetFile" />
            </body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
