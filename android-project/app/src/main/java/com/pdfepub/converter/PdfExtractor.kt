package com.pdfepub.converter

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ceil
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination

data class PdfPage(val number: Int, val text: String)
data class PdfChapter(val title: String, val text: String)

object PdfExtractor {

    // ── Extração de páginas ──────────────────────────────────────────────────

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
                stripper.endPage   = i
                val text = try { stripper.getText(doc).trim() } catch (_: Exception) { "" }
                pages += PdfPage(i, text)
                withContext(Dispatchers.Main) { onProgress(i, total) }
            }
            doc.close()
        }
        pages
    }

    // ── Agrupamento em capítulos ─────────────────────────────────────────────

    /**
     * Tenta detectar capítulos reais. Usa fallback de pagesPerChapter se não achar.
     * Precisa do URI para ler os outlines do PDF.
     */
    suspend fun groupIntoChapters(
        context: Context,
        uri: Uri,
        pages: List<PdfPage>,
        pagesPerChapter: Int = 10
    ): List<PdfChapter> = withContext(Dispatchers.IO) {

        val cleanPages = removeRepeatingFooters(pages)

        var chapters = tryTocLinks(context, uri, cleanPages)

        if (chapters.size < 2)
            chapters = tryOutlines(context, uri, cleanPages)

        if (chapters.size < 2)
            chapters = tryKeywordRegex(cleanPages)

        if (chapters.size < 2)
            chapters = tryHeuristic(cleanPages)

        if (chapters.size < 2)
            chapters = fallbackChunks(cleanPages, pagesPerChapter)

        chapters
    }

    // ── Estratégia 0: Links clicáveis em página de índice ───────────────────────

    private fun tryTocLinks(
        context: Context,
        uri: Uri,
        pages: List<PdfPage>
    ): List<PdfChapter> {
        if (pages.isEmpty()) return emptyList()

        val searchUpTo = minOf(40, maxOf(5, (pages.size * 0.20).toInt()))
        val allAccumulatedBreaks = mutableListOf<Pair<String, Int>>()

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val doc = PDDocument.load(stream)
                val totalPages = doc.numberOfPages
                var insideToc = false

                for (pageIdx in 0 until minOf(searchUpTo, totalPages)) {
                    val pdPage = try { doc.getPage(pageIdx) } catch (_: Exception) { continue }
                    val annotations = try { pdPage.annotations } catch (_: Exception) { null }

                    if (annotations.isNullOrEmpty()) {
                        if (insideToc) break
                        continue
                    }

                    val stripper = PDFTextStripper()
                    stripper.startPage = pageIdx + 1
                    stripper.endPage   = pageIdx + 1
                    val tocLines = try {
                        stripper.getText(doc).lines()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                    } catch (_: Exception) { emptyList() }

                    val currentPageBreaks = mutableListOf<Pair<String, Int>>()

                    for (ann in annotations) {
                        val link = ann as? PDAnnotationLink ?: continue
                        val dest = try {
                            link.destination ?: (link.action as? PDActionGoTo)?.destination
                        } catch (_: Exception) { null } ?: continue

                        val targetPageNum = try {
                            val pd = dest as? PDPageDestination ?: continue
                            val pg = pd.page
                            if (pg != null) doc.pages.indexOf(pg) + 1 else pd.pageNumber + 1
                        } catch (_: Exception) { null } ?: continue

                        if (targetPageNum in 1..totalPages) {
                            // --- BUSCA DE TÍTULO NO ÍNDICE (PRIORIDADE 1) ---
                            // Procura uma linha que contenha o número da página no final
                            var title = tocLines.find { it.matches(Regex(".*\\b$targetPageNum$")) }
                                ?.replace(Regex("\\s*\\d+$"), "") // Remove o número no fim
                                ?.trimEnd('.', ' ', '·', '•', '-', '–', '—') ?: ""

                            // --- FALLBACK NA PÁGINA DE DESTINO (PRIORIDADE 2) ---
                            if (title.isBlank() || title.equals("Chapter", ignoreCase = true)) {
                                val targetPage = pages.find { it.number == targetPageNum }
                                val targetLines = targetPage?.text?.lines()
                                    ?.map { it.trim() }
                                    ?.filter { it.isNotBlank() } ?: emptyList()

                                if (targetLines.isNotEmpty()) {
                                    val firstLine = targetLines.first()

                                    // Se a primeira linha for só "Chapter", mas a segunda tiver "Prologue" ou "Chapter 1"
                                    if (firstLine.equals("Chapter", ignoreCase = true) && targetLines.size > 1) {
                                        val secondLine = targetLines[1]
                                        // Só pega a segunda linha se ela parecer um título (curta e sem pontuação de frase)
                                        title = if (secondLine.length < 50 && !secondLine.contains(".")) {
                                            secondLine
                                        } else {
                                            // Se a segunda linha já é a história, tenta achar palavras-chave na primeira linha da história
                                            val keywordMatch = Regex("(?i)^(PROLOGUE|PREFACE|INTRODUCTION|EPILOGUE)").find(secondLine)
                                            keywordMatch?.value ?: "Chapter $targetPageNum"
                                        }
                                    } else {
                                        title = firstLine
                                    }
                                }
                            }

                            // Limpeza final: se o título ainda for só "Chapter", põe o número para não ficar repetido
                            if (title.equals("Chapter", ignoreCase = true)) {
                                title = "Chapter $targetPageNum"
                            }

                            currentPageBreaks.add(Pair(title.take(100), targetPageNum))
                        }
                    }

                    if (currentPageBreaks.isNotEmpty()) {
                        insideToc = true
                        allAccumulatedBreaks.addAll(currentPageBreaks)
                    } else if (insideToc) {
                        break
                    }
                }
                doc.close()
            }
        } catch (_: Exception) {}

        return if (allAccumulatedBreaks.isNotEmpty()) {
            allAccumulatedBreaks
                .sortedBy { it.second }
                .distinctBy { it.second }
                .let { buildChaptersFromBreaks(it, pages) }
        } else {
            emptyList()
        }
    }

    // ── Estratégia 1: Outlines (bookmarks) do PDF ───────────────────────────

    private fun tryOutlines(
        context: Context,
        uri: Uri,
        pages: List<PdfPage>
    ): List<PdfChapter> {
        if (pages.isEmpty()) return emptyList()

        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val doc = PDDocument.load(stream)
                val outline = doc.documentCatalog?.documentOutline
                    ?: return@use emptyList<PdfChapter>()

                val stripper = PDFTextStripper()

                // Coleta (título, número da página 1-based) dos itens de 1º nível
                val breaks = mutableListOf<Pair<String, Int>>()
                var item: PDOutlineItem? = outline.firstChild
                while (item != null) {
                    val dest = item.destination
                        ?: item.action?.let {
                            try {
                                (it as? com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo)?.destination
                            } catch (_: Exception) { null }
                        }
                    val pageNum = try {
                        val pageObj = dest?.let {
                            (it as? com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination)
                                ?.page
                        }
                        if (pageObj != null) doc.pages.indexOf(pageObj) + 1 else null
                    } catch (_: Exception) { null }

                    if (pageNum != null && pageNum in 1..pages.size)
                        breaks += Pair(item.title ?: "Capítulo", pageNum)

                    item = item.nextSibling
                }
                doc.close()

                if (breaks.size < 2) return@use emptyList()
                buildChaptersFromBreaks(breaks, pages)
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Estratégia 2: Regex de palavras-chave multilíngue ───────────────────

    // Cobre: pt, en, es, fr, de, it + romanos e ordinais
    private val CHAPTER_KEYWORDS = listOf(
        // Português
        "capítulo", "capitulo", "parte", "livro", "seção", "secção", "prólogo", "epilogo", "epílogo",
        // Inglês
        "chapter", "part", "book", "section", "prologue", "epilogue", "preface", "introduction",
        // Espanhol
        "capítulo", "parte", "libro", "sección", "prólogo", "epílogo",
        // Francês
        "chapitre", "partie", "livre", "section", "prologue", "épilogue", "préface",
        // Alemão
        "kapitel", "teil", "buch", "abschnitt", "prolog", "epilog", "vorwort", "einleitung",
        // Italiano
        "capitolo", "parte", "libro", "sezione", "prologo", "epilogo", "prefazione"
    ).distinct()

    // Números romanos I..LXXX
    private val ROMAN = Regex(
        """^(M{0,4}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3}))$""",
        RegexOption.IGNORE_CASE
    )

    private val KEYWORD_LINE = Regex(
        """^(${CHAPTER_KEYWORDS.joinToString("|") { Regex.escape(it) }})[.\s\-–—:]*(\d{1,4}|[IVXLCDM]{1,8})?[.\s\-–—:]{0,3}$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    private val KEYWORDS_REQUIRE_NUMBER = setOf(
        "book", "part", "parte", "livro", "libro", "livre", "buch",
        "section", "seção", "secção", "sección", "section", "abschnitt"
    )

    private fun tryKeywordRegex(pages: List<PdfPage>): List<PdfChapter> {
        // (título candidato, índice da página)
        val breaks = mutableListOf<Pair<String, Int>>()

        for ((idx, page) in pages.withIndex()) {
            val lines = page.text.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.length > 80) continue
                if (Regex("""(?i)\b(?:https?://|www\.)\S+""").containsMatchIn(trimmed)) continue
                if (Regex("""(?i)\b[a-z0-9-]+(?:\.[a-z]{2,})+\b""").containsMatchIn(trimmed)) continue

                val matchResult = KEYWORD_LINE.find(trimmed) ?: continue
                val hasNumber = matchResult.groupValues[2].isNotBlank()
                val keyword = matchResult.groupValues[1].lowercase().trim()

                if (keyword in KEYWORDS_REQUIRE_NUMBER && !hasNumber) continue

                val wordCount = trimmed.split(Regex("\\s+")).size
                if (wordCount <= 8) {
                    if (breaks.isEmpty() || breaks.last().second != idx)
                        breaks += Pair(trimmed, idx)
                    break
                }
            }
        }

        if (breaks.size < 2) return emptyList()

        // Converte índices (0-based) para números de página (1-based)
        val breaksWithPageNum = breaks.map { (title, idx) -> Pair(title, pages[idx].number) }
        return buildChaptersFromBreaks(breaksWithPageNum, pages)
    }

    // ── Estratégia 3: Heurística de formatação ───────────────────────────────

    private fun tryHeuristic(pages: List<PdfPage>): List<PdfChapter> {
        val breaks = mutableListOf<Pair<String, Int>>()

        for ((idx, page) in pages.withIndex()) {
            val lines = page.text.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue

            val candidate = lines.first()    // Primeira linha não-vazia da página

            val isShort         = candidate.length in 2..60
            val isUppercase     = candidate == candidate.uppercase() && candidate.any { it.isLetter() }
            val startsWithNum   = candidate.matches(Regex("""^\d{1,3}[.\s\-–].*"""))
            val startsWithRoman = ROMAN.matches(candidate.split(Regex("[.\\s\\-–]")).first())
            val looksLikeTitle  = isUppercase || startsWithNum || startsWithRoman

            if (isShort && looksLikeTitle) {
                // Confirma: o restante da página tem conteúdo real (para descartar páginas em branco)
                val bodyLines = lines.drop(1)
                val bodyWords = bodyLines.sumOf { it.split(Regex("\\s+")).size }
                if (bodyWords >= 20) {
                    if (breaks.isEmpty() || breaks.last().second != idx)
                        breaks += Pair(candidate, idx)
                }
            }
        }

        if (breaks.size < 2) return emptyList()

        val breaksWithPageNum = breaks.map { (title, idx) -> Pair(title, pages[idx].number) }
        return buildChaptersFromBreaks(breaksWithPageNum, pages)
    }

    // ── Fallback: N páginas por capítulo ─────────────────────────────────────

    private fun fallbackChunks(pages: List<PdfPage>, pagesPerChapter: Int): List<PdfChapter> {
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

    // ── Utilitário: monta capítulos a partir de lista de quebras ─────────────

    /**
     * breaks: lista de (título, número-de-página-1-based) em ordem crescente.
     * Agrupa as páginas entre quebras consecutivas em cada capítulo.
     */
    private fun buildChaptersFromBreaks(
        breaks: List<Pair<String, Int>>,
        pages: List<PdfPage>
    ): List<PdfChapter> {
        val pageMap = pages.associateBy { it.number }
        val chapters = mutableListOf<PdfChapter>()

        for (i in breaks.indices) {
            val (title, startPage) = breaks[i]
            val endPage = if (i + 1 < breaks.size) breaks[i + 1].second - 1 else pages.last().number

            val body = (startPage..endPage)
                .mapNotNull { pageMap[it] }
                .filter { it.text.isNotBlank() }
                .joinToString("\n\n") { it.text }

            if (body.isNotBlank())
                chapters += PdfChapter(title, body)
        }
        return chapters
    }

    // ── Remoção de rodapés repetidos ─────────────────────────────────────────

    private fun removeRepeatingFooters(pages: List<PdfPage>): List<PdfPage> {
        if (pages.size < 2) return pages

        val minRepeat = maxOf(2, ceil(pages.size * 0.15).toInt())
        val maxFooterWords = 10
        val maxLineLength = 140
        val maxStripPasses = 3

        fun stripIllegalXmlChars(text: String): String {
            val out = StringBuilder(text.length)
            for (ch in text) {
                val code = ch.code
                val isValidXmlChar =
                    ch == '\t' || ch == '\n' || ch == '\r' ||
                            code in 0x20..0xD7FF ||
                            code in 0xE000..0xFFFD

                if (isValidXmlChar) out.append(ch)
            }
            return out.toString()
        }

        fun isPageNumberLine(text: String): Boolean {
            val t = stripIllegalXmlChars(text).trim()
            return t.matches(Regex("""^\d{1,4}$""")) ||
                    t.matches(Regex("""(?i)^(?:page|p\.?|pag(?:e|ina)?|pág(?:ina)?)\s*\d{1,4}$""")) ||
                    t.matches(Regex("""(?i)^[ivxlcdm]{1,8}$"""))
        }

        fun isUrlLine(text: String): Boolean {
            val t = stripIllegalXmlChars(text).trim()
            return Regex("""(?i)\b(?:https?://|www\.)\S+""").containsMatchIn(t) ||
                    Regex("""(?i)\b[a-z0-9-]+(?:\.[a-z0-9-]+)+\b""").containsMatchIn(t)
        }

        fun normalizeForMatch(text: String): String {
            var s = stripIllegalXmlChars(text)

            // Separa letras de números para capturar casos como "Days10"
            s = s.replace(Regex("""(?<=\p{L})(?=\d)|(?<=\d)(?=\p{L})"""), " ")

            s = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT)

            s = s.replace(Regex("""https?://\S+|www\.\S+"""), "#URL#")
            s = s.replace(Regex("""[^\p{L}\p{N}#]+"""), " ")
            s = s.replace(Regex("""\s+"""), " ").trim()

            return s
        }

        fun candidateKey(rawLine: String): String? {
            val clean = stripIllegalXmlChars(rawLine).trim()
            if (clean.isBlank()) return null
            if (clean.length > maxLineLength) return null

            if (isPageNumberLine(clean)) return "#PAGE#"
            if (isUrlLine(clean)) return "#URL#"

            val normalized = normalizeForMatch(clean)
            if (normalized.isBlank()) return null

            val words = normalized.split(" ").filter { it.isNotBlank() }
            if (words.size > maxFooterWords) return null

            return normalized
        }

        fun buildPrefixRegex(candidate: String): Regex {
            return when (candidate) {
                "#PAGE#" -> Regex("""(?i)^\s*(?:\d{1,4}|[ivxlcdm]{1,8})\s*""")
                "#URL#" -> Regex("""(?i)^\s*(?:https?://\S+|www\.\S+)\s*""")
                else -> {
                    val body = candidate
                        .split(" ")
                        .filter { it.isNotBlank() }
                        .joinToString("""\s+""") { Regex.escape(it) }

                    Regex("""(?i)^\s*$body(?:\s*(?:\d{1,4}|[ivxlcdm]{1,8}))?\s*""")
                }
            }
        }

        fun buildSuffixRegex(candidate: String): Regex {
            return when (candidate) {
                "#PAGE#" -> Regex("""(?i)\s*(?:\d{1,4}|[ivxlcdm]{1,8})\s*$""")
                "#URL#" -> Regex("""(?i)\s*(?:https?://\S+|www\.\S+)\s*$""")
                else -> {
                    val body = candidate
                        .split(" ")
                        .filter { it.isNotBlank() }
                        .joinToString("""\s+""") { Regex.escape(it) }

                    Regex("""(?i)\s*$body(?:\s*(?:\d{1,4}|[ivxlcdm]{1,8}))?\s*$""")
                }
            }
        }

        // 1) Descobre fragmentos repetidos nas bordas das páginas
        val candidateCounts = mutableMapOf<String, Int>()

        pages.forEach { page ->
            val lines = page.text
                .lines()
                .map { stripIllegalXmlChars(it).trim() }
                .filter { it.isNotBlank() }

            val edgeLines = (lines.take(3) + lines.takeLast(5)).distinct()
            val seenOnPage = mutableSetOf<String>()

            edgeLines.forEach { line ->
                val key = candidateKey(line) ?: return@forEach
                if (seenOnPage.add(key)) {
                    candidateCounts[key] = (candidateCounts[key] ?: 0) + 1
                }
            }
        }

        val repeatingCandidates = candidateCounts
            .filter { (_, count) -> count >= minRepeat }
            .keys
            .sortedByDescending { it.length }

        val prefixRegexes = repeatingCandidates.map { it to buildPrefixRegex(it) }
        val suffixRegexes = repeatingCandidates.map { it to buildSuffixRegex(it) }

        fun stripRepeatedPrefix(line: String): String {
            var current = stripIllegalXmlChars(line)

            repeat(maxStripPasses) {
                var changed = false

                for ((candidate, regex) in prefixRegexes) {
                    val match = regex.find(current) ?: continue
                    if (match.range.first == 0) {
                        val remaining = current.substring(match.range.last + 1)
                        if (remaining.isBlank() || remaining.length >= 12) {
                            current = remaining
                            changed = true
                            break
                        }
                    }
                }

                if (!changed) return current
            }

            return current
        }

        fun stripRepeatedSuffix(line: String): String {
            var current = stripIllegalXmlChars(line)

            repeat(maxStripPasses) {
                var changed = false

                for ((candidate, regex) in suffixRegexes) {
                    val match = regex.find(current) ?: continue
                    if (match.range.last == current.lastIndex) {
                        val remaining = current.substring(0, match.range.first)
                        if (remaining.isBlank() || remaining.length >= 12) {
                            current = remaining
                            changed = true
                            break
                        }
                    }
                }

                if (!changed) return current
            }

            return current
        }

        fun shouldRemoveWholeLine(line: String): Boolean {
            val t = stripIllegalXmlChars(line).trim()
            if (t.isBlank()) return true
            if (isPageNumberLine(t)) return true
            if (isUrlLine(t)) return true

            val key = candidateKey(t) ?: return false
            return key in repeatingCandidates
        }

        return pages.map { page ->
            val rawLines = page.text.lines()
            val nonBlankIndices = rawLines.indices.filter { rawLines[it].isNotBlank() }

            val cleanedLines = mutableListOf<String>()

            for ((pos, index) in nonBlankIndices.withIndex()) {
                var line = stripIllegalXmlChars(rawLines[index]).trimEnd()
                if (line.isBlank()) continue

                // Remove linha inteira se for rodapé isolado
                if (shouldRemoveWholeLine(line)) continue

                // Limpa prefixo grudado no início da linha nas bordas superiores
                if (pos <= 2) {
                    line = stripRepeatedPrefix(line).trimStart()
                }

                // Limpa sufixo grudado no fim da linha nas bordas inferiores
                if (pos >= nonBlankIndices.size - 3) {
                    line = stripRepeatedSuffix(line).trimEnd()
                }

                line = line.replace(Regex("""\s+"""), " ").trim()
                if (line.isBlank()) continue

                // Se depois da limpeza virou rodapé puro, remove
                if (shouldRemoveWholeLine(line)) continue

                cleanedLines += line
            }

            PdfPage(page.number, cleanedLines.joinToString("\n").trim())
        }
    }

    // ── Parse do nome do arquivo ──────────────────────────────────────────────

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