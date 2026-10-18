package com.linkextract.id

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * 从分享链接中提取书籍 ID。
 *
 * 支持三类输入：
 * 1. 完整长链接 —— URL 查询参数中直接含 book_id，本地正则即可命中；
 * 2. 经 URL 编码的长链接 —— 先解码再匹配；
 * 3. 短链 —— 需联网跟随 HTTP 重定向，再从最终地址或页面内容中提取。
 */
object IdParser {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val MAX_REDIRECTS = 8
    private const val MAX_BODY_BYTES = 512 * 1024

    /** 纯数字 ID（用户可能直接粘贴 ID 本身） */
    private val PURE_ID = Regex("^\\d{12,25}\$")

    /** 查询参数形式：book_id=xxx 或 bookid=xxx。前置边界避免误撞 notebook_id 之类的字段 */
    private val PARAM_ID = Regex("""(?:^|[?&\s])book_?id=(\d{6,25})""", RegexOption.IGNORE_CASE)

    /** JSON 形式："book_id":"xxx" */
    private val JSON_ID = Regex("\"book_?id\"\\s*:\\s*\"?(\\d{6,25})", RegexOption.IGNORE_CASE)

    /** 文本中的第一个链接 */
    private val URL_RE = Regex("""https?://[^\s"'<>，。、；！？）】]+""")

    fun firstUrl(text: String): String? =
        URL_RE.find(text)?.value?.trimEnd('.', ',', ';', ')', ']', '}')

    /**
     * 纯本地解析，不发起网络请求。命中返回 ID，否则返回 null。
     */
    fun extractDirect(text: String): String? {
        val raw = text.trim()
        if (raw.isEmpty()) return null

        PURE_ID.matchEntire(raw)?.let { return it.value }
        matchIn(raw)?.let { return it }

        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
        if (decoded != null && decoded != raw) {
            matchIn(decoded)?.let { return it }
        }
        return null
    }

    private fun matchIn(text: String): String? =
        PARAM_ID.find(text)?.groupValues?.get(1) ?: JSON_ID.find(text)?.groupValues?.get(1)

    /**
     * 联网解析短链。
     * @return (ID, 来源说明)；失败返回 null。
     */
    suspend fun resolve(url: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val finalUrl = followRedirects(url)
            extractDirect(finalUrl)?.let { return@runCatching (it to "跳转后链接") }

            val body = fetchBody(finalUrl)
            matchIn(body)?.let { return@runCatching (it to "页面内容") }
            null
        }.getOrNull()
    }

    private fun followRedirects(start: String): String {
        var current = start
        repeat(MAX_REDIRECTS) {
            val conn = newConnection(current)
            try {
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = URL(URL(current), location).toString()
                } else {
                    return current
                }
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    private fun fetchBody(url: String): String {
        val conn = newConnection(url)
        return try {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0
            conn.inputStream.use { input ->
                while (total < MAX_BODY_BYTES) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    out.write(chunk, 0, n)
                    total += n
                }
            }
            out.toByteArray().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        } finally {
            conn.disconnect()
        }
    }

    private fun newConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        }
}
