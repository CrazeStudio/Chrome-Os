package com.craze.ggufrunner

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class WebResult(val title: String, val url: String, val snippet: String, val pageText: String = "")

object WebSearch {
    fun search(query: String, limit: Int = 5): List<WebResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val conn = (URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        return try {
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val blocks = Regex("""(?is)<div[^>]*class=["'][^"']*result__body[^"']*["'][^>]*>(.*?)</div>\s*</div>""")
                .findAll(html).map { it.groupValues[1] }.toList()
            val parsed = blocks.mapNotNull { block ->
                val a = Regex("""(?is)<a(?=[^>]*class=["'][^"']*result__a)[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""").find(block)
                    ?: Regex("""(?is)<a[^>]*href=["']([^"']+)["'][^>]*class=["'][^"']*result__a[^"']*["'][^>]*>(.*?)</a>""").find(block)
                    ?: return@mapNotNull null
                val href = decodeRedirect(a.groupValues[1])
                if (!href.startsWith("http")) return@mapNotNull null
                val title = clean(a.groupValues[2])
                val snippet = Regex("""(?is)<(?:a|div)[^>]*class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div)>""")
                    .find(block)?.groupValues?.get(1)?.let(::clean).orEmpty()
                if (title.isBlank()) null else WebResult(title, href, snippet)
            }.distinctBy { it.url }.take(limit)
            val base = if (parsed.isNotEmpty()) parsed else parseFallback(html, limit)
            // Fetch only the first three result pages to limit latency and data usage.
            base.take(3).map { it.copy(pageText = readPage(it.url)) } + base.drop(3)
        } finally {
            conn.disconnect()
        }
    }

    private fun readPage(url: String): String = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 7000
            readTimeout = 9000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching ""
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(1_500_000) }
            clean(html.replace(Regex("""(?is)<script.*?</script>|<style.*?</style>|<nav.*?</nav>|<footer.*?</footer>|<header.*?</header>"""), " "))
                .take(4500)
        } finally {
            connection.disconnect()
        }
    }.getOrDefault("")

    private fun parseFallback(html: String, limit: Int): List<WebResult> {
        val anchors = Regex("""(?is)<a(?=[^>]*class=["'][^"']*result__a)[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""")
            .findAll(html).toList()
        return anchors.mapNotNull {
            val url = decodeRedirect(it.groupValues[1])
            val title = clean(it.groupValues[2])
            if (!url.startsWith("http") || title.isBlank()) null else WebResult(title, url, "")
        }.distinctBy { it.url }.take(limit)
    }

    private fun decodeRedirect(raw: String): String = runCatching {
        if (raw.startsWith("//")) {
            val uri = java.net.URI("https:$raw")
            val q = uri.rawQuery.orEmpty().split("&").firstOrNull { it.startsWith("uddg=") }?.substringAfter("uddg=")
            if (q != null) java.net.URLDecoder.decode(q, "UTF-8") else "https:$raw"
        } else raw
    }.getOrDefault(raw)

    private fun clean(raw: String): String = raw
        .replace(Regex("""(?is)<script.*?</script>|<style.*?</style>"""), " ")
        .replace(Regex("""(?s)<[^>]+>"""), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("""\s+"""), " ").trim()
}
