package com.linkextract.id

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 结果卡片上的一个字段 */
data class IpField(val label: String, val value: String)

/** 一条 IP 作品 */
data class IpItem(
    val title: String,
    val fields: List<IpField>,
    val rawJson: String
)

sealed interface IpResult {
    /** 查到结果（可能为空列表） */
    data class Ok(val items: List<IpItem>) : IpResult

    /** 登录态失效 */
    data object NeedLogin : IpResult

    /** 接口返回了非 0 的业务错误码 */
    data class ApiError(val code: Int, val msg: String) : IpResult

    /** 网络或解析异常 */
    data class Fail(val msg: String) : IpResult
}

/**
 * 短剧版权中心 IP 库查询。
 *
 * 接口为明文 JSON，仅依赖 WebView 登录产生的 Cookie，无签名参数。
 */
object IpApi {

    const val SITE = "https://www.shortdramas.com"

    private const val LIST_PATH = "/api/playlet/ip/list/v1"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun searchUrl(novelId: String): String =
        "$SITE$LIST_PATH?tab=comic_playlet_ip&page_no=1&search_type=novel_id&search_keyword=$novelId"

    fun loginUrl(): String =
        "$SITE/page/copyright/ip/list?tab=comic_playlet_ip&page_no=1&search_type=novel_id"

    fun search(novelId: String, cookie: String?): IpResult {
        if (cookie.isNullOrBlank()) return IpResult.NeedLogin

        return try {
            val conn = (URL(searchUrl(novelId)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty(
                    "Referer",
                    "$SITE/page/copyright/ip/list?tab=comic_playlet_ip&search_type=novel_id"
                )
                setRequestProperty("Origin", SITE)
                setRequestProperty("Cookie", cookie)
            }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            if (body.isBlank()) {
                IpResult.Fail("服务端返回空内容（HTTP $status）")
            } else {
                parse(body)
            }
        } catch (e: Exception) {
            IpResult.Fail(e.message ?: "网络请求失败")
        }
    }

    private fun parse(body: String): IpResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return IpResult.Fail("响应不是合法 JSON")

        val code = root.optInt("code", 0)
        if (code == -203) return IpResult.NeedLogin
        if (code != 0 && code != 200) {
            return IpResult.ApiError(code, root.optString("msg", "未知错误"))
        }

        val data = root.opt("data") ?: return IpResult.Ok(emptyList())
        val array = findItemArray(data, 0) ?: return IpResult.Ok(emptyList())

        val items = (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { toItem(it) }
        }
        return IpResult.Ok(items)
    }

    /** 在返回结构里递归寻找「元素为对象」的数组 */
    private fun findItemArray(node: Any?, depth: Int): JSONArray? {
        if (depth > 6) return null
        when (node) {
            is JSONArray -> {
                if (node.length() > 0 && node.opt(0) is JSONObject) return node
                for (i in 0 until node.length()) {
                    findItemArray(node.opt(i), depth + 1)?.let { return it }
                }
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    findItemArray(node.opt(keys.next()), depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun toItem(o: JSONObject): IpItem {
        val title = firstString(
            o,
            listOf("book_name", "novel_name", "book_title", "title", "name", "item_name")
        ) ?: "未命名作品"

        val fields = mutableListOf<IpField>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in HIDDEN_KEYS) continue
            val text = renderValue(o.opt(key)) ?: continue
            fields += IpField(labelOf(key), text)
        }

        return IpItem(title = title, fields = fields, rawJson = o.toString(2))
    }

    private fun firstString(o: JSONObject, candidates: List<String>): String? {
        for (k in candidates) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank()) return v
        }
        return null
    }

    private fun renderValue(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        is String -> v.takeIf { it.isNotBlank() }
        is Boolean -> if (v) "是" else "否"
        is Number -> {
            val d = v.toDouble()
            if (d == d.toLong().toDouble()) v.toLong().toString() else v.toString()
        }
        is JSONArray -> {
            val parts = (0 until v.length()).mapNotNull { i ->
                when (val e = v.opt(i)) {
                    is String -> e
                    is Number -> e.toString()
                    is JSONObject -> e.optString("name").ifBlank { null }
                    else -> null
                }
            }
            parts.joinToString("、").ifBlank { null }
        }
        is JSONObject -> v.toString().takeIf { it.length <= 120 }
        else -> v.toString()
    }

    private fun labelOf(key: String): String = LABELS[key] ?: key

    private val HIDDEN_KEYS = setOf(
        "book_name", "novel_name", "book_title", "title", "name", "item_name"
    )

    private val LABELS = mapOf(
        "book_id" to "书籍 ID",
        "novel_id" to "小说 ID",
        "author" to "作者",
        "author_name" to "作者",
        "category" to "分类",
        "category_name" to "分类",
        "tags" to "标签",
        "tag_list" to "标签",
        "score" to "评分",
        "rating" to "评分",
        "word_count" to "总字数",
        "word_num" to "总字数",
        "total_word_count" to "总字数",
        "reader_count" to "在读人数",
        "read_count" to "阅读量",
        "male_ratio" to "男性读者占比",
        "female_ratio" to "女性读者占比",
        "playlet_count" to "已上架漫剧",
        "playlet_num" to "已上架漫剧",
        "status" to "状态",
        "status_desc" to "状态",
        "editor" to "责任编辑",
        "editor_name" to "责任编辑",
        "create_time" to "创建时间",
        "update_time" to "更新时间",
        "publish_status" to "上架状态"
    )
}
