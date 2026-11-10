package com.caro.nba.datasource.impl

import com.caro.nba.datasource.DataSourceCommon
import com.caro.nba.model.LeaderCategory
import com.caro.nba.model.LeaderEntry
import com.caro.nba.model.NBATeamSchedule
import com.caro.nba.model.SeasonLeaders
import com.caro.nba.model.TodayLeaders
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ESPN 统计扩展数据源（数据王榜 / 球队赛程）
 *
 * 端点（2026-11-10 实测）：
 * - 今日数据王：复用 scoreboard?dates= 的 competitions[].leaders（单场最佳聚合，零额外接口）
 * - 赛季数据王：sports.core.api.espn.com/v2/.../seasons/{year}/types/2/leaders
 *   （categories[].leaders 前 5，athlete 仅 $ref，需并发二次请求取球员名）
 * - 球队赛程：site.web.api.espn.com/apis/site/v2/sports/basketball/nba/teams/{abbr}/schedule
 *   （competitors[].winner 判断胜负）
 *
 * 仅 ESPN 提供这类数据，不走多源降级链；网络异常时上层 Result.failure 展示提示。
 */
class EspnStatsDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson
    private val teamNameMap = DataSourceCommon.teamNameMap

    companion object {
        private val HOSTS = listOf("https://site.web.api.espn.com", "https://site.api.espn.com")
        private const val CORE_HOST = "https://sports.core.api.espn.com"
        private const val SEASON_TOP_N = 5
        private const val TODAY_TOP_N = 10

        /** 今日榜聚合的三个类目：scoreboard leaders 的 name → 中文展示名 */
        private val TODAY_CATEGORIES = linkedMapOf("points" to "得分", "rebounds" to "篮板", "assists" to "助攻")

        /** 赛季榜取的三个类目：core API categories[].name → 中文展示名 */
        private val SEASON_CATEGORIES = linkedMapOf(
            "pointsPerGame" to "场均得分",
            "reboundsPerGame" to "场均篮板",
            "assistsPerGame" to "场均助攻"
        )

        /** NBA 跨年赛季的 ESPN year：7 月起算新赛季（10 月开打，6 月总决赛仍属上一年赛季） */
        fun currentSeasonYear(): Int {
            val today = LocalDate.now()
            return if (today.monthValue >= 7) today.year else today.year - 1
        }
    }

    // ---------------- 今日数据王 ----------------

    /**
     * 聚合当日所有比赛的球员单场最佳，得到联盟当日数据王。
     * @param easternDate 美东日期（与比分页查询日期语义一致）
     */
    fun todayLeaders(easternDate: LocalDate): Result<TodayLeaders> {
        val dateStr = easternDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        return fetchJson("${HOSTS.first()}/apis/site/v2/sports/basketball/nba/scoreboard?dates=$dateStr")
            .mapCatching { parseTodayLeaders(it, easternDate.toString()) }
    }

    private fun parseTodayLeaders(root: JsonObject, date: String): TodayLeaders {
        val events = root.getAsJsonArray("events")
        if (events == null || events.size() == 0) return TodayLeaders(date, emptyList())

        // 类目 → 当日全部比赛的单场最佳列表（每场比赛每类目取第一个即最佳）
        data class Best(val playerName: String, val value: Double, val display: String)
        val buckets = mutableMapOf<String, MutableList<Best>>()
        for (event in events) {
            val competition = event.asJsonObject.getAsJsonArray("competitions")?.get(0)?.asJsonObject ?: continue
            val leadersArr = competition.getAsJsonArray("leaders") ?: continue
            for (category in leadersArr) {
                val catObj = category.asJsonObject
                val catName = catObj.get("name")?.asString ?: continue
                val cnName = TODAY_CATEGORIES[catName] ?: continue
                val catLeaders = catObj.getAsJsonArray("leaders") ?: continue
                val first = catLeaders.get(0)?.asJsonObject ?: continue
                val athlete = first.getAsJsonObject("athlete") ?: continue
                val playerName = athlete.get("displayName")?.asString ?: continue
                val value = first.get("value")?.asDouble ?: continue
                val display = first.get("displayValue")?.asString ?: value.toInt().toString()
                buckets.getOrPut(cnName) { mutableListOf() }.add(Best(playerName, value, display))
            }
        }

        val categories = TODAY_CATEGORIES.values.mapIndexed { idx, cnName ->
            val entries = (buckets[cnName] ?: emptyList())
                .sortedByDescending { it.value }
                .take(TODAY_TOP_N)
                .map { LeaderEntry(it.playerName, it.display) }
            LeaderCategory(cnName, entries)
        }
        return TodayLeaders(date, categories)
    }

    // ---------------- 赛季数据王 ----------------

    fun seasonLeaders(): Result<SeasonLeaders> {
        val year = currentSeasonYear()
        return fetchJson("$CORE_HOST/v2/sports/basketball/leagues/nba/seasons/$year/types/2/leaders")
            .mapCatching { root ->
                val categories = mutableListOf<LeaderCategory>()
                val catArr = root.getAsJsonArray("categories")
                if (catArr != null) {
                    for (category in catArr) {
                        val catObj = category.asJsonObject
                        val catName = catObj.get("name")?.asString ?: continue
                        val cnName = SEASON_CATEGORIES[catName] ?: continue
                        val leadersArr = catObj.getAsJsonArray("leaders") ?: continue

                        // 并发拉 athlete $ref 取球员名（每个 ~2KB 小请求），displayValue 本地直接可取
                        val entries = runBlocking {
                            leadersArr.take(SEASON_TOP_N).map { leader ->
                                val display = leader.asJsonObject.get("displayValue")?.asString ?: ""
                                val ref = leader.asJsonObject.getAsJsonObject("athlete")?.get("\$ref")?.asString ?: ""
                                async(Dispatchers.IO) { LeaderEntry(resolveAthleteName(ref), display) }
                            }.awaitAll()
                        }
                        categories.add(LeaderCategory(cnName, entries))
                    }
                }
                SeasonLeaders("$year-${year + 1}", categories)
            }
    }

    /** athlete $ref URL → 球员名（失败返回占位） */
    private fun resolveAthleteName(ref: String): String {
        if (ref.isEmpty()) return ""
        return runCatching {
            val body = fetchBody(ref)
            gson.fromJson(body, JsonObject::class.java).get("displayName")?.asString ?: ""
        }.getOrDefault("")
    }

    // ---------------- 球队赛程 ----------------

    fun teamSchedule(abbr: String): Result<NBATeamSchedule> {
        val lower = abbr.lowercase()
        return fetchJson("${HOSTS.first()}/apis/site/v2/sports/basketball/nba/teams/$lower/schedule")
            .mapCatching { parseTeamSchedule(it) }
    }

    private fun parseTeamSchedule(root: JsonObject): NBATeamSchedule {
        val team = root.getAsJsonObject("team")
        val abbr = team?.get("abbreviation")?.asString ?: ""
        val logo = team?.get("logo")?.asString ?: ""
        val rawName = team?.get("shortDisplayName")?.asString ?: ""
        val teamName = teamNameMap[rawName] ?: rawName

        data class Row(val dateIso: String, val epochMillis: Long, val game: NBATeamSchedule.ScheduleGame)

        val recent = mutableListOf<Row>()
        val upcoming = mutableListOf<Row>()
        val events = root.getAsJsonArray("events")
        if (events != null) {
            for (event in events) {
                val evObj = event.asJsonObject
                val dateIso = evObj.get("date")?.asString ?: ""
                val competition = evObj.getAsJsonArray("competitions")?.get(0)?.asJsonObject ?: continue
                val competitors = competition.getAsJsonArray("competitors") ?: continue
                if (competitors.size() < 2) continue

                val mine = competitors.firstOrNull {
                    it.asJsonObject.getAsJsonObject("team")?.get("abbreviation")?.asString == abbr
                }?.asJsonObject ?: continue
                val opp = competitors.firstOrNull { it.asJsonObject != mine }?.asJsonObject ?: continue

                val oppTeam = opp.getAsJsonObject("team")
                val oppShort = oppTeam?.get("shortDisplayName")?.asString ?: ""
                val oppName = teamNameMap[oppShort] ?: oppShort
                val isHome = mine.get("homeAway")?.asString == "home"

                val state = competition.getAsJsonObject("status")?.getAsJsonObject("type")?.get("state")?.asString ?: "pre"
                val played = state == "post"
                val epoch = runCatching {
                    LocalDateTime.parse(dateIso.take(19)).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
                }.getOrDefault(0L)

                val game: NBATeamSchedule.ScheduleGame
                if (played) {
                    val win = mine.get("winner")?.asBoolean ?: false
                    val myScore = mine.getAsJsonObject("score")?.get("displayValue")?.asString ?: "?"
                    val oppScore = opp.getAsJsonObject("score")?.get("displayValue")?.asString ?: "?"
                    game = NBATeamSchedule.ScheduleGame(
                        dateIso = dateIso, opponentName = oppName, isHome = isHome,
                        played = true, win = win, scoreDisplay = "$myScore-$oppScore"
                    )
                } else {
                    game = NBATeamSchedule.ScheduleGame(
                        dateIso = dateIso, opponentName = oppName, isHome = isHome, played = false
                    )
                }
                (if (played) recent else upcoming).add(Row(dateIso, epoch, game))
            }
        }

        return NBATeamSchedule(
            teamName = teamName,
            teamAbbr = abbr,
            teamLogo = logo,
            recentGames = recent.sortedByDescending { it.epochMillis }.take(5).map { it.game },
            upcomingGames = upcoming.sortedBy { it.epochMillis }.take(5).map { it.game }
        )
    }

    // ---------------- 通用请求 ----------------

    private fun fetchJson(url: String): Result<JsonObject> = runCatching {
        gson.fromJson(fetchBody(url), JsonObject::class.java)
    }

    private fun fetchBody(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://www.espn.com")
            .header("Referer", "https://www.espn.com/")
            .build()
        client.newCall(request).execute().use { response ->
            DataSourceCommon.debugLog("ESPN Stats 响应: HTTP ${response.code} ($url)")
            if (!response.isSuccessful) throw Exception("ESPN 请求失败: ${response.code}")
            return response.body?.string() ?: throw Exception("ESPN 响应为空")
        }
    }
}

/** Result catches 包装（map 不抛检查异常的变体） */
private inline fun <T, R> Result<T>.mapCatching(transform: (T) -> R): Result<R> =
    runCatching { transform(getOrThrow()) }
