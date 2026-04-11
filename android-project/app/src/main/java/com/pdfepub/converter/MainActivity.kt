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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    // ── Estado ───────────────────────────────────────────────────────────────
    private var pdfUri            : Uri?       = null
    private var pdfTitle          = ""
    private var pdfAuthor         = ""
    private var allCoverUrls      = listOf<String>()
    private var shownCount        = 0
    private var selectedCoverUrl  : String?    = null
    private var selectedCoverBytes: ByteArray? = null
    private var epubUri           : Uri?       = null
    private var epubFilename      = ""
    private var conversionJob     : Job?       = null

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var tvStatus        : TextView
    private lateinit var cardPdf         : MaterialCardView
    private lateinit var tvPdfName       : TextView
    private lateinit var btnSelect       : MaterialButton
    private lateinit var cardCover       : MaterialCardView
    private lateinit var btnSearchCover  : MaterialButton
    private lateinit var btnConvertNoCover: MaterialButton
    private lateinit var rvCovers        : RecyclerView
    private lateinit var btnLoadMore     : MaterialButton
    private lateinit var tvCoverHint     : TextView
    private lateinit var cardConvert     : MaterialCardView
    private lateinit var btnConvert      : MaterialButton
    private lateinit var progressConvert : LinearProgressIndicator
    private lateinit var tvProgress      : TextView
    private lateinit var cardResult      : MaterialCardView
    private lateinit var tvResult        : TextView
    private lateinit var btnEmail        : MaterialButton
    private lateinit var btnNew          : MaterialButton
    private lateinit var btnSettings     : MaterialButton

    private lateinit var coverAdapter: CoverAdapter

    private val PDF_PICK = 101

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupCoverRecycler()
        setupListeners()
        updateUI()
    }

    private fun bindViews() {
        tvStatus         = findViewById(R.id.tvStatus)
        cardPdf          = findViewById(R.id.cardPdf)
        tvPdfName        = findViewById(R.id.tvPdfName)
        btnSelect        = findViewById(R.id.btnSelect)
        cardCover        = findViewById(R.id.cardCover)
        btnSearchCover   = findViewById(R.id.btnSearchCover)
        btnConvertNoCover= findViewById(R.id.btnConvertNoCover)
        rvCovers         = findViewById(R.id.rvCovers)
        btnLoadMore      = findViewById(R.id.btnLoadMore)
        tvCoverHint      = findViewById(R.id.tvCoverHint)
        cardConvert      = findViewById(R.id.cardConvert)
        btnConvert       = findViewById(R.id.btnConvert)
        progressConvert  = findViewById(R.id.progressConvert)
        tvProgress       = findViewById(R.id.tvProgress)
        cardResult       = findViewById(R.id.cardResult)
        tvResult         = findViewById(R.id.tvResult)
        btnEmail         = findViewById(R.id.btnEmail)
        btnNew           = findViewById(R.id.btnNew)
        btnSettings      = findViewById(R.id.btnSettings)
    }

    private fun setupCoverRecycler() {
        coverAdapter = CoverAdapter { url ->
            selectedCoverUrl = url
            // Item 5: sem label — borda verde no item já indica seleção
            btnConvert.isEnabled = true
        }
        rvCovers.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
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
            @Suppress("DEPRECATION")
            startActivityForResult(intent, PDF_PICK)
        }

        btnSearchCover.setOnClickListener    { searchCovers() }
        btnLoadMore.setOnClickListener       { showMoreCovers() }
        btnConvert.setOnClickListener        { startConversion(withCover = true) }
        btnConvertNoCover.setOnClickListener { startConversion(withCover = false) }
        btnEmail.setOnClickListener          { sendEmail() }
        btnNew.setOnClickListener            { resetAll() }
    }

    // ── Item 2: Reset TOTAL ao selecionar novo PDF ────────────────────────────
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PDF_PICK && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                // Cancela qualquer conversão em andamento
                conversionJob?.cancel()
                conversionJob = null

                pdfUri             = uri
                pdfTitle           = ""
                pdfAuthor          = ""
                allCoverUrls       = emptyList()
                shownCount         = 0
                selectedCoverUrl   = null
                selectedCoverBytes = null
                epubUri            = null
                epubFilename       = ""

                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                val filename = getFilename(uri)
                val (title, author) = PdfExtractor.parseFilename(filename)
                pdfTitle  = title
                pdfAuthor = author
                tvPdfName.text = filename

                // Limpa toda UI de capa, conversão e resultado
                coverAdapter.clear()
                tvCoverHint.visibility     = View.GONE
                btnLoadMore.visibility     = View.GONE
                btnSearchCover.text        = "Buscar Capa"
                btnSearchCover.isEnabled   = true
                btnConvert.isEnabled       = false
                progressConvert.visibility = View.GONE
                tvProgress.visibility      = View.GONE
                cardConvert.visibility     = View.GONE
                cardResult.visibility      = View.GONE

                cacheDir.listFiles()?.forEach { it.delete() }
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

    // ── Busca de Capas ────────────────────────────────────────────────────────

    private fun searchCovers() {
        coverAdapter.clear()
        allCoverUrls       = emptyList()
        shownCount         = 0
        selectedCoverUrl   = null
        selectedCoverBytes = null
        btnConvert.isEnabled = false

        btnSearchCover.isEnabled = false
        btnSearchCover.text      = "Buscando..."
        tvCoverHint.text         = "Buscando capas na internet…"
        tvCoverHint.visibility   = View.VISIBLE

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
            btnSearchCover.text      = "Buscar Capa Novamente"
        }
    }

    private fun showMoreCovers() {
        val PAGE     = 10
        val nextUrls = allCoverUrls.drop(shownCount).take(PAGE)
        if (nextUrls.isEmpty()) { btnLoadMore.visibility = View.GONE; return }
        coverAdapter.addItems(nextUrls)
        shownCount += nextUrls.size
        btnLoadMore.visibility =
            if (shownCount < allCoverUrls.size) View.VISIBLE else View.GONE
    }

    // ── Conversão ─────────────────────────────────────────────────────────────

    private fun startConversion(withCover: Boolean) {
        val uri = pdfUri ?: return
        if (withCover && selectedCoverUrl == null) {
            showError("Selecione uma capa primeiro, ou use "⚡ Converter sem Capa".")
            return
        }

        // Mostra o cardConvert com progresso, esconde o resultado anterior
        cardConvert.visibility     = View.VISIBLE
        cardResult.visibility      = View.GONE
        btnConvert.isEnabled       = false
        btnConvertNoCover.isEnabled= false
        progressConvert.visibility = View.VISIBLE
        tvProgress.visibility      = View.VISIBLE
        progressConvert.isIndeterminate = false
        progressConvert.progress   = 0

        conversionJob = lifecycleScope.launch {
            try {
                var cover: ByteArray? = null

                if (withCover) {
                    tvProgress.text = "Baixando capa…"
                    progressConvert.isIndeterminate = true
                    cover = withContext(Dispatchers.IO) {
                        CoverSearcher.downloadBytes(selectedCoverUrl!!)
                    } ?: throw Exception("Falha ao baixar capa. Tente outra imagem.")
                    selectedCoverBytes = cover
                    progressConvert.isIndeterminate = false
                }

                tvProgress.text = "Extraindo texto do PDF…"
                val offsetEnd = if (withCover) 50 else 80
                val pages = PdfExtractor.extract(this@MainActivity, uri) { cur, tot ->
                    val pct = (cur * offsetEnd) / tot
                    progressConvert.progress = pct
                    tvProgress.text = "Extraindo… página $cur/$tot"
                }

                val chapters = PdfExtractor.groupIntoChapters(pages)
                tvProgress.text = "Organizando ${chapters.size} capítulo(s)…"

                epubFilename = buildString {
                    append(pdfTitle.ifBlank { "Livro" })
                    if (pdfAuthor.isNotBlank()) append(" - $pdfAuthor")
                    append(".epub")
                }.replace(Regex("[\\\\/:*?\"<>|]"), "")

                val progressBase = if (withCover) 50 else 20
                val result = EpubBuilder.build(
                    context    = this@MainActivity,
                    title      = pdfTitle,
                    author     = pdfAuthor,
                    chapters   = chapters,
                    coverBytes = cover
                ) { cur, tot ->
                    val pct = progressBase + (cur * (100 - progressBase)) / tot
                    progressConvert.progress = pct
                    tvProgress.text = "Montando EPUB… $cur/$tot"
                }

                epubUri = result
                progressConvert.progress = 100
                tvProgress.text = "Concluído!"

                // ── Item 6: esconde cardConvert, exibe cardResult no lugar ──
                cardConvert.visibility = View.GONE
                cardResult.visibility  = View.VISIBLE
                tvResult.text =
                    "✓ EPUB salvo em Downloads" +
                    (if (!withCover) " (sem capa)" else "") +
                    ":\n$epubFilename"
                btnEmail.isEnabled = Prefs.isEmailConfigured(this@MainActivity)

                cacheDir.listFiles()?.forEach { it.delete() }

            } catch (e: Exception) {
                progressConvert.visibility  = View.GONE
                tvProgress.visibility       = View.GONE
                btnConvert.isEnabled        = (selectedCoverUrl != null)
                btnConvertNoCover.isEnabled = true
                showError("Erro na conversão: ${e.message}")
            }
        }
    }

    // ── E-mail ────────────────────────────────────────────────────────────────

    private fun sendEmail() {
        val uri = epubUri ?: return
        btnEmail.isEnabled = false
        btnEmail.text      = "Enviando…"

        lifecycleScope.launch {
            val result = EmailSender.send(this@MainActivity, uri, epubFilename)
            if (result.success) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "✓ EPUB enviado por e-mail!", Snackbar.LENGTH_LONG
                ).show()
            } else {
                showError(result.error)
                btnEmail.isEnabled = true
            }
            btnEmail.text = "Enviar por E-mail"
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    private fun resetAll() {
        conversionJob?.cancel()
        conversionJob      = null
        pdfUri             = null
        pdfTitle           = ""
        pdfAuthor          = ""
        allCoverUrls       = emptyList()
        shownCount         = 0
        selectedCoverUrl   = null
        selectedCoverBytes = null
        epubUri            = null
        epubFilename       = ""

        tvPdfName.text             = "Nenhum arquivo selecionado"
        coverAdapter.clear()
        tvCoverHint.visibility     = View.GONE
        btnLoadMore.visibility     = View.GONE
        progressConvert.visibility = View.GONE
        tvProgress.visibility      = View.GONE
        cardConvert.visibility     = View.GONE
        cardResult.visibility      = View.GONE
        btnSearchCover.text        = "Buscar Capa"
        btnSearchCover.isEnabled   = true
        btnConvert.isEnabled       = false
        btnConvertNoCover.isEnabled= true
        btnEmail.text              = "Enviar por E-mail"

        cacheDir.listFiles()?.forEach { it.delete() }
        updateUI()
    }

    // ── UI State ──────────────────────────────────────────────────────────────

    private fun updateUI() {
        val hasPdf = pdfUri != null
        cardCover.visibility = if (hasPdf) View.VISIBLE else View.GONE

        tvStatus.text = when {
            !hasPdf      -> "Selecione um arquivo PDF para começar."
            epubUri != null -> "EPUB gerado com sucesso!"
            else         -> "PDF selecionado. Busque uma capa ou converta diretamente."
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
