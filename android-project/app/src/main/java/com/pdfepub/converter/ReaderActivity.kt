package com.pdfepub.converter

import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.zip.ZipInputStream

class ReaderActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvChapterTitle: TextView
    private lateinit var tvPageInfo: TextView
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var tvLoading: TextView

    private val chapterFiles = mutableListOf<File>()
    private val chapterTitles = mutableListOf<String>()
    private var currentIndex = 0
    private var extractDir: File? = null

    companion object {
        const val EXTRA_EPUB_PATH = "epub_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        webView        = findViewById(R.id.webViewReader)
        tvChapterTitle = findViewById(R.id.tvChapterTitle)
        tvPageInfo     = findViewById(R.id.tvPageInfo)
        btnPrev        = findViewById(R.id.btnPrevChapter)
        btnNext        = findViewById(R.id.btnNextChapter)
        tvLoading      = findViewById(R.id.tvLoading)

        findViewById<ImageButton>(R.id.btnCloseReader).setOnClickListener { finish() }

        webView.settings.apply {
            javaScriptEnabled  = false
            allowFileAccess    = true
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            textZoom             = 110
        }
        webView.webViewClient = WebViewClient()

        btnPrev.setOnClickListener { showChapter(currentIndex - 1) }
        btnNext.setOnClickListener { showChapter(currentIndex + 1) }

        val epubPath = intent.getStringExtra(EXTRA_EPUB_PATH)
        if (epubPath == null) { finish(); return }

        tvLoading.visibility = View.VISIBLE

        Thread {
            try {
                val dir = File(cacheDir, "reader_${System.currentTimeMillis()}")
                dir.mkdirs()
                extractDir = dir
                unzipEpub(File(epubPath), dir)
                buildChapterList(dir)
                runOnUiThread {
                    tvLoading.visibility = View.GONE
                    if (chapterFiles.isEmpty()) {
                        tvLoading.text = getString(R.string.epub_not_readable)
                        tvLoading.visibility = View.VISIBLE
                    } else {
                        webView.visibility = View.VISIBLE
                        showChapter(0)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvLoading.text = getString(R.string.epub_not_readable)
                }
            }
        }.start()
    }

    private fun unzipEpub(epubFile: File, destDir: File) {
        ZipInputStream(epubFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun buildChapterList(dir: File) {
        val opf = File(dir, "OEBPS/content.opf")
        if (!opf.exists()) return
        val opfText = opf.readText()

        // Mapa id -> href do manifest
        val idToHref = mutableMapOf<String, String>()
        Regex("""<item\s[^>]*\bid="([^"]+)"[^>]*\bhref="([^"]+)"""")
            .findAll(opfText)
            .forEach { idToHref[it.groupValues[1]] = it.groupValues[2] }

        // Ordem da spine
        Regex("""<itemref\s[^>]*\bidref="([^"]+)"""")
            .findAll(opfText)
            .forEach { match ->
                val href = idToHref[match.groupValues[1]] ?: return@forEach
                // Pula capa e índice, que não são capítulos de texto: if (href == "nav.xhtml" || href == "cover.xhtml") return@forEach
                val file = File(dir, "OEBPS/$href")
                if (!file.exists()) return@forEach

                val content = file.readText()
                val title = Regex("""<h2[^>]*>(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL)
                    .find(content)?.groupValues?.get(1)
                    ?.replace(Regex("<[^>]+>"), "")?.trim()
                    ?: Regex("""<title[^>]*>(.*?)</title>""").find(content)?.groupValues?.get(1)?.trim()
                    ?: "Capítulo ${chapterFiles.size + 1}"

                chapterFiles.add(file)
                chapterTitles.add(title)
            }
    }

    private fun showChapter(index: Int) {
        if (index < 0 || index >= chapterFiles.size) return
        currentIndex = index

        val baseUrl = "file://${extractDir!!.absolutePath}/OEBPS/"
        val html    = chapterFiles[index].readText()

        webView.scrollTo(0, 0)
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)

        tvChapterTitle.text = chapterTitles[index]
        tvPageInfo.text     = "${index + 1} / ${chapterFiles.size}"
        btnPrev.isEnabled   = index > 0
        btnNext.isEnabled   = index < chapterFiles.size - 1
        btnPrev.alpha       = if (index > 0) 1f else 0.35f
        btnNext.alpha       = if (index < chapterFiles.size - 1) 1f else 0.35f
    }

    override fun onDestroy() {
        super.onDestroy()
        extractDir?.deleteRecursively()
    }
}