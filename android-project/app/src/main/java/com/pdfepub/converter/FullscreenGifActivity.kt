package com.pdfepub.converter

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class FullscreenGifActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen_gif)

        val gifFile  = intent.getStringExtra("gif_file") ?: return
        val title    = intent.getStringExtra("gif_title") ?: "Ajuda"
        supportActionBar?.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val wv = findViewById<WebView>(R.id.webGifFullscreen)
        wv.settings.apply {
            javaScriptEnabled = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        wv.setBackgroundColor(0xFF000000.toInt())

        val html = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body{margin:0;padding:0;background:#000;display:flex;align-items:center;justify-content:center;min-height:100vh;}
              img{max-width:100%;height:auto;display:block;}
            </style>
            </head><body>
            <img src="file:///android_asset/$gifFile" />
            </body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)

        findViewById<MaterialButton>(R.id.btnCloseFullscreen).setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
