package com.caro.nba.datasource.impl

import com.caro.nba.datasource.DataSourceCommon
import com.caro.nba.model.LeaderCategory
import com.caro.nba.model.LeaderEntry
import com.caro.nba.model.NBAGame
import com.caro.nba.model.SeasonLeaders
import com.caro.nba.model.TodayLeaders
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDate

/**
 * 直播吧球员数据榜数据源（中文球员名/队名，100 人完整榜单）
 *
 * 接口（2026-11-10 经浏览器实测，直播吧数据频道 data.zhibo8.cc/pc_main_data 的球员 tab）：
 * https://stats.qiumibao.com/shuju/public/index.php?_url=/data/index&year={赛季年}&type={得分|篮板|助攻}&tab=球员榜&league_id=924&league=NBA&_platform=web&_env=pc
 *
 * 响应 data[0].list[] 条目为中文键：排名/球员/球队/场均/场次/时间。
 * 赛季口径与 ESPN 一致：7 月起算新赛季（10 月开打，6 月总决赛仍属上一年）。
 */
class Zhibo8LeaderDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson

    companion object {
        private const val API = "https://stats.qiumibao.com/shuju/public/index.php"

        /** 类目：接口 type 参数 → 中文展示名 */
        private val CATEGORIES = linkedMapOf("得分" to "场均得分", "篮板" to "场均篮板", "助攻" to "场均助攻")
    }

    /** 赛季场均榜（每类取 topN，接口单次返回 100 人，无二次请求） */
    fun seasonLeaders(topN: Int = 10): Result<SeasonLeaders> = runCatching {
        val year = EspnStatsDataSource.currentSeasonYear()
        val categories = CATEGORIES.map { (typeParam, cnName) ->
            val root = fetchJson(buildUrl(typeParam, year))
            LeaderCategory(cnName, parseEntries(root, topN))
        }
        SeasonLeaders("$year-${year + 1}", categories, source = "直播吧")
    }

    // ---------------- 今日数据王（直播吧单场统计聚合） ----------------

    /**
     * 聚合当日（美东日期）全部已开赛比赛的直播吧球员单场统计：
     * 1. 复用 Zhibo8DataSource.getScores 拿当天比赛（中文队名，gameId 与统计接口同源）
     * 2. 对已开赛场次并发拉 player_{id}.htm（单请求含两队全部球员，player_name_cn 中文名）
     * 3. 每场每类目取本场最佳 → 跨场聚合排序 → TopN
     *
     * @param topN 每类展示条数
     */
    fun todayLeaders(easternDate: LocalDate, topN: Int = 10): Result<TodayLeaders> = runCatching {
        val games = Zhibo8ScoreDataSource().getScores(easternDate).getOrNull()?.games
            ?.filter { it.status != "scheduled" && it.gameId.isNotBlank() }
            ?: return@runCatching TodayLeaders(easternDate.toString(), emptyList(), source = "直播吧")

        data class Best(val playerName: String, val teamName: String, val value: Double, val display: String)

        // 类目：统计字段 → 中文展示名
        val statFields = linkedMapOf("points" to "得分", "off+def" to "篮板", "ass" to "助攻")
        val buckets = mutableMapOf<String, MutableList<Best>>()

        runBlocking {
            games.map { game ->
                async(Dispatchers.IO) {
                    val matchDate = game.startTime.take(10)  // startTime=beijingStart（yyyy-MM-dd HH:mm）
                    fetchJsonOrNull("https://dc4pc.qiumibao.com/dc/matchs/data/$matchDate/player_${game.gameId}.htm")
                }
            }.awaitAll().forEachIndexed { idx, playerRoot ->
                val game = games[idx]
                val data = playerRoot?.getAsJsonObject("data") ?: return@forEachIndexed
                for (side in listOf("host", "guest")) {
                    val sideObj = data.getAsJsonObject(side) ?: continue
                    val teamName = sideObj.get("team_name_cn")?.asString ?: ""
                    val on = sideObj.getAsJsonArray("on") ?: continue
                    for (p in on) {
                        val obj = p?.asJsonObject ?: continue
                        val name = obj.get("player_name_cn")?.asString ?: continue
                        for ((field, cnName) in statFields) {
                            val v = obj.get(field)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull() ?: continue
                            if (v <= 0) continue
                            buckets.getOrPut(cnName) { mutableListOf() }
                                .add(Best(name, teamName, v, trimStat(v)))
                        }
                    }
                }
            }
        }

        val categories = statFields.values.map { cnName ->
            val entries = (buckets[cnName] ?: emptyList())
                .sortedByDescending { it.value }
                .take(topN)
                .map { LeaderEntry("${it.playerName}（${it.teamName}）", it.display) }
            LeaderCategory(cnName, entries)
        }
        TodayLeaders(easternDate.toString(), categories, source = "直播吧")
    }

    /** 统计数值显示：整数值不带小数点 */
    private fun trimStat(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** 拉直播吧统计 JSON（失败返回 null，由上层跳过该场） */
    private fun fetchJsonOrNull(url: String): JsonObject? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://www.zhibo8.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.let { gson.fromJson(it, JsonObject::class.java) }
        }
    }.getOrNull()

    private fun buildUrl(type: String, year: Int): String =
        "$API?_url=/data/index&year=$year&type=${URLEncoder.encode(type, "UTF-8")}" +
            "&tab=${URLEncoder.encode("球员榜", "UTF-8")}&league_id=924&league=NBA&_platform=web&_env=pc"

    private fun parseEntries(root: JsonObject, topN: Int): List<LeaderEntry> {
        val dataArr = root.getAsJsonArray("data") ?: return emptyList()
        if (dataArr.size() == 0) return emptyList()
        val list = dataArr[0]?.asJsonObject?.getAsJsonArray("list") ?: return emptyList()
        return list.take(topN).mapNotNull { item ->
            val obj = item?.asJsonObject ?: return@mapNotNull null
            val name = obj.get("球员")?.asString ?: return@mapNotNull null
            val team = obj.get("球队")?.asString ?: ""
            val value = obj.get("场均")?.asString ?: ""
            // 展示「球员 · 球队」，与今日榜保持单名风格一致则只显示球员名——这里带球队更直观
            LeaderEntry(if (team.isEmpty()) name else "$name（$team）", value)
        }
    }

    private fun fetchJson(url: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://data.zhibo8.cc/pc_main_data/")
            .build()
        client.newCall(request).execute().use { response ->
            DataSourceCommon.debugLog("直播吧数据榜 响应: HTTP ${response.code}")
            if (!response.isSuccessful) throw Exception("直播吧数据接口失败: ${response.code}")
            val body = response.body?.string() ?: throw Exception("直播吧数据响应为空")
            return gson.fromJson(body, JsonObject::class.java)
        }
    }
}
