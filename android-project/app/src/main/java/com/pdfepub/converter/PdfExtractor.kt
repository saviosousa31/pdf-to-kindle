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
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.text.PDFTextStripperByArea
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import android.graphics.RectF

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

        // Detecta páginas de índice para excluí-las das estratégias de texto
        val tocPageNums = detectTocPageNums(cleanPages)

        var chapters = tryTocLinks(context, uri, cleanPages)

        if (chapters.size < 2)
            chapters = tryOutlines(context, uri, cleanPages)

        if (chapters.size < 2)
            chapters = tryKeywordRegex(cleanPages, tocPageNums)

        if (chapters.size < 2)
            chapters = tryHeuristic(cleanPages, tocPageNums)

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
        val allBreaks = mutableListOf<Pair<String, Int>>()

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val doc = PDDocument.load(stream)
                val totalPages = doc.numberOfPages

                // Mapa COSObject → número de página (resolve o problema de comparação por identidade)
                val cosPageMap = HashMap<COSBase, Int>()
                for (i in 0 until totalPages) {
                    cosPageMap[doc.getPage(i).cosObject] = i + 1
                }

                var insideToc = false

                for (pageIdx in 0 until minOf(searchUpTo, totalPages)) {
                    val pdPage = try { doc.getPage(pageIdx) } catch (_: Exception) { continue }
                    val annotations = try { pdPage.annotations } catch (_: Exception) { null }

                    if (annotations.isNullOrEmpty()) {
                        if (insideToc) break
                        continue
                    }

                    // Coleta links que apontam para frente, com posição Y (para ordenação)
                    val forwardLinks = mutableListOf<Pair<PDRectangle, Int>>() // (rect, destPage 1-based)

                    for (ann in annotations) {
                        val link = ann as? PDAnnotationLink ?: continue
                        val dest = try {
                            link.destination ?: (link.action as? PDActionGoTo)?.destination
                        } catch (_: Exception) { null } ?: continue

                        val targetPageNum: Int? = try {
                            val pd = dest as? PDPageDestination ?: continue
                            val pg = pd.page
                            if (pg != null) {
                                val idx = doc.pages.indexOf(pg)
                                if (idx >= 0) {
                                    idx + 1
                                } else {
                                    // Fallback via COSObject (corrige falha com referências diretas de página)
                                    cosPageMap[pg.cosObject]
                                        ?: let { val pn = pd.pageNumber; if (pn >= 0) pn + 1 else null }
                                }
                            } else {
                                val pn = pd.pageNumber
                                if (pn >= 0) pn + 1 else null
                            }
                        } catch (_: Exception) { null }

                        if (targetPageNum != null && targetPageNum in 1..totalPages) {
                            val rect = try { link.rectangle } catch (_: Exception) { null } ?: continue
                            forwardLinks.add(Pair(rect, targetPageNum))
                        }
                    }

                    if (forwardLinks.isEmpty()) {
                        if (insideToc) break
                        continue
                    }

                    // Ordena de cima para baixo (Y maior = mais alto na página em coordenadas PDF)
                    val sortedLinks = forwardLinks.sortedByDescending { it.first.lowerLeftY }

                    // Extrai o texto de cada link pela sua área exata na página
                    val pageHeight = try { pdPage.mediaBox.height } catch (_: Exception) { 842f }
                    val areaStripper = PDFTextStripperByArea()

                    sortedLinks.forEachIndexed { i, (rect, _) ->
                        // Calcula o Y invertido (pois o PDF começa de baixo pra cima, e a tela de cima pra baixo)
                        val java2dY = pageHeight - rect.upperRightY

                        // Mapeia os valores para o formato do Android
                        val leftExpand = 6f
                        val left   = (rect.lowerLeftX - leftExpand).coerceAtLeast(0f)
                        val top    = java2dY
                        val right  = left + rect.width + leftExpand
                        val bottom = top + rect.height

                        areaStripper.addRegion(
                            "link_$i",
                            RectF(left, top, right, bottom)
                        )
                    }
                    try { areaStripper.extractRegions(pdPage) } catch (_: Exception) { }

                    val currentBreaks = mutableListOf<Pair<String, Int>>()
                    sortedLinks.forEachIndexed { i, (linkRect, targetPageNum) ->
                        val rawText = try {
                            areaStripper.getTextForRegion("link_$i") ?: ""
                        } catch (_: Exception) { "" }
                        val rawLine = rawText.trim()
                            .replace(Regex("""\r?\n"""), " ")
                            .replace(Regex("""\t+"""), " ")
                            .replace(Regex("""\s+"""), " ")

                        // Se a área retornou texto válido, usa direto
                        // Caso contrário, tenta encontrar a linha correspondente no texto da página pelo Y
                        val areaTitle = cleanTocEntryTitle(rawLine)

                        val title = if (areaTitle.isNotBlank()) {
                            areaTitle
                        } else {
                            findClosestLineByY(
                                pageText = pages.getOrNull(pageIdx)?.text ?: "",
                                linkCenterY = linkRect.lowerLeftY + linkRect.height / 2f,
                                pageHeight = pageHeight
                            )
                        }

                        if (title.isNotBlank() && title.length <= 150) {
                            currentBreaks.add(Pair(title, targetPageNum))
                        }
                    }

                    if (currentBreaks.isNotEmpty()) {
                        insideToc = true
                        allBreaks.addAll(currentBreaks)
                    } else if (insideToc) {
                        break
                    }
                }
                doc.close()
            }
        } catch (_: Exception) {}

        if (allBreaks.size < 2) return emptyList()

        return allBreaks
            .sortedBy { it.second }
            .let { buildChaptersFromBreaks(it, pages) }
    }

    /**
     * Encontra a linha de texto da página mais próxima de um Y de referência (coordenada PDF,
     * ou seja, Y cresce de baixo para cima). Usa distância relativa ao centro vertical da página
     * para escolher a linha mais provável correspondente ao link.
     *
     * Como o PDFBox extrai o texto de cima para baixo, a linha no índice 0 é a do topo da página.
     * Convertemos a posição Y do link para uma fração relativa (0.0 = topo, 1.0 = base) e
     * escolhemos a linha no índice correspondente à mesma fração.
     *
     * @param pageText    Texto completo da página (extraído normalmente via PDFTextStripper)
     * @param linkCenterY Centro Y do link em coordenadas PDF (Y = 0 na base)
     * @param pageHeight  Altura total da página em pontos PDF
     */
    private fun findClosestLineByY(
        pageText: String,
        linkCenterY: Float,
        pageHeight: Float
    ): String {
        val lines = pageText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        // Converte Y do PDF (base=0) para fração relativa ao topo (0.0=topo, 1.0=base)
        val yFraction = 1f - (linkCenterY / pageHeight).coerceIn(0f, 1f)

        // Estima o índice da linha com base na fração
        val estimatedIndex = (yFraction * lines.size).toInt().coerceIn(0, lines.lastIndex)

        // Busca na vizinhança (±3 linhas) a linha que melhor parece um título de TOC
        val searchRange = (estimatedIndex - 3).coerceAtLeast(0)..(estimatedIndex + 3).coerceAtMost(lines.lastIndex)

        // Prefere a linha mais curta na vizinhança (títulos de TOC são curtos)
        val candidate = searchRange
            .map { lines[it] }
            .filter { it.length in 2..150 }
            .minByOrNull { it.length }
            ?: lines[estimatedIndex]

        return cleanTocEntryTitle(candidate)
    }

    /**
     * Mescla entradas do TOC que apontam para a mesma página.
     * Regra: mantém o PRIMEIRO título (nível mais alto, ex: "PART 1"),
     * descartando os demais que redirecionam para a mesma página.
     *
     * Isso evita que entradas como "PART 1" (range vazio → body vazio)
     * sejam descartadas em buildChaptersFromBreaks, perdendo o título correto.
     */
    private fun mergeBreaksBySamePage(
        breaks: List<Pair<String, Int>>
    ): List<Pair<String, Int>> {
        if (breaks.isEmpty()) return breaks
        val result = mutableListOf<Pair<String, Int>>()
        var current = breaks[0]
        for (i in 1 until breaks.size) {
            val next = breaks[i]
            if (next.second == current.second) {
                // Mesma página: mantém o primeiro (nível mais alto)
                // Se quiser combinar os títulos, use:
                // current = Pair("${current.first} – ${next.first}", current.second)
                // Por padrão, apenas descartamos o segundo:
                continue
            }
            result.add(current)
            current = next
        }
        result.add(current)
        return result
    }

    /**
     * Detecta páginas de índice/sumário nos primeiros 10% do livro.
     * Critério: página onde ≥50% das linhas e pelo menos 5 linhas são títulos de capítulo puros.
     */
    private fun detectTocPageNums(pages: List<PdfPage>): Set<Int> {
        val searchUpTo = minOf(15, maxOf(3, (pages.size * 0.10).toInt()))
        val result = mutableSetOf<Int>()

        val pureChapterLine = Regex(
            """(?i)^(chapter|ch\.?|part|section|prologue|epilogue|preface|introduction|appendix|""" +
                    """capítulo|chapitre|kapitel|capitolo|prologo|epilogo|préface|vorwort|einleitung|prefazione)""" +
                    """\s*[\dIVXLCDM]{0,6}\s*$"""
        )

        for (i in 0 until minOf(searchUpTo, pages.size)) {
            val lines = pages[i].text.lines()
                .map { it.trim().replace(Regex("""\t+"""), " ").replace(Regex("""\s+"""), " ") }
                .filter { it.isNotBlank() }

            if (lines.size < 5) continue

            val chapterLineCount = lines.count { pureChapterLine.matches(it) }

            if (chapterLineCount >= 5 && chapterLineCount.toFloat() / lines.size >= 0.50f) {
                result.add(pages[i].number)
            }
        }
        return result
    }

    /**
     * Remove o número de página e os pontos-guia do final de uma linha de índice,
     * retornando apenas o título limpo.
     */
    private fun cleanTocEntryTitle(line: String): String {
        return line
            .replace(Regex("""[·.•\-]{2,}\s*\d{1,4}\s*$"""), "") // "Título ....... 42" → "Título"
            .replace(Regex("""\s{2,}\d{1,4}\s*$"""), "")          // "Título    42" → "Título"
            .trim()
    }

    private fun resolveOutlinePageNum(
        item: PDOutlineItem,
        doc: PDDocument
    ): Int? {
        val dest = item.destination
            ?: item.action?.let {
                try {
                    (it as? PDActionGoTo)?.destination
                } catch (_: Exception) { null }
            }
        return try {
            val pageObj = (dest as? PDPageDestination)?.page
            if (pageObj != null) doc.pages.indexOf(pageObj) + 1 else null
        } catch (_: Exception) { null }
    }

    private fun collectOutlineItems(
        first: PDOutlineItem?,
        doc: PDDocument,
        totalPages: Int
    ): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        var item = first
        while (item != null) {
            val pageNum = resolveOutlinePageNum(item, doc)
            if (pageNum != null && pageNum in 1..totalPages) {
                result += Pair(item.title ?: "Chapter", pageNum)
            }
            if (item.hasChildren()) {
                result.addAll(collectOutlineItems(item.firstChild, doc, totalPages))
            }
            item = item.nextSibling
        }
        return result
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
                val breaks = collectOutlineItems(outline.firstChild, doc, pages.size)
                    .sortedBy { it.second }
                    .distinctBy { it.second } // mantém o primeiro (PART sobre Chapter na mesma página)
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

    private fun tryKeywordRegex(pages: List<PdfPage>, tocPageNums: Set<Int> = emptySet()): List<PdfChapter> {
        val breaks = mutableListOf<Pair<String, Int>>()
        for ((idx, page) in pages.withIndex()) {
            if (page.number in tocPageNums) continue   // ← linha adicionada
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

    private fun tryHeuristic(pages: List<PdfPage>, tocPageNums: Set<Int> = emptySet()): List<PdfChapter> {
        val breaks = mutableListOf<Pair<String, Int>>()
        for ((idx, page) in pages.withIndex()) {
            if (page.number in tocPageNums) continue   // ← linha adicionada
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
     * Constrói capítulos a partir de uma lista de quebras (título, página 1-based).
     *
     * Regras:
     * - Múltiplas entradas na mesma página → todas recebem o mesmo body (conteúdo compartilhado).
     * - Se o body do grupo for vazio (página sem texto detectável), todas as entradas são descartadas.
     * - Isso preserva entradas como "PART 1" e "Chapter 1" apontando para a mesma página,
     *   enquanto ainda descarta páginas realmente sem texto.
     */
    private fun buildChaptersFromBreaks(
        breaks: List<Pair<String, Int>>,
        pages: List<PdfPage>
    ): List<PdfChapter> {
        if (breaks.isEmpty()) return emptyList()
        val pageMap = pages.associateBy { it.number }
        val chapters = mutableListOf<PdfChapter>()

        // Agrupa índices de breaks por página-alvo
        // Ex: [(PART 1, 5), (Chapter 1, 5), (Chapter 2, 8)] →
        //     grupo página 5: [0,1], grupo página 8: [2]
        var i = 0
        while (i < breaks.size) {
            val (_, startPage) = breaks[i]

            // Coleta todos os breaks que apontam para a mesma página
            val groupIndices = mutableListOf(i)
            var j = i + 1
            while (j < breaks.size && breaks[j].second == startPage) {
                groupIndices.add(j)
                j++
            }

            // O endPage é determinado pelo próximo break DIFERENTE de startPage
            val endPage = if (j < breaks.size) breaks[j].second - 1 else pages.last().number

            // Monta o body compartilhado por todas as entradas do grupo
            val body = (startPage..endPage)
                .mapNotNull { pageMap[it] }
                .filter { it.text.isNotBlank() }
                .joinToString("\n\n") { it.text }

            // Só cria capítulos se houver conteúdo real
            if (body.isNotBlank()) {
                for (idx in groupIndices) {
                    val (title, _) = breaks[idx]
                    chapters += PdfChapter(title, body)
                }
            }

            i = j
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