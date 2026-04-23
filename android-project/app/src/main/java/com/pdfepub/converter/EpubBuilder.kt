package com.pdfepub.converter

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

object EpubBuilder {

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun textToXhtml(text: String): String {
        // \n simples = quebra de linha visual do PDF → vira espaço (une as palavras)
        // \n\n ou mais = quebra de parágrafo real → vira <p>
        val normalized = text.trim()
            .replace(Regex("(?<!\n)\n(?!\n)"), " ")  // single \n → espaço
            .replace(Regex(" {2,}"), " ")             // remove espaços duplos
        val paras = normalized.split(Regex("\n{2,}"))
        return paras.filter { it.isNotBlank() }.joinToString("\n") {
            "<p>${esc(it.trim())}</p>"
        }.ifBlank { "<p><em>(Página sem texto detectável)</em></p>" }
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
p{margin:.55em 0;text-align:justify;text-indent:1.3em;}p:first-of-type{text-indent:0;}"""

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

    /** Salva o arquivo de cache para o destino permanente escolhido pelo usuário. */
    fun saveToDestination(context: Context, cacheFile: File, filename: String): Uri {
        val treeUriStr = Prefs.get(context, Prefs.SAVE_PATH)

        // Destino customizado via ACTION_OPEN_DOCUMENT_TREE
        if (treeUriStr.isNotBlank()) {
            val treeUri = Uri.parse(treeUriStr)
            val docTree = DocumentFile.fromTreeUri(context, treeUri)
            if (docTree != null && docTree.canWrite()) {
                // Remove arquivo anterior com mesmo nome se existir
                docTree.findFile(filename)?.delete()
                val newDoc = docTree.createFile("application/epub+zip", filename)
                    ?: throw Exception("Não foi possível criar arquivo no diretório selecionado.")
                context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                    cacheFile.inputStream().use { it.copyTo(out) }
                }
                return newDoc.uri
            }
        }

        // Fallback: Downloads padrão
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/epub+zip")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw Exception("Falha ao criar arquivo em Downloads")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                cacheFile.inputStream().use { it.copyTo(out) }
            }
            cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, cv, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dest = File(dir, filename)
            cacheFile.copyTo(dest, overwrite = true)
            Uri.fromFile(dest)
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
