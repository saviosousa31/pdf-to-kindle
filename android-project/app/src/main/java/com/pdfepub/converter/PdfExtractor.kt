package com.pdfepub.converter

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PdfPage(val number: Int, val text: String)
data class PdfChapter(val title: String, val text: String)

object PdfExtractor {

    suspend fun extract(
        context: Context,
        uri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<PdfPage> = withContext(Dispatchers.IO) {
        val pages = mutableListOf<PdfPage>()

        context.contentResolver.openInputStream(uri)?.use { stream ->
            val doc = PDDocument.load(stream)
            val total = doc.numberOfPages
            val stripper = PDFTextStripper()

            for (i in 1..total) {
                stripper.startPage = i
                stripper.endPage = i
                val text = try {
                    stripper.getText(doc).trim()
                } catch (_: Exception) {
                    ""
                }
                pages += PdfPage(i, text)
                withContext(Dispatchers.Main) { onProgress(i, total) }
            }
            doc.close()
        }
        pages
    }

    fun groupIntoChapters(pages: List<PdfPage>, pagesPerChapter: Int = 10): List<PdfChapter> {
        val chapters = mutableListOf<PdfChapter>()
        for (i in pages.indices step pagesPerChapter) {
            val chunk = pages.subList(i, minOf(i + pagesPerChapter, pages.size))
            val start = chunk.first().number
            val end   = chunk.last().number
            val title = if (chunk.size > 1) "Páginas $start–$end" else "Página $start"
            val body  = chunk.filter { it.text.isNotBlank() }.joinToString("\n\n") { it.text }
            chapters += PdfChapter(title, body)
        }
        return chapters
    }

    /** Parses "Title - Author.pdf" → Pair(title, author) */
    fun parseFilename(filename: String): Pair<String, String> {
        val name = filename.removeSuffix(".pdf").removeSuffix(".PDF")
        for (sep in listOf(" - ", " – ", " — ", " | ")) {
            if (sep in name) {
                val parts = name.split(sep, limit = 2)
                return Pair(parts[0].trim(), parts[1].trim())
            }
        }
        return Pair(name.trim(), "")
    }
}
