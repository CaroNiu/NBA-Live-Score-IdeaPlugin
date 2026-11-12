package com.caro.nba.datasource.impl

import com.caro.nba.datasource.DataSourceCommon
import com.caro.nba.model.LeaderCategory
import com.caro.nba.model.LeaderEntry
import com.caro.nba.model.NBAGame
import com.caro.nba.model.ScheduleDay
import com.caro.nba.model.ScheduleGame
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
import java.time.format.DateTimeFormatter

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
        private val CATEGORIES = linkedMapOf(
            "得分" to "场均得分",
            "篮板" to "场均篮板",
            "助攻" to "场均助攻",
            "抢断" to "场均抢断",
            "盖帽" to "场均盖帽"
        )
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

    // ---------------- 球员资料卡（插件内嵌查看） ----------------

    /**
     * 球员资料卡原始数据（playerId 与单场统计接口的 player_id 同体系）。
     * 响应 data 含 player（资料）/playerExtend/seasonData（场均 average+total）/recent5Games（近5场）/career 等，
     * 结构较松散，由 UI 层防御性解析展示。
     * 注意：PHP 偶发在 JSON 前输出 Notice 噪音，解析时提取最后一个完整 JSON 对象。
     */
    fun playerCard(playerId: String): Result<JsonObject> = runCatching {
        val request = Request.Builder()
            .url("https://data.zhibo8.cc/manage/public/app.php?_url=/nba_v2_player/player&playerId=$playerId")
            .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://data.zhibo8.cc/nbaData/player/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("直播吧球员资料请求失败: ${response.code}")
            val body = response.body?.string() ?: throw Exception("球员资料响应为空")
            val jsonPart = body.substringAfterLast("<br />").ifBlank { body }
            val root = gson.fromJson(jsonPart, JsonObject::class.java)
            if (root.get("status")?.takeIf { it.isJsonPrimitive }?.asString != "1") {
                throw Exception(root.get("mesg")?.asString ?: "球员资料未找到")
            }
            root.getAsJsonObject("data") ?: throw Exception("球员资料为空")
        }
    }

    // ---------------- 联赛赛程（直播吧当前赛季赛程，未来 N 天） ----------------

    /**
     * 未来 N 天的联赛赛程。
     * 数据源：stats.qiumibao.com 通用赛程接口（league_v2 清单 type=赛程 tab），
     * 返回当前赛季完整赛程（189 天跨度，2026-06 ~ 2027-04，按北京日期分组，title 带星期）。
     * 注意：json_v2/nba_match.htm 是 2020 赛季历史归档，勿用。
     */
    fun upcomingSchedule(days: Int = 7): Result<List<ScheduleDay>> = runCatching {
        val year = EspnStatsDataSource.currentSeasonYear()
        val request = Request.Builder()
            .url("https://stats.qiumibao.com/shuju/public/index.php" +
                "?_url=/data/index&year=$year&type=%E8%B5%9B%E7%A8%8B&tab=%E8%B5%9B%E7%A8%8B" +
                "&league_id=924&league=NBA&_platform=web&_env=pc")
            .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://data.zhibo8.cc/pc_main_data/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("直播吧赛程接口失败: ${response.code}")
            val body = response.body?.string() ?: throw Exception("赛程响应为空")
            val root = gson.fromJson(body, JsonObject::class.java)
            val dataArr = root.getAsJsonArray("data") ?: throw Exception("赛程数据缺失")

            val today = LocalDate.now() // 北京时间口径（赛程按北京日期组织）
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            dataArr.mapNotNull { dayElem ->
                val dayObj = dayElem?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val title = dayObj.get("title")?.asString ?: return@mapNotNull null
                val date = runCatching { LocalDate.parse(title.take(10), formatter) }.getOrNull() ?: return@mapNotNull null
                date to dayObj
            }
                .filter { (date, _) -> !date.isBefore(today) }
                .sortedBy { (date, _) -> date }
                .take(days)
                .map { (date, dayObj) ->
                    val games = dayObj.getAsJsonArray("list")?.mapNotNull { g ->
                        val gObj = g?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        ScheduleGame(
                            time = gObj.strCn("时间"),
                            awayTeam = gObj.strCn("客队名称")?.takeIf { it != "-" } ?: gObj.strCn("客队"),
                            homeTeam = gObj.strCn("主队名称")?.takeIf { it != "-" } ?: gObj.strCn("主队"),
                            score = gObj.strCn("比分"),
                            dateBeijing = date.format(formatter)
                        )
                    } ?: emptyList()
                    ScheduleDay(date = date.format(formatter), games = games)
                }
                .filter { it.games.isNotEmpty() }
        }
    }

    /** 防御取值（字符串字段） */
    private fun JsonObject.strCn(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

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
