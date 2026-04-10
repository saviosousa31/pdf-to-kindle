package com.pdfepub.converter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object CoverSearcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val BROWSER_HEADERS = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    private fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): String? {
        return try {
            val req = Request.Builder().url(url).apply {
                BROWSER_HEADERS.forEach { (k, v) -> addHeader(k, v) }
                extraHeaders.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) resp.body?.string() else null
        } catch (e: Exception) {
            null
        }
    }

    // ── Amazon ──────────────────────────────────────────────────────────────

    private fun searchAmazon(title: String, author: String): List<String> {
        val urls = mutableListOf<String>()
        val q = java.net.URLEncoder.encode("$title $author", "UTF-8")

        for (base in listOf("https://www.amazon.com.br", "https://www.amazon.com")) {
            val html = get("$base/s?k=$q&i=stripbooks") ?: continue

            // Extract ASINs
            val asinPat = Pattern.compile("data-asin=\"([A-Z0-9]{10})\"")
            val m = asinPat.matcher(html)
            val asins = linkedSetOf<String>()
            while (m.find()) asins.add(m.group(1)!!)

            for (asin in asins.take(10)) {
                // CDN direct — most reliable
                urls += "https://m.media-amazon.com/images/P/$asin.01._SCLZZZZZZZ_SX600_.jpg"
                urls += "https://images-na.ssl-images-amazon.com/images/P/$asin.01.LZZZZZZZ.jpg"
            }

            // Also extract any media-amazon image IDs from the search page
            val imgPat = Pattern.compile("m\\.media-amazon\\.com/images/I/([A-Za-z0-9]{8,})")
            val im = imgPat.matcher(html)
            val seen = linkedSetOf<String>()
            while (im.find()) seen.add(im.group(1)!!)
            for (id in seen.take(10)) {
                urls += "https://m.media-amazon.com/images/I/$id._SL1500_.jpg"
            }

            if (urls.isNotEmpty()) break
        }
        return urls
    }

    // ── Google Books ─────────────────────────────────────────────────────────

    private fun searchGoogleBooks(title: String, author: String): List<String> {
        val urls = mutableListOf<String>()
        val q = java.net.URLEncoder.encode("$title $author", "UTF-8")
        val json = get("https://www.googleapis.com/books/v1/volumes?q=$q&maxResults=10&printType=books") ?: return urls

        val imgPat = Pattern.compile("\"(https://books\\.google[^\"]+)\"")
        val m = imgPat.matcher(json)
        val seen = linkedSetOf<String>()
        while (m.find()) seen.add(m.group(1)!!)

        for (url in seen) {
            var hires = url
                .replace("http://", "https://")
                .replace(Regex("&zoom=\\d+"), "")
                .replace(Regex("&edge=curl"), "")
            // Try to get larger image
            hires = hires.replace("zoom=1", "zoom=0")
                .replace("&imgtk=", "&fife=w600-h900&imgtk=")
            urls += hires
        }
        return urls
    }

    // ── Open Library ─────────────────────────────────────────────────────────

    private fun searchOpenLibrary(title: String, author: String): List<String> {
        val urls = mutableListOf<String>()
        val q = java.net.URLEncoder.encode("$title $author", "UTF-8")
        val json = get("https://openlibrary.org/search.json?q=$q&limit=10&fields=cover_i") ?: return urls

        val pat = Pattern.compile("\"cover_i\":\\s*(\\d+)")
        val m = pat.matcher(json)
        while (m.find()) {
            val id = m.group(1)!!
            urls += "https://covers.openlibrary.org/b/id/$id-L.jpg"
            urls += "https://covers.openlibrary.org/b/id/$id-M.jpg"
        }
        return urls
    }

    // ── Bing Images ──────────────────────────────────────────────────────────

    private fun searchBing(title: String, author: String): List<String> {
        val urls = mutableListOf<String>()
        val q = java.net.URLEncoder.encode("$title $author book cover", "UTF-8")
        val html = get("https://www.bing.com/images/search?q=$q&form=HDRSC2&first=1",
            mapOf("Referer" to "https://www.bing.com/")) ?: return urls

        val pat = Pattern.compile("\"murl\":\"(https?://[^\"]+\\.(?:jpg|jpeg|png))\"")
        val m = pat.matcher(html)
        while (m.find() && urls.size < 10) urls += m.group(1)!!
        return urls
    }

    // ── DuckDuckGo ───────────────────────────────────────────────────────────

    private fun searchDuckDuckGo(title: String, author: String): List<String> {
        val urls = mutableListOf<String>()
        try {
            // Get vqd token
            val home = get("https://duckduckgo.com/") ?: return urls
            val vqdPat = Pattern.compile("vqd=([\\d-]+)")
            val vm = vqdPat.matcher(home)
            val vqd = if (vm.find()) vm.group(1)!! else return urls

            val q = java.net.URLEncoder.encode("$title $author book cover", "UTF-8")
            val json = get("https://duckduckgo.com/i.js?l=pt-br&o=json&q=$q&vqd=$vqd&f=,,,,,&p=1") ?: return urls

            val pat = Pattern.compile("\"image\":\"(https?://[^\"]+)\"")
            val m = pat.matcher(json)
            while (m.find() && urls.size < 10) urls += m.group(1)!!
        } catch (_: Exception) {}
        return urls
    }

    // ── Internet Archive ─────────────────────────────────────────────────────

    private fun searchArchive(title: String, author: String): List<String> {
        val urls = mutableListOf<String>()
        val q = java.net.URLEncoder.encode("$title $author", "UTF-8")
        val json = get("https://archive.org/advancedsearch.php?q=$q&mediatype=texts&output=json&rows=8&fl=identifier") ?: return urls

        val pat = Pattern.compile("\"identifier\":\"([^\"]+)\"")
        val m = pat.matcher(json)
        while (m.find() && urls.size < 8) {
            urls += "https://archive.org/services/img/${m.group(1)!!}"
        }
        return urls
    }

    // ── Goodreads ────────────────────────────────────────────────────────────

    private fun searchGoodreads(title: String, author: String): List<String> {
        val urls = mutableListOf<String>()
        val q = java.net.URLEncoder.encode("$title $author", "UTF-8")
        val html = get("https://www.goodreads.com/search?q=$q&search_type=books") ?: return urls

        val doc = Jsoup.parse(html)
        for (img in doc.select("img[src*=amazon], img[src*=gr-assets]")) {
            val src = img.attr("src")
            if ("nophoto" !in src && src.startsWith("http")) {
                urls += src
                if (urls.size >= 8) break
            }
        }
        return urls
    }

    // ── Main search orchestrator ─────────────────────────────────────────────

    /**
     * Returns a large pool of cover URL candidates from all sources.
     * Call on IO dispatcher. Returns de-duplicated list of ~60 URLs.
     */
    suspend fun searchAll(title: String, author: String): List<String> = withContext(Dispatchers.IO) {
        val all = mutableListOf<String>()

        // Amazon first (most reliable for book covers)
        all += searchAmazon(title, author)

        // Google Books
        all += searchGoogleBooks(title, author)

        // Open Library
        all += searchOpenLibrary(title, author)

        // Bing
        all += searchBing(title, author)

        // DuckDuckGo
        all += searchDuckDuckGo(title, author)

        // Goodreads
        all += searchGoodreads(title, author)

        // Internet Archive (last — often low quality)
        all += searchArchive(title, author)

        // De-duplicate preserving order
        val seen = linkedSetOf<String>()
        all.filter { it.startsWith("http") && seen.add(it) }
    }

    /**
     * Verifies that a URL actually returns a valid image by doing a HEAD request.
     */
    suspend fun verifyUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).head().build()
            val resp = client.newCall(req).execute()
            val ct = resp.header("Content-Type", "") ?: ""
            resp.isSuccessful && "image" in ct
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Downloads image bytes from a URL.
     */
    suspend fun downloadBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).apply {
                BROWSER_HEADERS.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) resp.body?.bytes() else null
        } catch (_: Exception) {
            null
        }
    }
}
