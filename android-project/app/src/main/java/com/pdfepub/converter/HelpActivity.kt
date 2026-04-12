package com.pdfepub.converter

import android.content.Intent
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        supportActionBar?.title = "Ajuda — Configuração Kindle"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupGifCard(
            webId         = R.id.webKindleEmail,
            expandId      = R.id.btnExpandKindleEmail,
            gifFile       = "ajuda_email_kindle.gif",
            gifTitle      = "Como encontrar o e-mail do Kindle"
        )
        setupGifCard(
            webId         = R.id.webAuthSender,
            expandId      = R.id.btnExpandAuthSender,
            gifFile       = "ajuda_habilitar_remetente.gif",
            gifTitle      = "Como autorizar o remetente"
        )

        findViewById<MaterialButton>(R.id.btnHelpClose).setOnClickListener { finish() }
    }

    private fun setupGifCard(webId: Int, expandId: Int, gifFile: String, gifTitle: String) {
        val wv = findViewById<WebView>(webId)
        wv.settings.apply {
            javaScriptEnabled = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        wv.setBackgroundColor(0x00000000)

        val html = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;padding:0;background:transparent;}img{width:100%;height:auto;display:block;border-radius:8px;}</style>
            </head><body>
            <img src="file:///android_asset/$gifFile"/>
            </body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)

        // Botão de expandir sobreposto ao GIF
        val btnExpand = findViewById<FrameLayout>(expandId)
        btnExpand.setOnClickListener {
            startActivity(Intent(this, FullscreenGifActivity::class.java).apply {
                putExtra("gif_file", gifFile)
                putExtra("gif_title", gifTitle)
            })
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
}
