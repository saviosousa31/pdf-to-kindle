package com.pdfepub.converter

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.content.ContentUris

object EpubBuilder {

    private fun sanitizeXmlChars(input: String): String {
        val out = StringBuilder(input.length)
        for (ch in input) {
            val code = ch.code
            val isValidXmlChar =
                ch == '\t' || ch == '\n' || ch == '\r' ||
                        code in 0x20..0xD7FF ||
                        code in 0xE000..0xFFFD

            if (isValidXmlChar) out.append(ch)
        }
        return out.toString()
    }

    private fun esc(s: String) = sanitizeXmlChars(s)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun textToXhtml(text: String): String {
        val lines = sanitizeXmlChars(text)
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split('\n')
            .map { it.trimEnd() }

        if (lines.none { it.isNotBlank() }) {
            return "<p><em>(Página sem texto detectável)</em></p>"
        }

        fun isTableLike(line: String): Boolean {
            val t = line.trim()
            if (t.isBlank()) return false
            if (t.length > 160) return false

            val hasDotLeader = Regex("""\.{3,}""").containsMatchIn(t)
            val hasManySpaces = Regex("""\s{2,}""").containsMatchIn(t)
            val endsWithNumber = Regex("""\d+\s*(?:days?|‘)?$""", RegexOption.IGNORE_CASE).containsMatchIn(t)

            return hasDotLeader || (hasManySpaces && endsWithNumber)
        }

        fun startsNewDialogueParagraph(next: String): Boolean {
            val t = next.trimStart()
            if (t.isBlank()) return false
            return t.startsWith("‘") || t.startsWith("“") || t.startsWith("\"") || t.startsWith("'")
        }

        fun joinProseLines(lines: List<String>): String {
            val out = StringBuilder()

            for (raw in lines) {
                val line = raw.trim()
                if (line.isBlank()) continue

                if (out.isEmpty()) {
                    out.append(line)
                } else {
                    if (out.last() == '-') {
                        out.setLength(out.length - 1)
                        out.append(line.trimStart())
                    } else {
                        out.append(' ')
                        out.append(line)
                    }
                }
            }

            return out.toString().trim()
        }

        val blocks = mutableListOf<String>()
        val proseLines = mutableListOf<String>()
        val tableLines = mutableListOf<String>()

        fun flushProse() {
            if (proseLines.isEmpty()) return

            val paragraph = joinProseLines(proseLines)
            if (paragraph.isNotBlank()) {
                blocks += "<p>${esc(paragraph)}</p>"
            }
            proseLines.clear()
        }

        fun flushTable() {
            if (tableLines.isEmpty()) return

            blocks += """
            <div class="pdf-pre">${tableLines.joinToString("<br/>\n") { esc(it.trim()) }}</div>
        """.trimIndent()

            tableLines.clear()
        }

        for (rawLine in lines) {
            val line = rawLine.trim()

            if (line.isBlank()) {
                flushProse()
                flushTable()
                continue
            }

            if (isTableLike(line)) {
                flushProse()
                tableLines += line
                continue
            }

            if (tableLines.isNotEmpty()) {
                flushTable()
            }

            if (proseLines.isEmpty()) {
                proseLines += line
                continue
            }

            val prev = proseLines.last()
            val hardBreak = prev.trimEnd().endsWithAny('.', '!', '?', '’', '”') &&
                    startsNewDialogueParagraph(line)

            if (hardBreak) {
                flushProse()
                proseLines += line
            } else {
                proseLines += line
            }
        }

        flushProse()
        flushTable()

        return blocks.joinToString("\n").ifBlank {
            "<p><em>(Página sem texto detectável)</em></p>"
        }
    }

    private fun String.endsWithAny(vararg chars: Char): Boolean {
        val t = this.trimEnd()
        val last = t.lastOrNull() ?: return false
        return chars.contains(last)
    }

    /**
     * Constrói o EPUB e salva em cache temporário.
     * Chame [saveToDestination] para mover para o destino permanente,
     * ou [deleteCache] para limpar após envio de e-mail.
     */
    suspend fun build(
        context: Context,
        title: String,
        author: String,
        chapters: List<PdfChapter>,
        coverBytes: ByteArray?,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {

        val uid  = UUID.randomUUID().toString()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val et   = esc(title)
        val ea   = esc(author.ifBlank { "Desconhecido" })

        val safeTitle  = title.replace(Regex("[^\\w\\s-]"), "").trim().ifBlank { "Livro" }
        val safeAuthor = author.replace(Regex("[^\\w\\s-]"), "").trim()
        val fname = if (safeAuthor.isBlank()) "$safeTitle.epub" else "$safeTitle - $safeAuthor.epub"

        val items = StringBuilder(); val refs = StringBuilder()
        val navPts = StringBuilder(); val navLi = StringBuilder()

        chapters.forEachIndexed { idx, ch ->
            val cid = "ch%04d".format(idx + 1); val href = "$cid.xhtml"; val et2 = esc(ch.title)
            items.append("""    <item id="$cid" href="$href" media-type="application/xhtml+xml"/>${""}""").append('\n')
            refs.append("""    <itemref idref="$cid"/>${""}""").append('\n')
            navPts.append("    <navPoint id=\"$cid\" playOrder=\"${idx+2}\">\n      <navLabel><text>$et2</text></navLabel>\n      <content src=\"$href\"/>\n    </navPoint>\n")
            navLi.append("      <li><a href=\"$href\">$et2</a></li>\n")
        }

        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="book-id" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">$uid</dc:identifier>
    <dc:title>$et</dc:title><dc:creator>$ea</dc:creator>
    <dc:language>pt</dc:language><dc:date>$date</dc:date>
    ${if (coverBytes != null) "<meta name=\"cover\" content=\"cover-image\"/>" else ""}
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    ${if (coverBytes != null) "<item id=\"cover-page\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>" else ""}
    ${if (coverBytes != null) "<item id=\"cover-image\" href=\"images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>" else ""}
    <item id="css" href="style.css" media-type="text/css"/>
$items  </manifest>
  <spine toc="ncx">
    ${if (coverBytes != null) "<itemref idref=\"cover-page\"/>" else ""}
$refs  </spine>
</package>"""

        val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="$uid"/></head>
  <docTitle><text>$et</text></docTitle>
  <navMap>
    ${if (coverBytes != null) "<navPoint id=\"cover\" playOrder=\"1\"><navLabel><text>Capa</text></navLabel><content src=\"cover.xhtml\"/></navPoint>" else ""}
$navPts  </navMap>
</ncx>"""

        val nav = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><meta charset="UTF-8"/><title>Índice</title></head>
<body><nav epub:type="toc" id="toc"><h1>Índice</h1><ol>
${if (coverBytes != null) "<li><a href=\"cover.xhtml\">Capa</a></li>" else ""}
$navLi</ol></nav></body></html>"""

        val css = """body{font-family:Georgia,"Times New Roman",serif;font-size:1em;line-height:1.75;color:#1a1a1a;margin:0;padding:0;}
body.cover-page{margin:0;padding:0;}
.cover-wrap{width:100%;height:100%;display:flex;align-items:center;justify-content:center;}
.cover-img{max-width:100%;max-height:100%;display:block;margin:0 auto;}
.chapter{margin:1.5em 1.3em;}
.chapter-title{font-size:1.35em;font-weight:bold;border-bottom:2px solid #333;padding-bottom:.3em;margin-bottom:1em;color:#111;}
p{margin:.55em 0;text-align:justify;text-indent:1.3em;}
p:first-of-type{text-indent:0;}
.pdf-pre{margin:.35em 0;text-align:left;text-indent:0;white-space:pre-wrap;line-height:1.35;}"""

        val coverXhtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="UTF-8"/><title>Capa</title><link rel="stylesheet" href="style.css"/></head>
<body class="cover-page"><div class="cover-wrap"><img src="images/cover.jpg" alt="Capa" class="cover-img"/></div></body></html>"""

        // Salva em cache temporário
        val tmp = File(context.cacheDir, "epub_${System.currentTimeMillis()}.epub")
        ZipOutputStream(FileOutputStream(tmp)).use { zos ->
            fun addEntry(name: String, bytes: ByteArray) { zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry() }
            fun addStr(name: String, str: String) = addEntry(name, str.toByteArray(Charsets.UTF_8))

            val mimeBytes = "application/epub+zip".toByteArray()
            val mt = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                crc = java.util.zip.CRC32().also { it.update(mimeBytes) }.value
            }
            zos.putNextEntry(mt); zos.write(mimeBytes); zos.closeEntry()

            addStr("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""")
            addStr("OEBPS/content.opf", opf); addStr("OEBPS/toc.ncx", ncx)
            addStr("OEBPS/nav.xhtml", nav); addStr("OEBPS/style.css", css)
            if (coverBytes != null) {
                addStr("OEBPS/cover.xhtml", coverXhtml)
                addEntry("OEBPS/images/cover.jpg", coverBytes)
            }
            val total = chapters.size
            chapters.forEachIndexed { idx, ch ->
                val cid = "ch%04d".format(idx + 1); val et2 = esc(ch.title)
                val xhtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="UTF-8"/><title>$et2</title><link rel="stylesheet" href="style.css"/></head>
<body><div class="chapter"><h2 class="chapter-title">$et2</h2>
${textToXhtml(ch.text)}
</div></body></html>"""
                addStr("OEBPS/$cid.xhtml", xhtml)
                withContext(Dispatchers.Main) { onProgress(idx + 1, total) }
            }
        }
        tmp
    }

    fun saveToDestination(context: Context, cacheFile: File, filename: String): Uri {
        // 1. Pasta personalizada via DocumentFile (prioridade máxima, inalterado)
        val treeUriStr = Prefs.get(context, Prefs.SAVE_PATH)
        if (treeUriStr.isNotBlank()) {
            val treeUri = Uri.parse(treeUriStr)
            val docTree = DocumentFile.fromTreeUri(context, treeUri)
            if (docTree != null && docTree.canWrite()) {
                docTree.findFile(filename)?.delete()
                val newDoc = docTree.createFile("application/epub+zip", filename)
                    ?: throw Exception("Não foi possível criar arquivo no diretório selecionado.")
                context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                    cacheFile.inputStream().use { it.copyTo(out) }
                }
                return newDoc.uri
            }
        }

        // 2. Salva via File direto — funciona em todas as versões sem MediaStore insert
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()
        val destFile = File(downloadsDir, filename)
        cacheFile.copyTo(destFile, overwrite = true)

        // 3. Notifica o MediaStore via scan (sem INSERT manual — o scanner faz o upsert internamente)
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(destFile.absolutePath),
            arrayOf("application/epub+zip"),
            null
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                destFile
            )
        } else {
            Uri.fromFile(destFile)
        }
    }

    fun getSavePathLabel(context: Context): String {
        val treeUriStr = Prefs.get(context, Prefs.SAVE_PATH)
        if (treeUriStr.isNotBlank()) {
            val treeUri = Uri.parse(treeUriStr)
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            if (doc != null) return doc.name ?: "Pasta personalizada"
        }
        return "Downloads (padrão)"
    }
}
