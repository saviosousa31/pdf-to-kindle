package com.pdfepub.converter

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class FullscreenGifActivity : AppCompatActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen_gif)

        // Valida e sanitiza o nome do GIF (apenas nomes de arquivo permitidos)
        val rawFile = intent.getStringExtra("gif_file")
        val gifFile = rawFile?.let { sanitizeAssetName(it) }

        if (gifFile == null) {
            // Se inválido, fecha imediatamente sem crashar
            finish()
            return
        }

        val title = intent.getStringExtra("gif_title")?.take(80) ?: "Ajuda"
        supportActionBar?.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val wv = findViewById<WebView>(R.id.webGifFullscreen)
        webView = wv

        wv.settings.apply {
            javaScriptEnabled             = false
            loadWithOverviewMode          = true
            useWideViewPort               = true
            builtInZoomControls           = true
            displayZoomControls           = false
            cacheMode                     = WebSettings.LOAD_NO_CACHE
            // Segurança: desabilita acesso a outros arquivos locais
            allowFileAccess               = false   // assets são carregados via URL base
            allowContentAccess            = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs   = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled             = false
            databaseEnabled               = false
            geolocationEnabled            = false
            setSupportZoom(true)
        }

        // Bloqueia qualquer navegação externa — apenas assets locais permitidos
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Bloqueia TUDO exceto o asset local
                val url = request.url.toString()
                return !url.startsWith("file:///android_asset/")
            }
        }

        wv.setBackgroundColor(0xFF000000.toInt())

        val html = buildGifHtml(gifFile)
        // Carrega com URL base de assets para que o src relativo funcione
        wv.loadDataWithBaseURL(
            "file:///android_asset/",
            html,
            "text/html",
            "UTF-8",
            null
        )

        findViewById<MaterialButton>(R.id.btnCloseFullscreen).setOnClickListener { finish() }
    }

    private fun buildGifHtml(gifFile: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0">
        <style>
          * { box-sizing: border-box; margin: 0; padding: 0; }
          html, body {
            width: 100%; height: 100%;
            background: #000;
            display: flex; align-items: center; justify-content: center;
          }
          img {
            max-width: 100%; max-height: 100%;
            width: auto; height: auto;
            display: block; object-fit: contain;
          }
        </style>
        </head>
        <body>
          <img src="$gifFile" alt="Ajuda" />
        </body>
        </html>
    """.trimIndent()

    /** Aceita apenas nomes de arquivo simples sem path traversal. */
    private fun sanitizeAssetName(name: String): String? {
        val clean = name.trim()
        // Deve ser apenas "nome.extensao" sem barras ou pontos duplos
        if (clean.contains('/') || clean.contains('\\') || clean.contains("..")) return null
        if (!clean.matches(Regex("[a-zA-Z0-9_\\-]+\\.(gif|png|jpg|jpeg|webp)"))) return null
        return clean
    }

    override fun onDestroy() {
        webView?.apply { stopLoading(); destroy() }
        webView = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
