package com.pdfepub.converter

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    // ── Estado ───────────────────────────────────────────────────────────────
    private var pdfUri            : Uri?       = null
    private var pdfTitle          = ""
    private var pdfAuthor         = ""
    private var allCoverUrls      = listOf<String>()
    private var shownCount        = 0
    private var selectedCoverUrl  : String?    = null
    private var localCoverBytes   : ByteArray? = null
    private var epubCacheFile     : File?      = null
    private var epubFilename      = ""
    private var conversionJob     : Job?       = null

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var mainScrollView     : ScrollView
    private lateinit var tvStatus           : TextView
    private lateinit var cardPdf            : MaterialCardView
    private lateinit var tvPdfName          : TextView
    private lateinit var btnSelect          : MaterialButton
    private lateinit var cardCover          : MaterialCardView
    private lateinit var btnSearchCover     : MaterialButton
    private lateinit var btnSelectLocalCover: MaterialButton
    private lateinit var layoutLocalCover   : LinearLayout
    private lateinit var cardLocalCoverPreview: MaterialCardView
    private lateinit var imgLocalCover      : ImageView
    private lateinit var btnRemoveLocalCover: MaterialButton
    private lateinit var rvCovers           : RecyclerView
    private lateinit var btnLoadMore        : MaterialButton
    private lateinit var tvCoverHint        : TextView
    private lateinit var cardConvert        : MaterialCardView
    private lateinit var btnConvert         : MaterialButton
    private lateinit var progressConvert    : LinearProgressIndicator
    private lateinit var tvProgress         : TextView
    private lateinit var cardResult         : MaterialCardView
    private lateinit var tvResult           : TextView
    private lateinit var btnDownload        : MaterialButton
    private lateinit var btnEmail           : MaterialButton
    private lateinit var btnNew             : MaterialButton
    private lateinit var btnSettings        : ImageButton

    private lateinit var coverAdapter: CoverAdapter

    private val PDF_PICK     = 101
    private val GALLERY_PICK = 102

    private val TXT_NO_COVER  = "Converter para EPUB (Sem capa)"
    private val TXT_HAS_COVER = "Converter para EPUB (Capa selecionada)"

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
        mainScrollView      = findViewById(R.id.mainScrollView)
        tvStatus            = findViewById(R.id.tvStatus)
        cardPdf             = findViewById(R.id.cardPdf)
        tvPdfName           = findViewById(R.id.tvPdfName)
        btnSelect           = findViewById(R.id.btnSelect)
        cardCover           = findViewById(R.id.cardCover)
        btnSearchCover      = findViewById(R.id.btnSearchCover)
        btnSelectLocalCover = findViewById(R.id.btnSelectLocalCover)
        layoutLocalCover    = findViewById(R.id.layoutLocalCover)
        cardLocalCoverPreview = findViewById(R.id.cardLocalCoverPreview)
        imgLocalCover       = findViewById(R.id.imgLocalCover)
        btnRemoveLocalCover = findViewById(R.id.btnRemoveLocalCover)
        rvCovers            = findViewById(R.id.rvCovers)
        btnLoadMore         = findViewById(R.id.btnLoadMore)
        tvCoverHint         = findViewById(R.id.tvCoverHint)
        cardConvert         = findViewById(R.id.cardConvert)
        btnConvert          = findViewById(R.id.btnConvert)
        progressConvert     = findViewById(R.id.progressConvert)
        tvProgress          = findViewById(R.id.tvProgress)
        cardResult          = findViewById(R.id.cardResult)
        tvResult            = findViewById(R.id.tvResult)
        btnDownload         = findViewById(R.id.btnDownload)
        btnEmail            = findViewById(R.id.btnEmail)
        btnNew              = findViewById(R.id.btnNew)
        btnSettings         = findViewById(R.id.btnSettings)
    }

    private fun setupCoverRecycler() {
        coverAdapter = CoverAdapter(
            onSelected = { url ->
                selectedCoverUrl = url
                if (localCoverBytes != null) clearLocalCover(keepConvert = true)
                btnConvert.text = TXT_HAS_COVER
                cardResult.visibility = View.GONE
                // scroll para botão de converter
                cardConvert.post { mainScrollView.smoothScrollTo(0, cardConvert.bottom) }
            },
            onDeselected = {
                // item 6: des-selecionou capa do carrossel
                selectedCoverUrl = null
                updateConvertButton()
            }
        )
        rvCovers.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvCovers.adapter = coverAdapter
    }

    private fun setupListeners() {
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        btnSelect.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"
            }, PDF_PICK)
        }

        btnSelectLocalCover.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_PICK).apply { type = "image/*" }, GALLERY_PICK)
        }

        btnRemoveLocalCover.setOnClickListener { clearLocalCover(keepConvert = false) }
        btnSearchCover.setOnClickListener      { searchCovers() }
        btnLoadMore.setOnClickListener         { showMoreCovers() }
        btnConvert.setOnClickListener          { startConversion() }
        btnDownload.setOnClickListener         { downloadEpub() }
        btnEmail.setOnClickListener            { sendEmail() }
        btnNew.setOnClickListener              { resetAll() }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when {
            requestCode == PDF_PICK     && resultCode == Activity.RESULT_OK -> data?.data?.let { handlePdfSelected(it) }
            requestCode == GALLERY_PICK && resultCode == Activity.RESULT_OK -> data?.data?.let { handleGalleryImageSelected(it) }
        }
    }

    private fun handlePdfSelected(uri: Uri) {
        conversionJob?.cancel(); conversionJob = null
        deleteCacheFile()

        pdfUri = uri; pdfTitle = ""; pdfAuthor = ""
        allCoverUrls = emptyList(); shownCount = 0
        selectedCoverUrl = null; localCoverBytes = null
        epubFilename = ""; epubCacheFile = null

        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val filename = getFilename(uri)
        val (title, author) = PdfExtractor.parseFilename(filename)
        pdfTitle = title; pdfAuthor = author
        tvPdfName.text = filename

        coverAdapter.clear()
        layoutLocalCover.visibility = View.GONE
        tvCoverHint.visibility      = View.GONE
        rvCovers.visibility         = View.GONE
        btnLoadMore.visibility      = View.GONE
        btnSearchCover.text         = "Buscar Capa"
        btnSearchCover.isEnabled    = true
        progressConvert.visibility  = View.GONE
        tvProgress.visibility       = View.GONE
        cardResult.visibility       = View.GONE

        cacheDir.listFiles()?.filter { it.name.startsWith("epub_") }?.forEach { it.delete() }
        updateUI()
    }

    private fun handleGalleryImageSelected(uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    val stream = contentResolver.openInputStream(uri) ?: return@withContext null
                    val bitmap = BitmapFactory.decodeStream(stream)
                    stream.close()
                    if (bitmap == null || bitmap.width < 50 || bitmap.height < 50) return@withContext null
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.toByteArray()
                } catch (e: Exception) { null }
            }
            if (bytes == null) {
                DialogHelper.error(this@MainActivity, "Imagem inválida ou corrompida. Selecione outra foto.")
                return@launch
            }
            localCoverBytes = bytes
            // item 5: des-seleciona carrossel
            coverAdapter.clearSelection(); selectedCoverUrl = null
            imgLocalCover.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            layoutLocalCover.visibility = View.VISIBLE
            btnConvert.text = TXT_HAS_COVER
            cardResult.visibility = View.GONE
            cardConvert.post { mainScrollView.smoothScrollTo(0, cardConvert.bottom) }
        }
    }

    private fun clearLocalCover(keepConvert: Boolean) {
        localCoverBytes = null
        layoutLocalCover.visibility = View.GONE
        imgLocalCover.setImageDrawable(null)
        if (!keepConvert) updateConvertButton()
    }

    private fun updateConvertButton() {
        val hasCover = selectedCoverUrl != null || localCoverBytes != null
        btnConvert.text = if (hasCover) TXT_HAS_COVER else TXT_NO_COVER
    }

    private fun getFilename(uri: Uri): String {
        var name = "documento.pdf"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) { val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (col >= 0) name = c.getString(col) ?: name }
        }
        return name
    }

    // ── Busca de Capas ────────────────────────────────────────────────────────

    private fun searchCovers() {
        coverAdapter.clear()
        allCoverUrls = emptyList(); shownCount = 0
        selectedCoverUrl = null
        rvCovers.visibility = View.GONE; btnLoadMore.visibility = View.GONE
        if (localCoverBytes == null) btnConvert.text = TXT_NO_COVER

        btnSearchCover.isEnabled = false; btnSearchCover.text = "Buscando..."
        tvCoverHint.text = "Buscando capas na internet…"; tvCoverHint.visibility = View.VISIBLE

        lifecycleScope.launch {
            val urls = CoverSearcher.searchAll(pdfTitle, pdfAuthor)
            allCoverUrls = urls; shownCount = 0

            if (urls.isEmpty()) {
                tvCoverHint.text = "Nenhuma capa encontrada. Verifique o nome do arquivo."
                rvCovers.visibility = View.GONE; btnLoadMore.visibility = View.GONE
            } else {
                tvCoverHint.text = "${urls.size} capa(s) encontrada(s). Toque na desejada."
                rvCovers.visibility = View.VISIBLE
                showMoreCovers()
                // item 4: scroll para mostrar o carrossel
                rvCovers.post { mainScrollView.smoothScrollTo(0, rvCovers.bottom + 80) }
            }
            btnSearchCover.isEnabled = true; btnSearchCover.text = "Buscar Capa Novamente"
        }
    }

    private fun showMoreCovers() {
        val PAGE      = 10
        val remaining = allCoverUrls.drop(shownCount)
        if (remaining.isEmpty()) { btnLoadMore.visibility = View.GONE; return }
        val nextUrls  = remaining.take(PAGE)
        coverAdapter.addItems(nextUrls)
        shownCount += nextUrls.size
        btnLoadMore.visibility = if (shownCount < allCoverUrls.size) View.VISIBLE else View.GONE
    }

    // ── Conversão ─────────────────────────────────────────────────────────────

    private fun startConversion() {
        val uri = pdfUri ?: return
        deleteCacheFile()

        cardConvert.visibility      = View.VISIBLE
        cardResult.visibility       = View.GONE
        btnConvert.isEnabled        = false
        progressConvert.visibility  = View.VISIBLE
        tvProgress.visibility       = View.VISIBLE
        progressConvert.isIndeterminate = false
        progressConvert.progress    = 0

        conversionJob = lifecycleScope.launch {
            try {
                var cover: ByteArray? = localCoverBytes

                if (cover == null && selectedCoverUrl != null) {
                    tvProgress.text = "Baixando capa…"
                    progressConvert.isIndeterminate = true
                    cover = withContext(Dispatchers.IO) { CoverSearcher.downloadBytes(selectedCoverUrl!!) }
                        ?: throw Exception("Falha ao baixar capa. Tente outra imagem.")
                    progressConvert.isIndeterminate = false
                }

                tvProgress.text = "Extraindo texto do PDF…"
                val offsetEnd = if (cover != null) 50 else 80
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

                val progressBase = if (cover != null) 50 else 20
                val cacheFile = EpubBuilder.build(
                    context = this@MainActivity, title = pdfTitle, author = pdfAuthor,
                    chapters = chapters, coverBytes = cover
                ) { cur, tot ->
                    progressConvert.progress = progressBase + (cur * (100 - progressBase)) / tot
                    tvProgress.text = "Montando EPUB… $cur/$tot"
                }

                epubCacheFile = cacheFile
                progressConvert.progress = 100
                tvProgress.text = "Concluído!"

                cardConvert.visibility = View.GONE
                cardResult.visibility  = View.VISIBLE
                tvResult.text = "✅  EPUB pronto!" +
                    (if (cover == null) " (sem capa)" else "") +
                    "\n$epubFilename\n\nClique em Baixar para salvar ou Enviar por E-mail."
                btnEmail.isEnabled = Prefs.isEmailConfigured(this@MainActivity)
                cardResult.post { mainScrollView.smoothScrollTo(0, cardResult.bottom) }
                cacheDir.listFiles()?.filter { !it.name.startsWith("epub_") }?.forEach { it.delete() }

            } catch (e: Exception) {
                progressConvert.visibility = View.GONE; tvProgress.visibility = View.GONE
                btnConvert.isEnabled = true
                DialogHelper.error(this@MainActivity, "Erro na conversão:\n${e.message}")
            }
        }
    }

    private fun downloadEpub() {
        val f = epubCacheFile ?: run { DialogHelper.error(this, "Arquivo não encontrado. Converta novamente."); return }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { EpubBuilder.saveToDestination(this@MainActivity, f, epubFilename) }
                val path = EpubBuilder.getSavePathLabel(this@MainActivity)
                DialogHelper.success(this@MainActivity, "✅  EPUB salvo em:\n$path\n\n$epubFilename")
            } catch (e: Exception) {
                DialogHelper.error(this@MainActivity, "Erro ao salvar:\n${e.message}")
            }
        }
    }

    private fun sendEmail() {
        val f = epubCacheFile ?: run { DialogHelper.error(this, "Arquivo não encontrado. Converta novamente."); return }
        btnEmail.isEnabled = false; btnEmail.text = "Enviando…"
        lifecycleScope.launch {
            val result = EmailSender.send(this@MainActivity, Uri.fromFile(f), epubFilename)
            if (result.success) {
                deleteCacheFile()
                DialogHelper.success(this@MainActivity, "✅  EPUB enviado com sucesso!")
            } else {
                DialogHelper.error(this@MainActivity, result.error)
                btnEmail.isEnabled = true
            }
            btnEmail.text = "Enviar por E-mail"
        }
    }

    private fun resetAll() {
        conversionJob?.cancel(); conversionJob = null
        deleteCacheFile()
        pdfUri = null; pdfTitle = ""; pdfAuthor = ""
        allCoverUrls = emptyList(); shownCount = 0
        selectedCoverUrl = null; localCoverBytes = null
        epubCacheFile = null; epubFilename = ""

        tvPdfName.text = "Nenhum arquivo selecionado"
        coverAdapter.clear()
        layoutLocalCover.visibility = View.GONE
        tvCoverHint.visibility      = View.GONE
        rvCovers.visibility         = View.GONE
        btnLoadMore.visibility      = View.GONE
        progressConvert.visibility  = View.GONE
        tvProgress.visibility       = View.GONE
        cardConvert.visibility      = View.GONE
        cardResult.visibility       = View.GONE
        btnSearchCover.text         = "Buscar Capa"
        btnSearchCover.isEnabled    = true
        btnConvert.text             = TXT_NO_COVER
        btnConvert.isEnabled        = true
        btnEmail.text               = "Enviar por E-mail"
        updateUI()
    }

    private fun deleteCacheFile() {
        epubCacheFile?.let { if (it.exists()) it.delete() }
        epubCacheFile = null
    }

    private fun updateUI() {
        val hasPdf  = pdfUri != null
        val hasEpub = epubCacheFile != null

        cardCover.visibility   = if (hasPdf) View.VISIBLE else View.GONE
        // item 5: cardConvert sempre visível quando PDF selecionado
        cardConvert.visibility = if (hasPdf && !hasEpub) View.VISIBLE else View.GONE
        cardResult.visibility  = if (hasEpub) View.VISIBLE else View.GONE

        if (hasPdf) updateConvertButton()

        tvStatus.text = when {
            !hasPdf  -> "Selecione um arquivo PDF para começar."
            hasEpub  -> "✅  EPUB gerado! Baixe ou envie por e-mail."
            else     -> "PDF selecionado. Busque uma capa ou converta."
        }
    }
}
