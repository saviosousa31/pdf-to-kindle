package com.pdfepub.converter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    // State
    private var pdfUri: Uri? = null
    private var pdfTitle = ""
    private var pdfAuthor = ""
    private var allCoverUrls = listOf<String>()
    private var shownCount = 0
    private var selectedCoverUrl: String? = null
    private var selectedCoverBytes: ByteArray? = null
    private var epubUri: Uri? = null
    private var epubFilename = ""

    // Views
    private lateinit var tvStatus: TextView
    private lateinit var cardPdf: MaterialCardView
    private lateinit var tvPdfName: TextView
    private lateinit var btnSelect: MaterialButton
    private lateinit var cardCover: MaterialCardView
    private lateinit var btnSearchCover: MaterialButton
    private lateinit var rvCovers: RecyclerView
    private lateinit var btnLoadMore: MaterialButton
    private lateinit var tvCoverHint: TextView
    private lateinit var tvSelectedCover: TextView
    private lateinit var cardConvert: MaterialCardView
    private lateinit var btnConvert: MaterialButton
    private lateinit var progressConvert: LinearProgressIndicator
    private lateinit var tvProgress: TextView
    private lateinit var cardResult: MaterialCardView
    private lateinit var tvResult: TextView
    private lateinit var btnEmail: MaterialButton
    private lateinit var btnNew: MaterialButton
    private lateinit var btnSettings: MaterialButton

    private lateinit var coverAdapter: CoverAdapter

    private val PDF_PICK = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupCoverRecycler()
        setupListeners()
        updateUI()
    }

    private fun bindViews() {
        tvStatus        = findViewById(R.id.tvStatus)
        cardPdf         = findViewById(R.id.cardPdf)
        tvPdfName       = findViewById(R.id.tvPdfName)
        btnSelect       = findViewById(R.id.btnSelect)
        cardCover       = findViewById(R.id.cardCover)
        btnSearchCover  = findViewById(R.id.btnSearchCover)
        rvCovers        = findViewById(R.id.rvCovers)
        btnLoadMore     = findViewById(R.id.btnLoadMore)
        tvCoverHint     = findViewById(R.id.tvCoverHint)
        tvSelectedCover = findViewById(R.id.tvSelectedCover)
        cardConvert     = findViewById(R.id.cardConvert)
        btnConvert      = findViewById(R.id.btnConvert)
        progressConvert = findViewById(R.id.progressConvert)
        tvProgress      = findViewById(R.id.tvProgress)
        cardResult      = findViewById(R.id.cardResult)
        tvResult        = findViewById(R.id.tvResult)
        btnEmail        = findViewById(R.id.btnEmail)
        btnNew          = findViewById(R.id.btnNew)
        btnSettings     = findViewById(R.id.btnSettings)
    }

    private fun setupCoverRecycler() {
        coverAdapter = CoverAdapter { url ->
            selectedCoverUrl = url
            tvSelectedCover.text = "✓ Capa selecionada"
            tvSelectedCover.visibility = View.VISIBLE
            cardConvert.visibility = View.VISIBLE
        }
        rvCovers.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvCovers.adapter = coverAdapter
    }

    private fun setupListeners() {
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnSelect.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
            }
            startActivityForResult(intent, PDF_PICK)
        }

        btnSearchCover.setOnClickListener { searchCovers() }

        btnLoadMore.setOnClickListener { showMoreCovers() }

        btnConvert.setOnClickListener { startConversion() }

        btnEmail.setOnClickListener { sendEmail() }

        btnNew.setOnClickListener { resetAll() }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PDF_PICK && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                pdfUri = uri
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val filename = getFilename(uri)
                val (title, author) = PdfExtractor.parseFilename(filename)
                pdfTitle  = title
                pdfAuthor = author
                tvPdfName.text = filename
                coverAdapter.clear()
                allCoverUrls  = emptyList()
                shownCount    = 0
                selectedCoverUrl   = null
                selectedCoverBytes = null
                epubUri = null
                updateUI()
            }
        }
    }

    private fun getFilename(uri: Uri): String {
        var name = "documento.pdf"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0) name = cursor.getString(col) ?: name
            }
        }
        return name
    }

    // ── Cover Search ─────────────────────────────────────────────────────────

    private fun searchCovers() {
        coverAdapter.clear()
        allCoverUrls = emptyList()
        shownCount   = 0
        selectedCoverUrl = null
        selectedCoverBytes = null
        tvSelectedCover.visibility = View.GONE
        cardConvert.visibility = View.GONE

        btnSearchCover.isEnabled = false
        btnSearchCover.text = "Buscando..."
        tvCoverHint.text = "Buscando capas na internet…"
        tvCoverHint.visibility = View.VISIBLE

        lifecycleScope.launch {
            val urls = CoverSearcher.searchAll(pdfTitle, pdfAuthor)
            allCoverUrls = urls
            shownCount   = 0

            if (urls.isEmpty()) {
                tvCoverHint.text = "Nenhuma capa encontrada. Tente verificar o nome do arquivo."
            } else {
                tvCoverHint.text = "${urls.size} capa(s) encontrada(s). Toque na desejada."
                showMoreCovers()
            }

            btnSearchCover.isEnabled = true
            btnSearchCover.text = "Buscar Capa Novamente"
        }
    }

    private fun showMoreCovers() {
        val PAGE = 10
        val nextUrls = allCoverUrls.drop(shownCount).take(PAGE)
        if (nextUrls.isEmpty()) {
            btnLoadMore.visibility = View.GONE
            return
        }
        coverAdapter.addItems(nextUrls)
        shownCount += nextUrls.size
        btnLoadMore.visibility = if (shownCount < allCoverUrls.size) View.VISIBLE else View.GONE
    }

    // ── Conversion ───────────────────────────────────────────────────────────

    private fun startConversion() {
        val uri = pdfUri ?: return
        val coverUrl = selectedCoverUrl ?: return

        btnConvert.isEnabled = false
        progressConvert.visibility = View.VISIBLE
        tvProgress.visibility = View.VISIBLE
        progressConvert.isIndeterminate = false
        progressConvert.progress = 0

        lifecycleScope.launch {
            try {
                // 1. Download cover
                tvProgress.text = "Baixando capa…"
                progressConvert.isIndeterminate = true
                val cover = withContext(Dispatchers.IO) {
                    CoverSearcher.downloadBytes(coverUrl)
                } ?: throw Exception("Falha ao baixar capa. Tente outra imagem.")
                selectedCoverBytes = cover

                // 2. Extract PDF text
                progressConvert.isIndeterminate = false
                tvProgress.text = "Extraindo texto do PDF…"
                val pages = PdfExtractor.extract(this@MainActivity, uri) { cur, tot ->
                    val pct = (cur * 50) / tot
                    progressConvert.progress = pct
                    tvProgress.text = "Extraindo texto… página $cur/$tot"
                }

                // 3. Group chapters
                val chapters = PdfExtractor.groupIntoChapters(pages)
                tvProgress.text = "Organizando ${chapters.size} capítulo(s)…"

                // 4. Build EPUB
                epubFilename = buildString {
                    append(pdfTitle.ifBlank { "Livro" })
                    if (pdfAuthor.isNotBlank()) append(" - $pdfAuthor")
                    append(".epub")
                }.replace(Regex("[\\\\/:*?\"<>|]"), "")

                val result = EpubBuilder.build(
                    context  = this@MainActivity,
                    title    = pdfTitle,
                    author   = pdfAuthor,
                    chapters = chapters,
                    coverBytes = cover
                ) { cur, tot ->
                    val pct = 50 + (cur * 50) / tot
                    progressConvert.progress = pct
                    tvProgress.text = "Montando EPUB… $cur/$tot"
                }

                epubUri = result
                progressConvert.progress = 100
                tvProgress.text = "Concluído!"

                // Show result
                cardResult.visibility = View.VISIBLE
                tvResult.text = "✓ EPUB salvo em Downloads:\n$epubFilename"
                btnEmail.isEnabled = Prefs.isEmailConfigured(this@MainActivity)
                btnNew.visibility = View.VISIBLE

                // Cleanup cache
                cacheDir.listFiles()?.forEach { it.delete() }

            } catch (e: Exception) {
                progressConvert.visibility = View.GONE
                tvProgress.visibility = View.GONE
                btnConvert.isEnabled = true
                showError("Erro na conversão: ${e.message}")
            }
        }
    }

    // ── Email ────────────────────────────────────────────────────────────────

    private fun sendEmail() {
        val uri = epubUri ?: return
        btnEmail.isEnabled = false
        btnEmail.text = "Enviando…"

        lifecycleScope.launch {
            val result = EmailSender.send(this@MainActivity, uri, epubFilename)
            if (result.success) {
                Snackbar.make(findViewById(android.R.id.content),
                    "✓ EPUB enviado por e-mail!", Snackbar.LENGTH_LONG).show()
            } else {
                showError(result.error)
                btnEmail.isEnabled = true
            }
            btnEmail.text = "Enviar por E-mail"
        }
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    private fun resetAll() {
        pdfUri             = null
        pdfTitle           = ""
        pdfAuthor          = ""
        allCoverUrls       = emptyList()
        shownCount         = 0
        selectedCoverUrl   = null
        selectedCoverBytes = null
        epubUri            = null
        epubFilename       = ""

        tvPdfName.text = ""
        coverAdapter.clear()
        tvSelectedCover.visibility = View.GONE
        tvCoverHint.visibility = View.GONE
        progressConvert.visibility = View.GONE
        tvProgress.visibility = View.GONE
        cardResult.visibility = View.GONE
        btnNew.visibility = View.GONE
        btnSearchCover.text = "Buscar Capa"
        btnConvert.isEnabled = true
        btnEmail.text = "Enviar por E-mail"

        cacheDir.listFiles()?.forEach { it.delete() }
        updateUI()
    }

    // ── UI State ─────────────────────────────────────────────────────────────

    private fun updateUI() {
        val hasPdf = pdfUri != null
        cardCover.visibility   = if (hasPdf) View.VISIBLE else View.GONE
        cardConvert.visibility = if (hasPdf && selectedCoverUrl != null) View.VISIBLE else View.GONE
        cardResult.visibility  = if (epubUri != null) View.VISIBLE else View.GONE

        if (hasPdf) {
            tvStatus.text = "PDF selecionado. Agora busque a capa."
        } else {
            tvStatus.text = "Selecione um arquivo PDF para começar."
        }
    }

    private fun showError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("Erro")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }
}
