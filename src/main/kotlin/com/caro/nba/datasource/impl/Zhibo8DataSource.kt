package com.caro.nba.datasource.impl

import com.caro.nba.datasource.DataSource
import com.caro.nba.datasource.DataSourceCommon
import com.caro.nba.datasource.GameDetailDataSource
import com.caro.nba.datasource.ScoreDataSource
import com.caro.nba.model.GameDetail
import com.caro.nba.model.NBAGame
import com.caro.nba.model.NBAScoreboard
import com.caro.nba.model.PlayByPlay
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 直播吧数据源（中文数据源）
 *
 * 数据来自直播吧（zhibo8.com）比分频道的公开接口（bifen.qiumibao.com），
 * 中文队名、国内访问速度快，无需 API Key。
 *
 * 主要端点：
 * - 按日比分: https://bifen.qiumibao.com/json/{yyyy-MM-dd}/list.htm
 *   返回当天全部体育项目的比赛列表（NBA、CBA、足球等），需按 type=basketball + 队名过滤出 NBA
 *
 * 注意：
 * - 接口日期为北京时间；NBA 比赛发生在美国东部晚间，对应北京时间为次日，
 *   因此查询美东日期 D 时需请求 D 和 D+1 两天，再按比赛开始时间精确过滤
 * - 该源支持比分/赛程、比赛详情（含完整中文技术统计）与中文图文直播，
 *   不支持排名和季后赛对阵图
 */
class Zhibo8ScoreDataSource : ScoreDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson

    private val baseUrl = "https://bifen.qiumibao.com/json"

    /** 是否 NBA 球队（基于公共中文队名映射） */
    private fun isNbaTeam(name: String): Boolean =
        DataSourceCommon.chineseNameToAbbr.containsKey(DataSourceCommon.normalizeChineseTeamName(name))

    override fun getScores(date: LocalDate): Result<NBAScoreboard> {
        return try {
            // 直播吧按北京日期组织比赛：美东日期 D 的 NBA 比赛位于北京日期 D+1。
            // 同时请求 D 与 D+1 两天，先按比赛开始时间（北京->美东）精确过滤；
            // 若无精确匹配，退回请求日期当天的 NBA 比赛（兼容调用方直接传北京日期）
            val nextDay = fetchDay(date.plusDays(1))
            val sameDay = fetchDay(date)

            if (nextDay.isFailure && sameDay.isFailure) {
                val failure: Result<NBAScoreboard> = Result.failure(
                    Exception("直播吧 API 请求失败: ${nextDay.exceptionOrNull()?.message ?: sameDay.exceptionOrNull()?.message}")
                )
                return failure
            }

            val candidates = nextDay.getOrDefault(emptyList()) + sameDay.getOrDefault(emptyList())
            DataSourceCommon.debugLog(
                "直播吧 请求日期=$date: 次日(${date.plusDays(1)})=${nextDay.getOrDefault(emptyList()).size} 场, " +
                        "当日=${sameDay.getOrDefault(emptyList()).size} 场 NBA 比赛"
            )

            // 精确过滤：比赛开始时间（北京时间）换算到美东后落在请求日期
            val etMatched = candidates.filter { game ->
                toEasternDate(game.startTime) == date
            }
            DataSourceCommon.debugLog("直播吧 美东日期精确匹配: ${etMatched.size} 场")

            // 只采信美东日期精确匹配。不要用「当天/次日列表兜底」——精确匹配为空时
            // 兜底会返回北京日期对得上但美东日期错位的比赛
            // （如查美东 10-29 却返回美东 10-28 已打完的场次，UI 表现为"切日期查的还是今天的比赛"）
            if (etMatched.isEmpty()) {
                val easternToday = LocalDate.now(ZoneId.of("America/New_York"))
                if (!date.isBefore(easternToday)) {
                    // 直播吧按北京日期归档且只发布已开始的赛程：美东当天晚场（北京次日早上开打）
                    // 与未来日期的 NBA 条目不在任何列表中。返回失败放行降级链（ESPN 等有完整赛程）
                    val failure: Result<NBAScoreboard> = Result.failure(
                        Exception("直播吧：该日期无赛程数据")
                    )
                    return failure
                }
                // 历史日期精确匹配为空 = 确实没有比赛，正常返回空列表
                return Result.success(NBAScoreboard(date.toString(), emptyList()))
            }

            Result.success(NBAScoreboard(date.toString(), etMatched))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 请求直播吧某北京日期的比赛列表，过滤出 NBA 比赛
     */
    private fun fetchDay(beijingDate: LocalDate): Result<List<NBAGame>> {
        return try {
            val dateStr = beijingDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val url = "$baseUrl/$dateStr/list.htm"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
                .header("Referer", "https://www.zhibo8.com/")
                .header("Accept", "application/json, text/plain, */*")
                .build()

            client.newCall(request).execute().use { response ->
                DataSourceCommon.debugLog("直播吧 响应: HTTP ${response.code} ($url)")
                if (!response.isSuccessful) {
                    val failure: Result<List<NBAGame>> = Result.failure(Exception("HTTP ${response.code}"))
                    return failure
                }

                val body = response.body?.string()
                    ?: return Result.failure(Exception("直播吧响应为空"))

                val games = parseScoreboard(body)
                DataSourceCommon.debugLog("直播吧 $dateStr 解析出 ${games.size} 场 NBA 比赛")
                Result.success(games)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 解析按日比赛列表 JSON，过滤出 NBA 比赛
     */
    private fun parseScoreboard(json: String): List<NBAGame> {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val listElement = root.get("list")
            if (listElement == null || listElement.isJsonNull || !listElement.isJsonArray) {
                return emptyList()
            }

            listElement.asJsonArray.mapNotNull { element -> parseGame(element.asJsonObject) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析单场比赛，非 NBA 比赛返回 null
     */
    private fun parseGame(game: JsonObject): NBAGame? {
        return try {
            // type=basketball 且 leagueid=924 或任一队为 NBA 球队（容错：季前赛 leagueid 可能不同）
            val type = game.get("type")?.asString ?: return null
            if (type != "basketball") return null

            val homeNameRaw = game.get("home_team")?.asString ?: ""
            val awayNameRaw = game.get("visit_team")?.asString ?: ""
            val homeName = DataSourceCommon.normalizeChineseTeamName(homeNameRaw)
            val awayName = DataSourceCommon.normalizeChineseTeamName(awayNameRaw)

            // 过滤系列赛大比分伪条目（队名带 "(1)" 之类后缀，如 "尼克斯(1) vs 骑士(0)"）
            val seriesPattern = Regex("""\(\d+\)$""")
            if (seriesPattern.containsMatchIn(homeNameRaw) || seriesPattern.containsMatchIn(awayNameRaw)) {
                return null
            }

            val leagueId = game.get("leagueid")?.asString ?: ""
            val isNba = leagueId == "924" || (isNbaTeam(homeName) && homeName.isNotEmpty()) ||
                    (isNbaTeam(awayName) && awayName.isNotEmpty())
            if (!isNba) return null

            // 状态映射：1=未开始, 2=进行中, 3=完赛, 4=延期, 7=待定, 8=取消
            val state = game.get("state")?.asString ?: "1"
            val periodCn = (game.get("period_cn")?.asString ?: "").trim()
            val status = when (state) {
                "2" -> "in_progress"
                "3" -> "finished"
                else -> "scheduled"
            }

            // period_cn 形如 "第4节\n08:04"、"中场"、"完赛"
            var period = 0
            var clock = ""
            val periodMatch = Regex("""第(\d+)节""").find(periodCn)
            when {
                periodMatch != null -> period = periodMatch.groupValues[1].toIntOrNull() ?: 0
                periodCn.contains("加时") -> period = 5
            }
            periodCn.split("\n").getOrNull(1)?.let { clock = it.trim() }

            val homeScore = game.get("home_score")?.asString?.toIntOrNull() ?: 0
            val awayScore = game.get("visit_score")?.asString?.toIntOrNull() ?: 0

            NBAGame(
                gameId = game.get("id")?.asString ?: "",
                status = status,
                period = period,
                clock = clock,
                startTime = game.get("start")?.asString ?: "",
                detail = periodCn.replace("\n", " "),
                homeTeam = buildTeam(game.get("home_id")?.asString ?: "", homeName),
                homeScore = homeScore,
                awayTeam = buildTeam(game.get("visit_id")?.asString ?: "", awayName),
                awayScore = awayScore
            )
        } catch (e: Exception) {
            // 解析失败不再静默丢弃，打日志便于排查
            DataSourceCommon.debugLog("直播吧 单场解析失败(id=${game.get("id")?.asString}): ${e.message}")
            null
        }
    }

    /**
     * 构建球队信息：直播吧不提供队徽，用 ESPN CDN 队徽兜底
     */
    private fun buildTeam(teamId: String, chineseName: String): NBAGame.Team {
        val abbr = DataSourceCommon.chineseNameToAbbr[chineseName] ?: ""
        return NBAGame.Team(
            id = teamId,
            name = chineseName,  // 已经是中文
            abbreviation = abbr,
            shortName = chineseName,
            logo = DataSourceCommon.espnTeamLogo(abbr)
        )
    }

    /**
     * 北京时间字符串 -> 美东日期
     */
    private fun toEasternDate(beijingStart: String): LocalDate? {
        return try {
            val ldt = LocalDateTime.parse(beijingStart, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            ldt.atZone(ZoneId.of("Asia/Shanghai"))
                .withZoneSameInstant(ZoneId.of("America/New_York"))
                .toLocalDate()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 直播吧文字转播数据源（中文图文直播）
 *
 * 链路（2026-10-28 实测，逆自直播间页面 JS bk2016_v2.v5.js）：
 * 1. max_sid: https://{server}.qiumibao.com/livetext/data/cache/livetext/{saishi_id}/0/max_sid.json
 *             -> {"max_sid":N,...}；非 JSON 响应（404 页面）视为直播间未建立。
 *             域名双活（dingshi145 / dingshi4pc），由页面 JS 下发，按序尝试互为容错
 * 2. 分块:    .../livetext/{saishi_id}/0/page_10/{ceil(sid/10)*10}.htm -> JSON 数组（10 条/块）
 *             注意 per_num=10 的路径无 lit_ 前缀（页面 JS 既有写法）
 *
 * saishi_id 即 Zhibo8ScoreDataSource 返回的 gameId（比分列表 id 字段），链路内自洽。
 * 完赛/历史比赛数据长期保留（实测前一天全量可取），仅未开赛比赛可能 404。
 */
class Zhibo8PlayByPlayDataSource : GameDetailDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson

    companion object {
        /** 直播间静态缓存域名（页面 JS server_name，2026-10 实测双活） */
        private val LIVE_HOSTS = listOf("dingshi145.qiumibao.com", "dingshi4pc.qiumibao.com")

        /** 每块消息条数（page_10 固定 10 条/块） */
        private const val BLOCK_SIZE = 10

        /** 单次最多拉取块数：150 条解说，更早的内容需翻页加载（v1 不做） */
        private const val MAX_BLOCKS = 15
    }

    override fun getGameDetail(gameId: String): Result<GameDetail> {
        // 详情由 Zhibo8GameDetailDataSource 提供（见 gameDetailDataSources 工厂）
        return Result.failure(Exception("直播吧：该实现仅提供文字转播"))
    }

    override fun getPlayByPlay(gameId: String, homeTeamId: String, awayTeamId: String): Result<PlayByPlay> {
        return try {
            val maxSid = fetchMaxSid(gameId)
                ?: return fail("直播吧：该比赛暂无图文直播（未开赛或直播间未建立）")
            DataSourceCommon.debugLog("直播吧直播: saishi_id=$gameId max_sid=$maxSid")

            val messages = fetchMessages(gameId, maxSid)
            if (messages.isEmpty()) {
                return fail("直播吧：该比赛暂无图文直播内容")
            }

            Result.success(PlayByPlay(gameId, buildPlays(messages)))
        } catch (e: Exception) {
            DataSourceCommon.debugLog("直播吧文字转播异常: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 查询直播间最新消息序号；所有域名均失败返回 null
     */
    private fun fetchMaxSid(saishiId: String): Int? {
        for (host in LIVE_HOSTS) {
            val json = fetchJson<JsonObject>(host, "livetext/data/cache/livetext/$saishiId/0/max_sid.json") ?: continue
            val sid = json.get("max_sid")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
            if (sid > 0) return sid
        }
        return null
    }

    /**
     * 从最新块向前拉取消息分块（页码按 ceil(sid/10)*10 对齐），404 即停
     */
    private fun fetchMessages(saishiId: String, maxSid: Int): List<JsonObject> {
        val topBlock = ((maxSid + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE
        val messages = mutableListOf<JsonObject>()

        for (host in LIVE_HOSTS) {
            messages.clear()
            var missing = 0
            // 块号按 ceil(sid/10)*10 对齐，每次递减一个块（10 条）
            for (block in topBlock downTo BLOCK_SIZE step BLOCK_SIZE) {
                if (missing >= 2) break  // 连续 2 块缺失视为已到数据头部
                if (messages.size / BLOCK_SIZE >= MAX_BLOCKS) break

                val arr = fetchJson<JsonArray>(host, "livetext/data/cache/livetext/$saishiId/0/page_10/$block.htm")
                if (arr == null) {
                    missing++
                    continue
                }
                missing = 0
                arr.forEach { e ->
                    if (e.isJsonObject) messages.add(e.asJsonObject)
                }
            }
            if (messages.isNotEmpty()) {
                DataSourceCommon.debugLog("直播吧直播: 拉取 ${messages.size} 条消息($host)")
                return messages
            }
            // 该域名整链失败，换下一个域名重拉
        }
        return messages
    }

    /**
     * 消息列表（任意顺序）-> 按 live_sid 升序的 Play 列表，含节次继承与得分标记
     */
    private fun buildPlays(messages: List<JsonObject>): List<PlayByPlay.Play> {
        val sorted = messages.sortedBy { it.get("live_sid")?.takeIf { e -> e.isJsonPrimitive }?.asInt ?: 0 }

        var prevVisit = -1
        var prevHome = -1
        var lastPeriod = 0
        val plays = mutableListOf<PlayByPlay.Play>()

        for (msg in sorted) {
            val text = msg.get("live_text")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: ""
            val periodDisplay = msg.get("pid_text")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: ""
            if (text.isEmpty() && periodDisplay.isEmpty()) continue

            // 节数：pid_text="第N节"直接取；加时记 5；其余（比赛结束/中场等）继承上一条，保持分组连续
            val periodFromPid = Regex("""第(\d+)节""").find(periodDisplay)?.groupValues?.get(1)?.toIntOrNull()
            val period = when {
                periodFromPid != null -> periodFromPid
                periodDisplay.contains("加时") -> 5
                else -> lastPeriod
            }
            lastPeriod = period

            val visit = msg.get("visit_score")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0
            val home = msg.get("home_score")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0
            // 比分相对上一条消息增长视为得分事件（直播吧消息本身无得分标记字段）
            val scoring = (prevVisit in 0..visit && prevHome in 0..home) && (visit > prevVisit || home > prevHome)
            prevVisit = visit
            prevHome = home

            plays.add(
                PlayByPlay.Play(
                    id = msg.get("live_id")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    sequenceNumber = msg.get("live_sid")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    text = text.ifEmpty { periodDisplay },
                    shortText = text.take(20),
                    clock = msg.get("live_time")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.length >= 16 }?.substring(11, 16) ?: "",
                    period = period,
                    periodDisplay = periodDisplay.ifEmpty { "图文直播" },
                    teamId = "",
                    teamName = "",
                    awayScore = visit,
                    homeScore = home,
                    isScoringPlay = scoring,
                    scoreValue = 0,
                    isShootingPlay = false,
                    playType = msg.get("user_chn")?.takeIf { it.isJsonPrimitive }?.asString ?: "",  // 解说主播昵称
                    participants = emptyList()
                )
            )
        }
        return plays
    }

    /**
     * GET 直播间静态缓存并解析为 JSON，失败（HTTP 非 200 / 非法 JSON / 类型不符）返回 null。
     * max_sid.json 为对象、消息分块为数组，用泛型区分。
     */
    private inline fun <reified T : JsonElement> fetchJson(host: String, path: String): T? {
        return try {
            // max_sid/分块都是固定 URL 的滚动更新内容，CDN 有缓存；
            // 不带随机参数轮询会持续命中缓存拿到旧直播（官方页面用 ?time=Math.random() 规避）
            val url = "https://$host/$path?_=${System.nanoTime()}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
                .header("Referer", "https://www.zhibo8.com/")
                .header("Accept", "*/*")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                // 404 等场景返回 nginx HTML 页，gson 解析失败即视为无数据
                gson.fromJson<JsonElement>(body, JsonElement::class.java) as? T
            }
        } catch (e: Exception) {
            DataSourceCommon.debugLog("直播吧直播请求异常($host/$path): ${e.message}")
            null
        }
    }

    private fun fail(message: String): Result<PlayByPlay> {
        DataSourceCommon.debugLog("直播吧文字转播: $message")
        return Result.failure(Exception(message))
    }
}

/**
 * 直播吧比赛详情数据源（中文，含完整球员技术统计）
 *
 * 链路（2026-10-28 实测，逆自比赛页 www.zhibo8.com/zhibo/nba/{y}/match{id}v.htm?redirect=shuju
 * 的「数据」tab 与 bk2016_v2.v5.js 的 get_match_data）：
 * 1. 元数据: https://s.qiumibao.com/json/match/{id}.htm
 *            -> match_date（北京日期）、home_id/visit_id、home_team/visit_team（中文队名）
 * 2. 比分:   https://bifen4pc2.qiumibao.com/json/{match_date}/v2/{id}.htm
 *            -> state(1未开始/2进行中/3完赛)、period_cn、left/right{score, scores[8] 每节比分}
 * 3. 统计:   https://dc4pc.qiumibao.com/dc/matchs/data/{match_date}/player_{id}.htm
 *            -> data.host/guest{team_id, team_name_cn, on[]{minutes, field/three/free(命中-出手),
 *               off/def/off+def 篮板, ass/ste/blo/turn/fouls, points, plusMinus,
 *               shirt_number, position, player_logo, redirect_url}}
 *
 * 注意：v2 的 left/right 与统计的 host/guest 均为「页面展示位」（实测 host=左侧=客队），
 * 与主客队的对应关系必须以元数据 home_id/visit_id 按 team_id 匹配，不能按字段名直接取。
 */
class Zhibo8GameDetailDataSource : GameDetailDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson

    override fun getGameDetail(gameId: String): Result<GameDetail> {
        return try {
            // 1. 元数据（主客队 id/中文队名，是左右展示位映射回主客队的基准）
            val meta = fetchJsonObject("https://s.qiumibao.com/json/match/$gameId.htm")
                ?: return fail("直播吧：比赛元数据获取失败 (id=$gameId)")
            val matchDate = meta.get("match_date")?.takeIf { it.isJsonPrimitive }?.asString
                ?: return fail("直播吧：比赛元数据缺 match_date")
            val homeId = meta.get("home_id")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val visitId = meta.get("visit_id")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val homeName = DataSourceCommon.normalizeChineseTeamName(
                meta.get("home_team")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            )
            val visitName = DataSourceCommon.normalizeChineseTeamName(
                meta.get("visit_team")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            )

            // 2. 比分/状态（未开赛时 v2 可能无数据，detail 出壳、统计为空即可）
            val v2 = fetchJsonObject("https://bifen4pc2.qiumibao.com/json/$matchDate/v2/$gameId.htm")
            val state = v2?.get("state")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val status = when (state) {
                "2" -> "in_progress"
                "3" -> "finished"
                else -> "scheduled"
            }
            val periodCn = v2?.get("period_cn")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: ""
            val periodMatch = Regex("""第(\d+)节""").find(periodCn)
            var period = when {
                periodMatch != null -> periodMatch.groupValues[1].toIntOrNull() ?: 0
                periodCn.contains("加时") -> 5
                else -> 0
            }
            val clock = periodCn.split("\n").getOrNull(1)?.trim() ?: ""

            // left/right 按 team_id 对号入座；元数据 id 缺失时按实测惯例 right=主队
            val left = v2?.getAsJsonObject("left")
            val right = v2?.getAsJsonObject("right")
            fun sideId(side: JsonObject?): String =
                side?.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val homeSide = when {
                homeId.isNotBlank() && sideId(left) == homeId -> left
                homeId.isNotBlank() && sideId(right) == homeId -> right
                else -> right
            }
            val visitSide = if (homeSide === left) right else left

            fun quarterStats(side: JsonObject?): Map<String, String> {
                val arr = side?.getAsJsonArray("scores") ?: return emptyMap()
                val map = mutableMapOf<String, String>()
                arr.forEachIndexed { i, e ->
                    val v = e.takeIf { it.isJsonPrimitive }?.asString ?: return@forEachIndexed
                    // "0" 多为未打的节（加时位），不展示
                    if (v.isNotBlank() && v != "0") {
                        map[if (i < 4) "第${i + 1}节" else "加时${i - 3}"] = v
                    }
                }
                return map
            }
            fun scoreOf(side: JsonObject?): Int =
                side?.get("score")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0

            fun buildTeam(name: String, teamId: String, side: JsonObject?, stats: Map<String, String>): GameDetail.TeamDetail {
                val abbr = DataSourceCommon.chineseNameToAbbr[name] ?: ""
                return GameDetail.TeamDetail(
                    id = teamId,
                    name = name,
                    abbreviation = abbr,
                    logo = DataSourceCommon.espnTeamLogo(abbr),
                    score = scoreOf(side),
                    statistics = stats
                )
            }

            // 3. 球员技术统计（host/guest 同样按 team_id 匹配；缺 id 时按实测惯例 host=客队）
            val playerRoot = fetchJsonObject(
                "https://dc4pc.qiumibao.com/dc/matchs/data/$matchDate/player_$gameId.htm"
            )
            val statData = playerRoot?.getAsJsonObject("data")
            val hostStats = statData?.getAsJsonObject("host")
            val guestStats = statData?.getAsJsonObject("guest")
            fun teamIdOf(side: JsonObject?): String =
                side?.get("team_id")?.takeIf { it.isJsonPrimitive }?.asString ?: ""

            val awayStatSide: JsonObject?
            val homeStatSide: JsonObject?
            when {
                homeId.isNotBlank() && teamIdOf(hostStats) == homeId -> {
                    homeStatSide = hostStats; awayStatSide = guestStats
                }
                homeId.isNotBlank() && teamIdOf(guestStats) == homeId -> {
                    homeStatSide = guestStats; awayStatSide = hostStats
                }
                else -> { awayStatSide = hostStats; homeStatSide = guestStats }  // 实测 host=客队
            }

            fun parsePlayer(p: JsonObject): GameDetail.Player {
                fun str(k: String) = p.get(k)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                fun splitPair(k: String): Pair<String, String> {
                    val parts = str(k).split("-")
                    return (parts.getOrNull(0) ?: "0") to (parts.getOrNull(1) ?: "0")
                }
                val (fgMade, fgAtt) = splitPair("field")
                val (threeMade, threeAtt) = splitPair("three")
                val (ftMade, ftAtt) = splitPair("free")
                // position 字段赛后会变成「首发」标记（网页上的首发高亮），真实位置在 pos；
                // 两者都取真实位置语义，替补两者皆空
                val posRaw = str("pos")
                val positionRaw = str("position")
                val position = when {
                    posRaw.isNotBlank() && posRaw != "首发" -> posRaw
                    positionRaw.isNotBlank() && positionRaw != "首发" -> positionRaw
                    else -> ""
                }
                return GameDetail.Player(
                    id = str("player_id"),
                    name = str("player_name_cn"),
                    jersey = str("shirt_number"),
                    position = position,
                    minutes = str("minutes"),
                    points = str("points"),
                    rebounds = str("off+def"),
                    offensiveRebounds = str("off"),
                    defensiveRebounds = str("def"),
                    assists = str("ass"),
                    steals = str("ste"),
                    blocks = str("blo"),
                    turnovers = str("turn"),
                    fouls = str("fouls"),
                    fgMade = fgMade, fgAttempts = fgAtt,
                    threeMade = threeMade, threeAttempts = threeAtt,
                    ftMade = ftMade, ftAttempts = ftAtt,
                    plusMinus = str("plusMinus"),
                    headshot = str("player_logo"),
                    playerUrl = str("redirect_url")
                )
            }
            fun playersOf(side: JsonObject?): List<GameDetail.Player> {
                val on = side?.getAsJsonArray("on") ?: return emptyList()
                return on.mapNotNull { if (it.isJsonObject) parsePlayer(it.asJsonObject) else null }
            }

            // 球队总计行（total 各字段与球员同构；数据为空/全 "-" 时不出行，如未开赛）
            fun parseTotals(t: JsonObject?): GameDetail.Player? {
                t ?: return null
                fun str(k: String) = t.get(k)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                fun splitPair(k: String): Pair<String, String> {
                    val parts = str(k).split("-")
                    return (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
                }
                if (str("points").isBlank() || str("points") == "-") return null
                val (fgMade, fgAtt) = splitPair("field")
                val (threeMade, threeAtt) = splitPair("three")
                val (ftMade, ftAtt) = splitPair("free")
                return GameDetail.Player(
                    id = "",
                    name = "总计",
                    jersey = "",
                    position = "",
                    minutes = str("minutes").ifBlank { "-" },
                    points = str("points"),
                    rebounds = str("off+def"),
                    offensiveRebounds = str("off"),
                    defensiveRebounds = str("def"),
                    assists = str("ass"),
                    steals = str("ste"),
                    blocks = str("blo"),
                    turnovers = str("turn"),
                    fouls = str("fouls"),
                    fgMade = fgMade, fgAttempts = fgAtt,
                    threeMade = threeMade, threeAttempts = threeAtt,
                    ftMade = ftMade, ftAttempts = ftAtt,
                    plusMinus = str("plusMinus")
                )
            }

            val detail = GameDetail(
                gameId = gameId,
                status = status,
                clock = if (status == "in_progress") clock else "",
                period = period,
                homeTeam = buildTeam(homeName, homeId, homeSide, quarterStats(homeSide)),
                awayTeam = buildTeam(visitName, visitId, visitSide, quarterStats(visitSide)),
                players = if (statData != null) GameDetail.PlayerStats(
                    homePlayers = playersOf(homeStatSide),
                    awayPlayers = playersOf(awayStatSide),
                    homeTotals = parseTotals(homeStatSide?.getAsJsonObject("total")),
                    awayTotals = parseTotals(awayStatSide?.getAsJsonObject("total"))
                ) else null
            )
            DataSourceCommon.debugLog(
                "直播吧详情解析成功: ${visitName} @ ${homeName} (${detail.awayTeam.score}-${detail.homeTeam.score}), " +
                        "球员 ${detail.players?.homePlayers?.size ?: 0}+${detail.players?.awayPlayers?.size ?: 0}"
            )
            Result.success(detail)
        } catch (e: Exception) {
            DataSourceCommon.debugLog("直播吧详情异常: ${e.message}")
            Result.failure(e)
        }
    }

    override fun getPlayByPlay(gameId: String, homeTeamId: String, awayTeamId: String): Result<PlayByPlay> {
        // 文字转播由 Zhibo8PlayByPlayDataSource 提供
        return Result.failure(Exception("直播吧：该实现仅提供比赛详情"))
    }

    /**
     * GET 纯 JSON 接口并解析为 JsonObject，失败（HTTP 非 200 / 非法 JSON）返回 null
     *
     * 统一追加随机参数绕过 CDN 缓存：v2 比分等接口为固定 URL 的滚动更新内容，
     * 缓存会导致详情比分滞后（官方页面同类接口均带 ?get=Math.random()）
     */
    private fun fetchJsonObject(url: String): JsonObject? {
        return try {
            val request = Request.Builder()
                .url("$url?_=${System.nanoTime()}")
                .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
                .header("Referer", "https://www.zhibo8.com/")
                .header("Accept", "application/json, text/plain, */*")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DataSourceCommon.debugLog("直播吧详情 HTTP ${response.code}: $url")
                    return null
                }
                val body = response.body?.string() ?: return null
                gson.fromJson(body, JsonObject::class.java)
            }
        } catch (e: Exception) {
            DataSourceCommon.debugLog("直播吧详情请求异常($url): ${e.message}")
            null
        }
    }

    private fun fail(message: String): Result<GameDetail> {
        DataSourceCommon.debugLog("直播吧详情: $message")
        return Result.failure(Exception(message))
    }
}

/**
 * 按「美东日期 + 两队缩写」反查直播吧 saishi_id（跨源自 ESPN eventId 用），失败返回 null
 */
internal fun findZhibo8Id(easternDate: LocalDate, homeAbbr: String, awayAbbr: String): String? {
    return try {
        val home = DataSourceCommon.abbrToChineseName[homeAbbr.uppercase()] ?: return null
        val away = DataSourceCommon.abbrToChineseName[awayAbbr.uppercase()] ?: return null
        val targets = setOf(home, away)

        // 直播吧按北京日期组织比赛：美东日期 D 的比赛位于北京日期 D 与 D+1（北京时间上午场）
        val candidates = (fetchNbaList(easternDate) + fetchNbaList(easternDate.plusDays(1)))
            .filter { zhibo8ToEasternDate(it.start) == easternDate }

        DataSourceCommon.debugLog("直播吧反查: 美东$easternDate 候选 ${candidates.size} 场, 目标=$away@$home")
        candidates.firstOrNull { game ->
            setOf(
                DataSourceCommon.normalizeChineseTeamName(game.homeTeam),
                DataSourceCommon.normalizeChineseTeamName(game.awayTeam)
            ) == targets
        }?.id
    } catch (e: Exception) {
        DataSourceCommon.debugLog("直播吧反查异常: ${e.message}")
        null
    }
}

/** 反查用轻量比赛条目 */
private data class Zhibo8ListEntry(val id: String, val homeTeam: String, val awayTeam: String, val start: String)

/**
 * 请求直播吧某北京日期列表并返回其中的 NBA 比赛条目（leagueid=924 精确过滤）
 */
private fun fetchNbaList(beijingDate: LocalDate): List<Zhibo8ListEntry> {
    return try {
        val dateStr = beijingDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val request = Request.Builder()
            .url("https://bifen.qiumibao.com/json/$dateStr/list.htm")
            .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
            .header("Referer", "https://www.zhibo8.com/")
            .header("Accept", "application/json, text/plain, */*")
            .build()

        val client = DataSourceCommon.client
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val root = DataSourceCommon.gson.fromJson(body, JsonObject::class.java)
            val list = root.getAsJsonArray("list") ?: return emptyList()

            list.mapNotNull { element ->
                val game = element.asJsonObject
                if (game.get("type")?.asString != "basketball") return@mapNotNull null
                if (game.get("leagueid")?.asString != "924") return@mapNotNull null
                val id = game.get("id")?.asString ?: return@mapNotNull null
                Zhibo8ListEntry(
                    id = id,
                    homeTeam = game.get("home_team")?.asString ?: "",
                    awayTeam = game.get("visit_team")?.asString ?: "",
                    start = game.get("start")?.asString ?: ""
                )
            }
        }
    } catch (e: Exception) {
        DataSourceCommon.debugLog("直播吧反查列表请求失败($beijingDate): ${e.message}")
        emptyList()
    }
}

/**
 * 北京时间字符串 -> 美东日期（文件级，供反查使用）
 */
private fun zhibo8ToEasternDate(beijingStart: String): LocalDate? {
    return try {
        val ldt = LocalDateTime.parse(beijingStart, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        ldt.atZone(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneId.of("America/New_York"))
            .toLocalDate()
    } catch (e: Exception) {
        null
    }
}
