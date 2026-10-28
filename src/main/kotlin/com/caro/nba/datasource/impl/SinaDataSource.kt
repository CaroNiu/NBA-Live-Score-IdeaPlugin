package com.caro.nba.datasource.impl

import com.caro.nba.datasource.DataSourceCommon
import com.caro.nba.datasource.GameDetailDataSource
import com.caro.nba.datasource.ScoreDataSource
import com.caro.nba.model.GameDetail
import com.caro.nba.model.NBAGame
import com.caro.nba.model.NBAScoreboard
import com.caro.nba.model.PlayByPlay
import com.google.gson.JsonObject
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 新浪 NBA 数据源（中文数据源）
 *
 * 数据来自新浪体育 NBA 频道的公开 JSONP 接口（slamdunk.sports.sina.com.cn），
 * 接口为 NBA 专属，无需过滤其他联赛，中文队名，无需 API Key。
 * （参考开源项目 github.com/sunfeilong/NBA 的接口用法）
 *
 * 主要端点：
 * - 按日赛程/比分: https://slamdunk.sports.sina.com.cn/api?p=radar&s=schedule&a=day&date={yyyy-MM-dd}
 *   返回 result.data.matchs[]，含每节比分（scoring.score1-8）、球队胜负场、
 *   球员领袖（leader：得分/篮板/助攻，中文名）等
 *
 * 注意：
 * - 响应为 JSONP 格式（try{dayCallback({...});}catch(e){};），需剥壳后再解析
 * - 接口日期为北京时间；NBA 比赛发生在美国东部晚间，对应北京时间为次日，
 *   因此查询美东日期 D 时需请求 D 和 D+1 两天，再按比赛开始时间精确过滤
 * - 该源仅支持比分/赛程，不支持排名、季后赛对阵图和比赛详情
 */
class SinaScoreDataSource : ScoreDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson

    private val scheduleUrl = "https://slamdunk.sports.sina.com.cn/api?p=radar&s=schedule&a=day&date="

    override fun getScores(date: LocalDate): Result<NBAScoreboard> {
        return try {
            // 新浪按北京日期组织比赛：美东日期 D 的 NBA 比赛位于北京日期 D+1。
            // 同时请求 D 与 D+1 两天，先按比赛开始时间（北京->美东）精确过滤；
            // 若无精确匹配，退回请求日期当天的比赛（兼容调用方直接传北京日期）
            val nextDay = fetchDay(date.plusDays(1))
            val sameDay = fetchDay(date)

            if (nextDay.isFailure && sameDay.isFailure) {
                val failure: Result<NBAScoreboard> = Result.failure(
                    Exception("新浪 NBA API 请求失败: ${nextDay.exceptionOrNull()?.message ?: sameDay.exceptionOrNull()?.message}")
                )
                return failure
            }

            val candidates = nextDay.getOrDefault(emptyList()) + sameDay.getOrDefault(emptyList())
            DataSourceCommon.debugLog(
                "新浪 请求日期=$date: 次日(${date.plusDays(1)})=${nextDay.getOrDefault(emptyList()).size} 场, " +
                        "当日=${sameDay.getOrDefault(emptyList()).size} 场 NBA 比赛"
            )

            // 精确过滤：比赛开始时间（北京时间）换算到美东后落在请求日期
            val etMatched = candidates.filter { game ->
                toEasternDate(game.startTime) == date
            }
            DataSourceCommon.debugLog("新浪 美东日期精确匹配: ${etMatched.size} 场")

            val games = when {
                etMatched.isNotEmpty() -> etMatched
                sameDay.getOrDefault(emptyList()).isNotEmpty() -> sameDay.getOrDefault(emptyList())
                else -> nextDay.getOrDefault(emptyList())
            }

            Result.success(NBAScoreboard(date.toString(), games))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 请求新浪某北京日期的 NBA 比赛列表
     */
    private fun fetchDay(beijingDate: LocalDate): Result<List<NBAGame>> {
        return try {
            val dateStr = beijingDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val url = "$scheduleUrl$dateStr&callback=dayCallback"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
                .header("Referer", "https://sports.sina.com.cn/")
                .header("Accept", "*/*")
                .build()

            client.newCall(request).execute().use { response ->
                DataSourceCommon.debugLog("新浪 响应: HTTP ${response.code} ($url)")
                if (!response.isSuccessful) {
                    val failure: Result<List<NBAGame>> = Result.failure(Exception("HTTP ${response.code}"))
                    return failure
                }

                val body = response.body?.string()
                    ?: return Result.failure(Exception("新浪 NBA 响应为空"))

                val games = parseScoreboard(body)
                DataSourceCommon.debugLog("新浪 $dateStr 解析出 ${games.size} 场 NBA 比赛")
                Result.success(games)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 解析按日赛程 JSON
     */
    private fun parseScoreboard(jsonp: String): List<NBAGame> {
        return try {
            val json = unwrapJsonp(jsonp) ?: return emptyList()
            val root = gson.fromJson(json, JsonObject::class.java)
            val data = root.getAsJsonObject("result")?.getAsJsonObject("data")

            // 注意接口字段名就是 matchs（无拼写错误）
            val matchsElement = data?.get("matchs")
            if (matchsElement == null || matchsElement.isJsonNull || !matchsElement.isJsonArray) {
                return emptyList()
            }

            matchsElement.asJsonArray.mapNotNull { element -> parseGame(element.asJsonObject) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析单场比赛
     */
    private fun parseGame(match: JsonObject): NBAGame? {
        return try {
            val mid = match.get("mid")?.asString ?: return null
            val status = match.get("status")?.asString ?: ""
            // 未开赛的比赛 quarter 为空字符串，asInt 会抛 NumberFormatException，必须用 toIntOrNull 容错
            val quarter = match.get("quarter")?.asString?.toIntOrNull() ?: 0
            val clock = match.get("clock")?.asString ?: ""
            val dateStr = match.get("date")?.asString ?: ""
            val timeStr = match.get("time")?.asString ?: ""

            val homeObj = match.getAsJsonObject("home") ?: return null
            val awayObj = match.getAsJsonObject("away") ?: return null
            val homeName = DataSourceCommon.normalizeChineseTeamName(homeObj.get("name")?.asString ?: "")
            val awayName = DataSourceCommon.normalizeChineseTeamName(awayObj.get("name")?.asString ?: "")

            // 状态映射：scheduled/created=未开始, inprogress=进行中, halftime=中场, closed=已结束
            val gameStatus = when (status) {
                "inprogress", "halftime" -> "in_progress"
                "closed" -> "finished"
                else -> "scheduled"
            }
            val detail = when (status) {
                "inprogress" -> if (clock.isNotBlank() && clock != "00:00") "第${quarter}节 $clock" else "第${quarter}节"
                "halftime" -> "中场休息"
                "closed" -> "完赛"
                else -> timeStr
            }

            NBAGame(
                gameId = mid,
                status = gameStatus,
                period = quarter,
                clock = if (gameStatus == "in_progress") clock else "",
                startTime = "$dateStr $timeStr",
                detail = detail,
                homeTeam = buildTeam(homeObj, homeName),
                homeScore = homeObj.get("score")?.asString?.toIntOrNull() ?: 0,
                awayTeam = buildTeam(awayObj, awayName),
                awayScore = awayObj.get("score")?.asString?.toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            // 解析失败不再静默丢弃，打日志便于排查
            DataSourceCommon.debugLog("新浪 单场解析失败(mid=${match.get("mid")?.asString}): ${e.message}")
            null
        }
    }

    /**
     * 构建球队信息：新浪不提供队徽，用 ESPN CDN 队徽兜底
     */
    private fun buildTeam(teamObj: JsonObject?, chineseName: String): NBAGame.Team {
        val abbr = DataSourceCommon.chineseNameToAbbr[chineseName] ?: ""
        return NBAGame.Team(
            id = teamObj?.get("tid")?.asString ?: "",
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
 * 解析 JSONP 响应：try{cb({...});}catch(e){}; -> JSON（文件级共享函数）
 *
 * 注意第一个 '{' 属于 try 块（try{cb(...），需先定位回调函数的 '(' 再取其后的 '{'
 */
private fun unwrapJsonp(text: String): String? {
    val paren = text.indexOf('(')
    val start = text.indexOf('{', (paren + 1).coerceAtLeast(0))
    if (start < 0) return null

    // 按大括号配对找出第一个完整 JSON 对象（忽略字符串内括号的极端情况，本接口不涉及）
    var depth = 0
    for (i in start until text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}

/**
 * 新浪文字转播数据源（中文解说）
 *
 * 参考开源项目 NBA-master 的三步链路（接口 2026-09 实测仍存活，但直播间仅对
 * 临近开赛/进行中的比赛存在，历史与休赛期比赛会返回"没有建立直播间"）：
 * 1. boxscore:  https://slamdunk.sports.sina.com.cn/api?p=radar&s=boxscore&a=match&mid={mid}&dpc=1
 *               -> result.data.livecast_id
 * 2. live/room: http://rapid.sports.sina.com.cn/live/api/live/room?match_id={livecast_id}&dpc=1
 *               -> result.data.room_id（status.code=301 表示没有建立直播间）
 * 3. msg/index: http://rapid.sports.sina.com.cn/live/api/msg/index?room_id={room_id}&count=100&msg_id=&direct=-1&dpc=1
 *               -> result.data[] 消息列表（text=解说文字、liver.nickname=解说员、match.phase=节次）
 *
 * mid 即 SinaScoreDataSource 返回的 gameId，链路内自洽；不提供比赛详情（getGameDetail 恒失败）。
 */
class SinaPlayByPlayDataSource : GameDetailDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson

    override fun getGameDetail(gameId: String): Result<GameDetail> {
        return try {
            // boxscore 接口（与文字转播第一步相同），含比分、状态、每节比分
            val root = fetchJson(
                "https://slamdunk.sports.sina.com.cn/api?p=radar&s=boxscore&a=match&callback=cb&mid=$gameId&dpc=1"
            ) ?: return fail("新浪比赛详情请求失败")

            val statusCode = root.getAsJsonObject("result")
                ?.getAsJsonObject("status")?.get("code")?.asInt ?: -1
            if (statusCode != 0) {
                // 无效 mid（如 ESPN 的 gameId）会返回 code=11 param error，正常走降级链
                return fail("新浪：无该比赛详情 (code=$statusCode)")
            }
            val data = root.getAsJsonObject("result")?.get("data")
            if (data == null || !data.isJsonObject) {
                return fail("新浪：无该比赛详情")
            }
            val box = data.asJsonObject

            // 新浪对无效 mid 返回 code=0 但 data 为空壳（mid/队名全空），
            // 必须判空失败以走降级/反查链，否则脏数据会污染上层状态判断
            val boxMid = box.get("mid")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            if (boxMid.isBlank()) {
                return fail("新浪：无该比赛详情 (空数据)")
            }

            val statusEn = box.get("status_en")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val status = when (statusEn) {
                "inprogress", "halftime" -> "in_progress"
                "closed" -> "finished"
                else -> "scheduled"
            }
            val quarter = box.get("quarter")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0
            val clock = box.get("clock")?.takeIf { it.isJsonPrimitive }?.asString ?: ""

            val homeName = DataSourceCommon.normalizeChineseTeamName(
                box.get("home_name")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            )
            val awayName = DataSourceCommon.normalizeChineseTeamName(
                box.get("away_name")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            )

            // 每节比分 score1-8 放入 statistics，供 UI 展示（空字符串跳过）
            val homeStats = mutableMapOf<String, String>()
            val awayStats = mutableMapOf<String, String>()
            for (i in 1..8) {
                val label = if (i <= 4) "第${i}节" else "加时${i - 4}"
                val homeVal = box.get("home_score$i")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                val awayVal = box.get("away_score$i")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                if (homeVal.isNotBlank()) homeStats[label] = homeVal
                if (awayVal.isNotBlank()) awayStats[label] = awayVal
            }

            fun buildTeam(name: String, tidKey: String, scoreKey: String, stats: Map<String, String>): GameDetail.TeamDetail {
                val abbr = DataSourceCommon.chineseNameToAbbr[name] ?: ""
                return GameDetail.TeamDetail(
                    id = box.get(tidKey)?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    name = name,
                    abbreviation = abbr,
                    logo = DataSourceCommon.espnTeamLogo(abbr),
                    score = box.get(scoreKey)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0,
                    statistics = stats
                )
            }

            val detail = GameDetail(
                gameId = gameId,
                status = status,
                clock = if (status == "in_progress") clock else "",
                period = quarter,
                homeTeam = buildTeam(homeName, "home_tid", "home_score", homeStats),
                awayTeam = buildTeam(awayName, "away_tid", "away_score", awayStats),
                players = null  // 新浪 boxscore 无球员统计，UI 隐藏球员表格即可
            )
            DataSourceCommon.debugLog("新浪比赛详情解析成功: $awayName @ $homeName (${detail.awayTeam.score}-${detail.homeTeam.score})")
            Result.success(detail)
        } catch (e: Exception) {
            DataSourceCommon.debugLog("新浪比赛详情异常: ${e.message}")
            Result.failure(e)
        }
    }

    override fun getPlayByPlay(gameId: String, homeTeamId: String, awayTeamId: String): Result<PlayByPlay> {
        return try {
            // 第一步：mid -> livecast_id
            val livecastId = fetchJson(
                "https://slamdunk.sports.sina.com.cn/api?p=radar&s=boxscore&a=match&callback=cb&mid=$gameId&dpc=1"
            )?.getAsJsonObject("result")?.getAsJsonObject("data")
                ?.get("livecast_id")?.takeIf { it.isJsonPrimitive }?.asString
            if (livecastId.isNullOrBlank()) {
                return fail("新浪：该比赛无文字直播数据")
            }
            DataSourceCommon.debugLog("新浪直播 step1: mid -> livecast_id=$livecastId")

            // 第二步：livecast_id -> room_id（无直播间时 status.code=301 且 data 为数组）
            val roomRoot = fetchJson(
                "http://rapid.sports.sina.com.cn/live/api/live/room?callback=cb&match_id=$livecastId&dpc=1"
            ) ?: return fail("新浪直播间查询失败")
            val statusCode = roomRoot.getAsJsonObject("result")
                ?.getAsJsonObject("status")?.get("code")?.asInt ?: -1
            if (statusCode != 0) {
                return fail("新浪：该比赛没有建立文字直播间（未开赛或直播数据已过期）")
            }
            val dataElem = roomRoot.getAsJsonObject("result")?.get("data")
            val roomId = if (dataElem != null && dataElem.isJsonObject) {
                dataElem.asJsonObject.get("room_id")?.takeIf { it.isJsonPrimitive }?.asString
            } else {
                null
            } ?: return fail("新浪：该比赛没有建立文字直播间（未开赛或直播数据已过期）")
            DataSourceCommon.debugLog("新浪直播 step2: livecast_id -> room_id=$roomId")

            // 第三步：room_id -> 解说消息列表
            val msgRoot = fetchJson(
                "http://rapid.sports.sina.com.cn/live/api/msg/index?callback=cb&room_id=$roomId&count=100&msg_id=&direct=-1&dpc=1"
            ) ?: return fail("新浪文字直播消息请求失败")
            val dataArr = msgRoot.getAsJsonObject("result")?.get("data")
            val plays = if (dataArr != null && dataArr.isJsonArray) {
                dataArr.asJsonArray.mapNotNull { parseMessage(it.asJsonObject) }
            } else {
                emptyList()
            }
            DataSourceCommon.debugLog("新浪直播 step3: room_id=$roomId 取得 ${plays.size} 条解说")

            Result.success(PlayByPlay(gameId, plays))
        } catch (e: Exception) {
            DataSourceCommon.debugLog("新浪文字转播异常: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * GET 请求并解析 JSONP 为 JsonObject，失败返回 null
     */
    private fun fetchJson(url: String): JsonObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
                .header("Referer", "https://sports.sina.com.cn/")
                .header("Accept", "*/*")
                .build()

            client.newCall(request).execute().use { response ->
                DataSourceCommon.debugLog("新浪直播 HTTP ${response.code}: $url")
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                unwrapJsonp(body)?.let { gson.fromJson(it, JsonObject::class.java) }
            }
        } catch (e: Exception) {
            DataSourceCommon.debugLog("新浪直播请求异常: ${e.message}")
            null
        }
    }

    /**
     * 单条解说消息 -> Play
     */
    private fun parseMessage(msg: JsonObject): PlayByPlay.Play? {
        return try {
            val matchInfo = msg.getAsJsonObject("match")
            val phase = matchInfo?.get("phase")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val text = msg.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val ctime = msg.get("ctime")?.asLong ?: 0L
            val nickname = msg.getAsJsonObject("liver")
                ?.get("nickname")?.takeIf { it.isJsonPrimitive }?.asString ?: ""

            val period = Regex("""第(\d+)节""").find(phase)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (phase.contains("加时")) 5 else 0

            PlayByPlay.Play(
                id = msg.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                sequenceNumber = ctime.toString(),
                text = text,
                shortText = text.take(20),
                clock = formatBeijingTime(ctime),
                period = period,
                periodDisplay = phase.ifEmpty { "实时解说" },
                teamId = "",
                teamName = "",
                awayScore = matchInfo?.get("score1")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0,
                homeScore = matchInfo?.get("score2")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0,
                isScoringPlay = phase.isNotBlank(),
                scoreValue = 0,
                isShootingPlay = false,
                playType = nickname,  // 解说员昵称显示在文字下方小字
                participants = emptyList()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Unix 秒 -> 北京时间 HH:mm:ss
     */
    private fun formatBeijingTime(epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        return Instant.ofEpochSecond(epochSeconds)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }

    private fun <T> fail(message: String): Result<T> {
        DataSourceCommon.debugLog("新浪文字转播: $message")
        return Result.failure(Exception(message))
    }
}

/**
 * 按「美东日期 + 两队缩写」反查新浪 mid（跨源自 ESPN eventId 用），失败返回 null
 *
 * 复用按日赛程接口：slamdunk.../schedule&a=day&date={北京日期}，result.data.matchs[] 含 mid/date/time/队名
 */
internal fun findSinaMid(easternDate: LocalDate, homeAbbr: String, awayAbbr: String): String? {
    return try {
        val home = DataSourceCommon.abbrToChineseName[homeAbbr.uppercase()] ?: return null
        val away = DataSourceCommon.abbrToChineseName[awayAbbr.uppercase()] ?: return null
        val targets = setOf(home, away)

        // 新浪按北京日期组织比赛：美东日期 D 的比赛位于北京日期 D 与 D+1
        val matchs = (fetchSinaDayMatchs(easternDate) + fetchSinaDayMatchs(easternDate.plusDays(1)))
            .filter { sinaToEasternDate(it.start) == easternDate }

        DataSourceCommon.debugLog("新浪反查: 美东$easternDate 候选 ${matchs.size} 场, 目标=$away@$home")
        matchs.firstOrNull { entry ->
            setOf(
                DataSourceCommon.normalizeChineseTeamName(entry.homeName),
                DataSourceCommon.normalizeChineseTeamName(entry.awayName)
            ) == targets
        }?.mid
    } catch (e: Exception) {
        DataSourceCommon.debugLog("新浪反查异常: ${e.message}")
        null
    }
}

/** 反查用轻量比赛条目：(北京开始时间 "yyyy-MM-dd HH:mm", mid, 主队名, 客队名) */
private data class SinaMatchEntry(val start: String, val mid: String, val homeName: String, val awayName: String)

/**
 * 请求新浪某北京日期的 NBA 比赛条目列表，失败返回空列表
 */
private fun fetchSinaDayMatchs(beijingDate: LocalDate): List<SinaMatchEntry> {
    return try {
        val dateStr = beijingDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val url = "https://slamdunk.sports.sina.com.cn/api?p=radar&s=schedule&a=day&date=$dateStr&callback=dayCallback"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
            .header("Referer", "https://sports.sina.com.cn/")
            .header("Accept", "*/*")
            .build()

        val client = DataSourceCommon.client
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val json = unwrapJsonp(body)?.let { DataSourceCommon.gson.fromJson(it, JsonObject::class.java) }
                ?: return emptyList()
            val matchs = json.getAsJsonObject("result")?.getAsJsonObject("data")?.getAsJsonArray("matchs")
                ?: return emptyList()

            matchs.mapNotNull { element ->
                val match = element.asJsonObject
                val mid = match.get("mid")?.asString ?: return@mapNotNull null
                val date = match.get("date")?.asString ?: ""
                val time = match.get("time")?.asString ?: ""
                val homeName = match.getAsJsonObject("home")?.get("name")?.asString ?: ""
                val awayName = match.getAsJsonObject("away")?.get("name")?.asString ?: ""
                SinaMatchEntry("$date $time", mid, homeName, awayName)
            }
        }
    } catch (e: Exception) {
        DataSourceCommon.debugLog("新浪反查列表请求失败($beijingDate): ${e.message}")
        emptyList()
    }
}

/**
 * 北京时间字符串 -> 美东日期（文件级，供反查使用）
 */
private fun sinaToEasternDate(beijingStart: String): LocalDate? {
    return try {
        val ldt = LocalDateTime.parse(beijingStart.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        ldt.atZone(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneId.of("America/New_York"))
            .toLocalDate()
    } catch (e: Exception) {
        null
    }
}
