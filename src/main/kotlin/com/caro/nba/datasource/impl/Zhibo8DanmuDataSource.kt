package com.caro.nba.datasource.impl

import com.caro.nba.datasource.DataSourceCommon
import com.caro.nba.model.PlayByPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Request

/**
 * 直播吧比赛弹幕数据源（只读聚合，不提供发送）
 *
 * 接口（2026-11-11 实测，逆自比赛页 ndanmu.js）：
 * - 计数：https://dan.zhibo8.cc/data/{year}/nba/match{gameId}v_count.htm
 *   → {"num":"6366","per_page":5,...}
 * - 数据：https://dan.zhibo8.cc/data/{year}/nba/match{gameId}v_{页码}.htm
 *   → [{order_id, content, createtime, room, m_uid}...]，order_id 全局递增
 * - 页码 = Math.floor((order_id - 1) / per_page)，per_page 固定 5
 *
 * filename 规则：{比赛年}-nba-match{gameId}v（比赛年 = 美东比赛日期的年份）。
 *
 * 合规边界：仅只读聚合直播吧公开弹幕用于展示并标注来源，不提供发送、不存储用户内容。
 */
class Zhibo8DanmuDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson

    companion object {
        /** 每页弹幕条数（接口固定 per_page=5） */
        private const val PER_PAGE = 5

        private fun filename(gameId: String, year: Int) = "$year-nba-match${gameId}v"

        private fun dataUrl(gameId: String, year: Int, page: Int) =
            "https://dan.zhibo8.cc/data/${filename(gameId, year).replace("-", "/")}_${page}.htm?rand=${Math.random()}"

        private fun countUrl(gameId: String, year: Int) =
            "https://dan.zhibo8.cc/data/${filename(gameId, year).replace("-", "/")}_count.htm?rand=${Math.random()}"

        /** PHP 偶发前置 Notice 噪音：截取第一个 '[' 起的 JSON 数组 */
        private fun extractArray(body: String): String =
            body.substringAfterLast("<br />").let { cleaned ->
                val start = cleaned.indexOf('[')
                if (start >= 0) cleaned.substring(start) else cleaned
            }

        private fun parseArray(body: String): List<PlayByPlay.Danmu> {
            val jsonPart = extractArray(body)
            val arr = com.google.gson.JsonParser.parseString(jsonPart).takeIf { it.isJsonArray }?.asJsonArray
                ?: throw Exception("弹幕数据格式异常")
            return arr.mapNotNull { e ->
                val o = e?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val orderId = o.get("order_id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@mapNotNull null
                val content = o.get("content")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                PlayByPlay.Danmu(
                    orderId = orderId,
                    content = content,
                    time = o.get("createtime")?.takeIf { it.isJsonPrimitive }?.asString?.take(16) ?: ""
                )
            }
        }
    }

    /** 通用 GET（返回原始 body） */
    private fun fetchBody(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://www.zhibo8.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("弹幕接口失败: ${response.code}")
            return response.body?.string() ?: throw Exception("弹幕响应为空")
        }
    }

    /** 弹幕总条数（用于计算总页数/最新页；接口 404 通常=无弹幕，返回 0） */
    fun count(gameId: String, year: Int): Result<Int> = runCatching {
        val body = fetchBody(countUrl(gameId, year))
        val cleaned = body.substringAfterLast("<br />")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val obj = com.google.gson.JsonParser.parseString(cleaned.substring(start, end + 1)).asJsonObject
        obj.get("num")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0
    }

    /** 按页码拉弹幕（page 从 0 开始，每页 PER_PAGE 条，页内 order_id 升序） */
    fun page(gameId: String, year: Int, page: Int): Result<List<PlayByPlay.Danmu>> = runCatching {
        parseArray(fetchBody(dataUrl(gameId, year, page)))
    }

    /** 最新一页弹幕（轮询增量用） */
    fun latest(gameId: String, year: Int): Result<List<PlayByPlay.Danmu>> = runCatching {
        val num = count(gameId, year).getOrDefault(0)
        if (num <= 0) return@runCatching emptyList()
        val lastPage = Math.floor((num - 1).toDouble() / PER_PAGE).toInt()
        parseArray(fetchBody(dataUrl(gameId, year, lastPage)))
    }

    /**
     * 最近 count 条弹幕（首次打开回填用）：从最后一页往前并行翻页合并。
     * kotlinx 协程并发拉页（IO），合并去重后按 order_id 升序返回（≤count 条）。
     */
    fun recent(gameId: String, year: Int, count: Int = 60): Result<List<PlayByPlay.Danmu>> = runCatching {
        val num = count0(gameId, year)
        if (num <= 0) return@runCatching emptyList()
        val lastPage = (num - 1) / PER_PAGE
        val pagesToFetch = Math.ceil(count.toDouble() / PER_PAGE).toInt()
        val all = runBlocking {
            (lastPage downTo maxOf(0, lastPage - pagesToFetch + 1)).map { p ->
                async(Dispatchers.IO) { page(gameId, year, p).getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        }
        all.distinctBy { it.orderId }.sortedBy { it.orderId }.takeLast(count)
    }

    /** 内部计数（不走 Result 包装） */
    private fun count0(gameId: String, year: Int): Int = runCatching {
        val body = fetchBody(countUrl(gameId, year))
        val cleaned = body.substringAfterLast("<br />")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val obj = com.google.gson.JsonParser.parseString(cleaned.substring(start, end + 1)).asJsonObject
        obj.get("num")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0
    }.getOrDefault(0)
}
