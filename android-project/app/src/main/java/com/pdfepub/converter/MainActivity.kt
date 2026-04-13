package com.pdfepub.converter

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
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

    // Limite de 50 MB para PDF
    private val PDF_MAX_BYTES = 50L * 1024 * 1024

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

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
                btnConvert.text = getString(R.string.btn_convert_has_cover)
                cardResult.visibility = View.GONE
                cardConvert.post { mainScrollView.smoothScrollTo(0, cardConvert.bottom) }
            },
            onDeselected = {
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

    private fun getPdfSize(uri: Uri): Long {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize
            } ?: -1L
        } catch (e: Exception) { -1L }
    }

    private fun handlePdfSelected(uri: Uri) {
        // Verificar tamanho — limite 50 MB
        val size = getPdfSize(uri)
        if (size > PDF_MAX_BYTES) {
            val sizeMb = size / (1024.0 * 1024.0)
            DialogHelper.error(this, getString(R.string.error_pdf_too_large, sizeMb))
            return
        }

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
        btnSearchCover.text         = getString(R.string.btn_search_cover)
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
                DialogHelper.error(this@MainActivity, getString(R.string.error_invalid_image))
                return@launch
            }
            localCoverBytes = bytes
            coverAdapter.clearSelection(); selectedCoverUrl = null
            imgLocalCover.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            layoutLocalCover.visibility = View.VISIBLE
            btnConvert.text = getString(R.string.btn_convert_has_cover)
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
        btnConvert.text = getString(if (hasCover) R.string.btn_convert_has_cover else R.string.btn_convert_no_cover)
    }

    private fun getFilename(uri: Uri): String {
        var name = "documento.pdf"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) { val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (col >= 0) name = c.getString(col) ?: name }
        }
        return name
    }

    private fun searchCovers() {
        coverAdapter.clear()
        allCoverUrls = emptyList(); shownCount = 0
        selectedCoverUrl = null
        rvCovers.visibility = View.GONE; btnLoadMore.visibility = View.GONE
        if (localCoverBytes == null) btnConvert.text = getString(R.string.btn_convert_no_cover)

        btnSearchCover.isEnabled = false
        btnSearchCover.text = getString(R.string.searching)
        tvCoverHint.text = getString(R.string.cover_hint_searching)
        tvCoverHint.visibility = View.VISIBLE

        lifecycleScope.launch {
            val urls = CoverSearcher.searchAll(pdfTitle, pdfAuthor)
            allCoverUrls = urls; shownCount = 0

            if (urls.isEmpty()) {
                tvCoverHint.text = getString(R.string.cover_hint_not_found)
                rvCovers.visibility = View.GONE; btnLoadMore.visibility = View.GONE
            } else {
                tvCoverHint.text = getString(R.string.cover_hint_found, urls.size)
                rvCovers.visibility = View.VISIBLE
                showMoreCovers()
                rvCovers.post { mainScrollView.smoothScrollTo(0, rvCovers.bottom + 80) }
            }
            btnSearchCover.isEnabled = true
            btnSearchCover.text = getString(R.string.btn_search_cover_again)
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
                    tvProgress.text = getString(R.string.downloading_cover)
                    progressConvert.isIndeterminate = true
                    cover = withContext(Dispatchers.IO) { CoverSearcher.downloadBytes(selectedCoverUrl!!) }
                        ?: throw Exception(getString(R.string.error_cover_download))
                    progressConvert.isIndeterminate = false
                }

                tvProgress.text = getString(R.string.progress_extracting)
                val offsetEnd = if (cover != null) 50 else 80
                val pages = PdfExtractor.extract(this@MainActivity, uri) { cur, tot ->
                    progressConvert.progress = (cur * offsetEnd) / tot
                    tvProgress.text = getString(R.string.progress_page, cur, tot)
                }

                val chapters = PdfExtractor.groupIntoChapters(pages)
                tvProgress.text = getString(R.string.progress_organizing, chapters.size)

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
                    tvProgress.text = getString(R.string.mounting_epub, cur, tot)
                }

                epubCacheFile = cacheFile
                progressConvert.progress = 100
                tvProgress.text = getString(R.string.progress_done)

                cardConvert.visibility = View.GONE
                cardResult.visibility  = View.VISIBLE
                tvResult.text = getString(R.string.result_epub_ready) +
                    (if (cover == null) getString(R.string.result_no_cover) else "") +
                    "\n$epubFilename" +
                    getString(R.string.result_instructions)
                btnEmail.isEnabled = Prefs.isEmailConfigured(this@MainActivity)
                cardResult.post { mainScrollView.smoothScrollTo(0, cardResult.bottom) }
                cacheDir.listFiles()?.filter { !it.name.startsWith("epub_") }?.forEach { it.delete() }

            } catch (e: Exception) {
                progressConvert.visibility = View.GONE; tvProgress.visibility = View.GONE
                btnConvert.isEnabled = true
                DialogHelper.error(this@MainActivity, getString(R.string.conversion_error, e.message ?: ""))
            }
        }
    }

    private fun downloadEpub() {
        val f = epubCacheFile ?: run {
            DialogHelper.error(this, getString(R.string.error_file_not_found)); return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { EpubBuilder.saveToDestination(this@MainActivity, f, epubFilename) }
                val path = EpubBuilder.getSavePathLabel(this@MainActivity)
                DialogHelper.success(this@MainActivity, getString(R.string.epub_saved_ok, path, epubFilename))
            } catch (e: Exception) {
                DialogHelper.error(this@MainActivity, getString(R.string.save_error, e.message ?: ""))
            }
        }
    }

    private fun sendEmail() {
        val f = epubCacheFile ?: run {
            DialogHelper.error(this, getString(R.string.error_file_not_found)); return
        }
        btnEmail.isEnabled = false
        btnEmail.text = getString(R.string.sending)
        lifecycleScope.launch {
            val result = EmailSender.send(this@MainActivity, Uri.fromFile(f), epubFilename)
            if (result.success) {
                deleteCacheFile()
                DialogHelper.success(this@MainActivity, getString(R.string.epub_sent_ok))
            } else {
                DialogHelper.error(this@MainActivity, result.error)
                btnEmail.isEnabled = true
            }
            btnEmail.text = getString(R.string.btn_email)
        }
    }

    private fun resetAll() {
        conversionJob?.cancel(); conversionJob = null
        deleteCacheFile()
        pdfUri = null; pdfTitle = ""; pdfAuthor = ""
        allCoverUrls = emptyList(); shownCount = 0
        selectedCoverUrl = null; localCoverBytes = null
        epubCacheFile = null; epubFilename = ""

        tvPdfName.text = getString(R.string.label_no_file)
        coverAdapter.clear()
        layoutLocalCover.visibility = View.GONE
        tvCoverHint.visibility      = View.GONE
        rvCovers.visibility         = View.GONE
        btnLoadMore.visibility      = View.GONE
        progressConvert.visibility  = View.GONE
        tvProgress.visibility       = View.GONE
        cardConvert.visibility      = View.GONE
        cardResult.visibility       = View.GONE
        btnSearchCover.text         = getString(R.string.btn_search_cover)
        btnSearchCover.isEnabled    = true
        btnConvert.text             = getString(R.string.btn_convert_no_cover)
        btnConvert.isEnabled        = true
        btnEmail.text               = getString(R.string.btn_email)
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
        cardConvert.visibility = if (hasPdf && !hasEpub) View.VISIBLE else View.GONE
        cardResult.visibility  = if (hasEpub) View.VISIBLE else View.GONE

        if (hasPdf) updateConvertButton()

        tvStatus.text = when {
            !hasPdf  -> getString(R.string.status_select_pdf)
            hasEpub  -> getString(R.string.status_epub_ready)
            else     -> getString(R.string.status_pdf_selected)
        }
    }
}
