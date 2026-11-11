package com.caro.nba.datasource.impl

import com.caro.nba.datasource.DataSourceCommon
import com.caro.nba.datasource.GameDetailDataSource
import com.caro.nba.datasource.ScoreDataSource
import com.caro.nba.datasource.StandingsDataSource
import com.caro.nba.model.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ESPN 数据源实现
 *
 * ESPN 有四个公开 API 端点：
 * - 比分：    {host}/apis/site/v2/sports/basketball/nba/scoreboard
 * - 排名：    {host}/apis/v2/sports/basketball/nba/standings
 * - 比赛详情：{host}/apis/site/v2/sports/basketball/nba/summary
 *
 * host 有两个：site.api.espn.com（主站 CDN，部分网络环境返回 403）
 * 和 site.web.api.espn.com（web 端 CDN，通常可达），请求时依次尝试。
 *
 * ESPN 没有直接的季后赛对阵图 API，需要通过 standings + events 自行组装。
 */

/** ESPN 双 host：web 端 CDN 可达性更好，放前面优先尝试 */
private val ESPN_HOSTS = listOf("https://site.web.api.espn.com", "https://site.api.espn.com")
class EspnScoreDataSource : ScoreDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson
    private val teamNameMap = DataSourceCommon.teamNameMap

    override fun getScores(date: LocalDate): Result<NBAScoreboard> {
        val dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        var lastFailure: Result<NBAScoreboard>? = null
        for (host in ESPN_HOSTS) {
            val result = fetchScoreboard("$host/apis/site/v2/sports/basketball/nba/scoreboard?dates=$dateStr", date)
            if (result.isSuccess) return result
            lastFailure = result
        }
        return lastFailure ?: Result.failure(Exception("ESPN API请求失败"))
    }

    private fun fetchScoreboard(url: String, date: LocalDate): Result<NBAScoreboard> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://www.espn.com")
                .header("Referer", "https://www.espn.com/")
                .build()

            client.newCall(request).execute().use { response ->
                DataSourceCommon.debugLog("ESPN 响应: HTTP ${response.code} ($url)")
                if (!response.isSuccessful) {
                    return Result.failure(Exception("ESPN API请求失败: ${response.code}"))
                }

                val body = response.body?.string() ?: return Result.failure(Exception("ESPN响应为空"))
                val scoreboard = parseScoreboard(body, date)
                DataSourceCommon.debugLog("ESPN 解析: ${scoreboard.games.size} 场比赛")
                Result.success(scoreboard)
            }
        } catch (e: Exception) {
            DataSourceCommon.debugLog("ESPN 异常: ${e.message}")
            Result.failure(e)
        }
    }

    private fun parseScoreboard(json: String, date: LocalDate): NBAScoreboard {
        val root = gson.fromJson(json, JsonObject::class.java)
        val events = root.getAsJsonArray("events") ?: return NBAScoreboard(date.toString(), emptyList())

        val games = events.map { event ->
            val eventObj = event.asJsonObject

            val status = eventObj.getAsJsonObject("status")
            val type = status?.getAsJsonObject("type")
            val state = type?.get("state")?.asString ?: "pre"
            val period = type?.get("period")?.asInt ?: 0
            val displayClock = status?.get("displayClock")?.asString ?: ""
            val detail = type?.get("detail")?.asString ?: ""

            val competitions = eventObj.getAsJsonArray("competitions")
            val competition = competitions?.get(0)?.asJsonObject
            val competitors = competition?.getAsJsonArray("competitors") ?: return@map null

            val homeCompetitor = competitors.find {
                it.asJsonObject.get("homeAway")?.asString == "home"
            }?.asJsonObject
            val awayCompetitor = competitors.find {
                it.asJsonObject.get("homeAway")?.asString == "away"
            }?.asJsonObject

            val homeTeam = homeCompetitor?.getAsJsonObject("team")
            val awayTeam = awayCompetitor?.getAsJsonObject("team")

            val homeScore = homeCompetitor?.get("score")?.asString?.toIntOrNull() ?: 0
            val awayScore = awayCompetitor?.get("score")?.asString?.toIntOrNull() ?: 0

            val homeLogo = homeTeam?.get("logo")?.asString ?: ""
            val awayLogo = awayTeam?.get("logo")?.asString ?: ""

            val homeShortName = homeTeam?.get("shortDisplayName")?.asString ?: ""
            val awayShortName = awayTeam?.get("shortDisplayName")?.asString ?: ""
            val homeAbbr = homeTeam?.get("abbreviation")?.asString ?: ""
            val awayAbbr = awayTeam?.get("abbreviation")?.asString ?: ""

            NBAGame(
                gameId = eventObj.get("id")?.asString ?: "",
                status = when (state) {
                    "pre" -> "scheduled"
                    "in" -> "in_progress"
                    "post" -> "finished"
                    else -> state
                },
                period = period,
                clock = displayClock,
                startTime = eventObj.get("date")?.asString ?: "",
                detail = detail,
                homeTeam = NBAGame.Team(
                    id = homeTeam?.get("id")?.asString ?: "",
                    name = teamNameMap[homeShortName] ?: homeShortName,
                    abbreviation = homeAbbr,
                    shortName = homeShortName,
                    logo = homeLogo
                ),
                homeScore = homeScore,
                awayTeam = NBAGame.Team(
                    id = awayTeam?.get("id")?.asString ?: "",
                    name = teamNameMap[awayShortName] ?: awayShortName,
                    abbreviation = awayAbbr,
                    shortName = awayShortName,
                    logo = awayLogo
                ),
                awayScore = awayScore
            )
        }.filterNotNull()

        return NBAScoreboard(date.toString(), games)
    }
}

class EspnStandingsDataSource : StandingsDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson
    private val teamNameMap = DataSourceCommon.teamNameMap

    override fun getStandings(): Result<NBAStandings> {
        var lastFailure: Result<NBAStandings>? = null
        for (host in ESPN_HOSTS) {
            val result = fetchStandings("$host/apis/v2/sports/basketball/nba/standings")
            if (result.isSuccess) return result
            lastFailure = result
        }
        return lastFailure ?: Result.failure(Exception("ESPN API请求失败"))
    }

    private fun fetchStandings(url: String): Result<NBAStandings> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://www.espn.com")
                .header("Referer", "https://www.espn.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(Exception("ESPN API请求失败: ${response.code}"))
                }

                val body = response.body?.string() ?: return Result.failure(Exception("ESPN响应为空"))
                val standings = parseStandings(body)
                Result.success(standings)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseStandings(json: String): NBAStandings {
        val root = gson.fromJson(json, JsonObject::class.java)
        val children = root.getAsJsonArray("children") ?: return createEmptyStandings()

        var easternTeams = listOf<TeamStanding>()
        var westernTeams = listOf<TeamStanding>()

        children.forEach { child ->
            val conf = child.asJsonObject
            val confName = conf.get("name")?.asString ?: ""
            val entries = conf.getAsJsonObject("standings")?.getAsJsonArray("entries") ?: return@forEach

            val teams = entries.mapIndexed { index, entry ->
                parseTeamStanding(entry.asJsonObject, index + 1)
            }.sortedBy { it.conferenceRank }

            when (confName) {
                "Eastern Conference" -> easternTeams = teams
                "Western Conference" -> westernTeams = teams
            }
        }

        return NBAStandings(
            eastern = ConferenceStandings("East", easternTeams),
            western = ConferenceStandings("West", westernTeams),
            lastUpdated = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        ).also {
            DataSourceCommon.debugLog("ESPN 排名解析: 东部 ${easternTeams.size} 队, 西部 ${westernTeams.size} 队")
        }
    }

    private fun parseTeamStanding(entry: JsonObject, defaultRank: Int): TeamStanding {
        val team = entry.getAsJsonObject("team")
        val stats = entry.getAsJsonArray("stats")

        val teamId = team.get("id")?.asString ?: ""
        val abbreviation = team.get("abbreviation")?.asString ?: ""
        val shortName = team.get("shortDisplayName")?.asString ?: ""
        val logo = team.getAsJsonArray("logos")?.get(0)?.asJsonObject?.get("href")?.asString ?: ""

        val statsMap = mutableMapOf<String, String>()
        stats?.forEach { stat ->
            val statObj = stat.asJsonObject
            val name = statObj.get("name")?.asString ?: ""
            val displayValue = statObj.get("displayValue")?.asString ?: ""
            statsMap[name] = displayValue
        }

        val wins = statsMap["wins"]?.toIntOrNull() ?: 0
        val losses = statsMap["losses"]?.toIntOrNull() ?: 0
        val winPercent = statsMap["winPercent"]?.toDoubleOrNull() ?: 0.0
        val gamesBehind = statsMap["gamesBehind"]?.ifEmpty { "-" } ?: "-"
        val streak = statsMap["streak"] ?: ""
        val clincher = statsMap["clincher"] ?: ""
        // 新赛季 0-0 时 playoffSeed 为 0（无效），回退到 entries 顺序作为排名
        val playoffSeed = statsMap["playoffSeed"]?.toIntOrNull()?.takeIf { it in 1..30 } ?: defaultRank

        val overallRecord = stats?.find {
            it.asJsonObject.get("name")?.asString == "overall"
        }?.asJsonObject?.get("summary")?.asString ?: "$wins-$losses"

        val homeRecord = stats?.find {
            it.asJsonObject.get("name")?.asString == "home"
        }?.asJsonObject?.get("summary")?.asString ?: "0-0"

        val awayRecord = stats?.find {
            it.asJsonObject.get("name")?.asString == "road"
        }?.asJsonObject?.get("summary")?.asString ?: "0-0"

        val last10 = stats?.find {
            it.asJsonObject.get("name")?.asString == "lasttengames"
        }?.asJsonObject?.get("summary")?.asString ?: "0-0"

        return TeamStanding(
            teamId = teamId,
            teamName = teamNameMap[shortName] ?: shortName,
            abbreviation = abbreviation,
            logo = logo,
            wins = wins,
            losses = losses,
            winPercent = winPercent,
            gamesBehind = gamesBehind,
            homeRecord = homeRecord,
            awayRecord = awayRecord,
            last10 = last10,
            streak = streak,
            conferenceRank = playoffSeed,
            clincher = clincher
        )
    }

    private fun createEmptyStandings(): NBAStandings {
        return NBAStandings(
            eastern = ConferenceStandings("East", emptyList()),
            western = ConferenceStandings("West", emptyList()),
            lastUpdated = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        )
    }
}

class EspnGameDetailDataSource : GameDetailDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson
    private val teamNameMap = DataSourceCommon.teamNameMap

    override fun getGameDetail(gameId: String): Result<GameDetail> {
        var lastFailure: Result<GameDetail>? = null
        for (host in ESPN_HOSTS) {
            val result = fetchSummary("$host/apis/site/v2/sports/basketball/nba/summary?event=$gameId") { json ->
                parseGameDetail(json, gameId)
            }
            if (result.isSuccess) return result
            lastFailure = result
        }
        return lastFailure ?: Result.failure(Exception("ESPN API请求失败"))
    }

    private inline fun <reified T> fetchSummary(url: String, parser: (String) -> T): Result<T> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://www.espn.com")
                .header("Referer", "https://www.espn.com/")
                .build()

            client.newCall(request).execute().use { response ->
                DataSourceCommon.debugLog("ESPN 响应: HTTP ${response.code} ($url)")
                if (!response.isSuccessful) {
                    return Result.failure(Exception("ESPN API请求失败: ${response.code}"))
                }

                val body = response.body?.string() ?: return Result.failure(Exception("ESPN响应为空"))
                Result.success(parser(body))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ESPN 球队 ID 到球队名称和缩写的映射
    private val teamIdMap = mapOf(
        "1" to Pair("Warriors", "GSW"),
        "2" to Pair("Lakers", "LAL"),
        "3" to Pair("Heat", "MIA"),
        "4" to Pair("Suns", "PHX"),
        "5" to Pair("Spurs", "SAS"),
        "6" to Pair("Bulls", "CHI"),
        "7" to Pair("Cavaliers", "CLE"),
        "8" to Pair("Mavericks", "DAL"),
        "9" to Pair("Nets", "BKN"),
        "10" to Pair("Knicks", "NYK"),
        "11" to Pair("Magic", "ORL"),
        "12" to Pair("76ers", "PHI"),
        "14" to Pair("Kings", "SAC"),
        "15" to Pair("Hornets", "CHA"),
        "16" to Pair("Celtics", "BOS"),
        "17" to Pair("Clippers", "LAC"),
        "18" to Pair("Raptors", "TOR"),
        "19" to Pair("Rockets", "HOU"),
        "20" to Pair("Nuggets", "DEN"),
        "21" to Pair("Timberwolves", "MIN"),
        "22" to Pair("Grizzlies", "MEM"),
        "23" to Pair("Pelicans", "NOP"),
        "24" to Pair("Thunder", "OKC"),
        "25" to Pair("Pacers", "IND"),
        "27" to Pair("Bucks", "MIL"),
        "28" to Pair("Hawks", "ATL"),
        "29" to Pair("Wizards", "WAS"),
        "30" to Pair("Jazz", "UTA"),
        "38" to Pair("Trail Blazers", "POR")
    )

    private fun parseGameDetail(json: String, gameId: String): GameDetail {
        val root = gson.fromJson(json, JsonObject::class.java)

        val gameInfo = root.getAsJsonObject("gameInfo")
        val venueObj = gameInfo?.getAsJsonObject("venue")
        val venue = venueObj?.let {
            GameDetail.Venue(
                name = it.get("fullName")?.asString ?: "",
                city = it.getAsJsonObject("address")?.get("city")?.asString ?: ""
            )
        }

        val header = root.getAsJsonObject("header")
        val competitions = header?.getAsJsonArray("competitions")
        val competition = competitions?.get(0)?.asJsonObject
        val competitors = competition?.getAsJsonArray("competitors")

        var homeTeam: GameDetail.TeamDetail? = null
        var awayTeam: GameDetail.TeamDetail? = null
        var status = "scheduled"
        var period = 0
        var statusDetail = ""

        val statusObj = competition?.getAsJsonObject("status")
        val type = statusObj?.getAsJsonObject("type")
        val state = type?.get("state")?.asString ?: "pre"
        statusDetail = type?.get("detail")?.asString ?: ""
        period = type?.get("period")?.asInt ?: 0

        status = when (state) {
            "pre" -> "scheduled"
            "in" -> "in_progress"
            "post" -> "finished"
            else -> state
        }

        competitors?.forEach { compElement ->
            val compObj = compElement.asJsonObject
            val isHome = compObj.get("homeAway")?.asString == "home"
            val team = compObj.getAsJsonObject("team")
            val shortDisplayName = team.get("shortDisplayName")?.asString ?: ""
            val score = compObj.get("score")?.asString?.toIntOrNull() ?: 0

            // 每节得分：header.competitions[0].competitors[].linescores[].value
            val lineScores = compObj.getAsJsonArray("linescores")?.mapNotNull { ls ->
                ls.asJsonObject.get("value")?.takeIf { it.isJsonPrimitive }?.let { it.asInt.toString() }
            } ?: emptyList()

            val teamDetail = GameDetail.TeamDetail(
                id = team.get("id")?.asString ?: "",
                name = teamNameMap[shortDisplayName] ?: shortDisplayName,
                abbreviation = team.get("abbreviation")?.asString ?: "",
                logo = team.getAsJsonArray("logos")?.get(0)?.asJsonObject?.get("href")?.asString ?: "",
                score = score,
                statistics = emptyMap(),
                leaders = emptyList(),
                lineScores = lineScores
            )

            if (isHome) homeTeam = teamDetail else awayTeam = teamDetail
        }

        // 解析球员领袖
        val leadersArray = root.getAsJsonArray("leaders") ?: emptyList()
        val homeLeaders = mutableListOf<GameDetail.TeamLeader>()
        val awayLeaders = mutableListOf<GameDetail.TeamLeader>()

        leadersArray.forEach { teamLeadersElement ->
            val teamLeadersObj = teamLeadersElement.asJsonObject
            val teamId = teamLeadersObj.getAsJsonObject("team")?.get("id")?.asString
            val isHomeTeam = teamId == homeTeam?.id

            teamLeadersObj.getAsJsonArray("leaders")?.forEach { categoryElement ->
                val categoryObj = categoryElement.asJsonObject
                val categoryName = categoryObj.get("displayName")?.asString ?: ""

                categoryObj.getAsJsonArray("leaders")?.firstOrNull()?.let { playerElement ->
                    val playerObj = playerElement.asJsonObject
                    val athlete = playerObj.getAsJsonObject("athlete")

                    val leader = GameDetail.TeamLeader(
                        category = categoryName,
                        playerName = athlete?.get("fullName")?.asString ?: "",
                        playerJersey = athlete?.get("jersey")?.asString ?: "",
                        playerHeadshot = athlete?.getAsJsonObject("headshot")?.get("href")?.asString ?: "",
                        value = playerObj.get("displayValue")?.asString ?: ""
                    )

                    if (isHomeTeam) homeLeaders.add(leader) else awayLeaders.add(leader)
                }
            }
        }

        homeTeam = homeTeam?.copy(leaders = homeLeaders)
        awayTeam = awayTeam?.copy(leaders = awayLeaders)

        // 解析球员统计
        val boxscore = root.getAsJsonObject("boxscore")
        val playersArray = boxscore?.getAsJsonArray("players") ?: emptyList()

        val homePlayers = mutableListOf<GameDetail.Player>()
        val awayPlayers = mutableListOf<GameDetail.Player>()

        playersArray.forEach { teamElement ->
            val teamObj = teamElement.asJsonObject
            val teamId = teamObj.getAsJsonObject("team")?.get("id")?.asString
            val isHomeTeam = teamId == homeTeam?.id

            val statistics = teamObj.getAsJsonArray("statistics")
            statistics?.forEach { statBlock ->
                val block = statBlock.asJsonObject
                val labels = block.getAsJsonArray("labels")?.map { it.asString } ?: emptyList()
                val athletes = block.getAsJsonArray("athletes") ?: emptyList()

                athletes.forEach { athleteElement ->
                    val athleteObj = athleteElement.asJsonObject
                    val athleteInfo = athleteObj.getAsJsonObject("athlete")
                    val stats = athleteObj.getAsJsonArray("stats")?.map { it.asString } ?: emptyList()

                    fun getStat(label: String): String {
                        val index = labels.indexOf(label)
                        return if (index >= 0 && index < stats.size) stats[index] else "0"
                    }

                    val player = GameDetail.Player(
                        id = athleteInfo?.get("id")?.asString ?: "",
                        name = athleteInfo?.get("displayName")?.asString ?: "",
                        jersey = athleteInfo?.get("jersey")?.asString ?: "",
                        position = athleteInfo?.getAsJsonObject("position")?.get("abbreviation")?.asString ?: "",
                        minutes = getStat("MIN"),
                        points = getStat("PTS"),
                        rebounds = getStat("REB"),
                        assists = getStat("AST"),
                        steals = getStat("STL"),
                        blocks = getStat("BLK"),
                        turnovers = getStat("TO"),
                        fgMade = getStat("FG").split("-").getOrNull(0) ?: "0",
                        fgAttempts = getStat("FG").split("-").getOrNull(1) ?: "0",
                        threeMade = getStat("3PT").split("-").getOrNull(0) ?: "0",
                        threeAttempts = getStat("3PT").split("-").getOrNull(1) ?: "0",
                        ftMade = getStat("FT").split("-").getOrNull(0) ?: "0",
                        ftAttempts = getStat("FT").split("-").getOrNull(1) ?: "0",
                        plusMinus = getStat("+/-"),
                        headshot = athleteInfo?.getAsJsonObject("headshot")?.get("href")?.asString ?: "",
                        playerUrl = athleteInfo?.getAsJsonArray("links")?.get(0)?.asJsonObject?.get("href")?.asString ?: ""
                    )

                    if (isHomeTeam) homePlayers.add(player) else awayPlayers.add(player)
                }
            }
        }

        return GameDetail(
            gameId = gameId,
            status = status,
            clock = "",
            period = period,
            venue = venue,
            homeTeam = homeTeam ?: GameDetail.TeamDetail("", "", "", ""),
            awayTeam = awayTeam ?: GameDetail.TeamDetail("", "", "", ""),
            players = GameDetail.PlayerStats(homePlayers, awayPlayers),
            highlights = emptyList()
        )
    }

    override fun getPlayByPlay(gameId: String, homeTeamId: String, awayTeamId: String): Result<PlayByPlay> {
        var lastFailure: Result<PlayByPlay>? = null
        for (host in ESPN_HOSTS) {
            val result = fetchSummary("$host/apis/site/v2/sports/basketball/nba/summary?event=$gameId") { json ->
                parsePlayByPlay(json, gameId)
            }
            if (result.isSuccess) return result
            lastFailure = result
        }
        return lastFailure ?: Result.failure(Exception("ESPN API请求失败"))
    }

    private fun parsePlayByPlay(json: String, gameId: String): PlayByPlay {
        val root = gson.fromJson(json, JsonObject::class.java)
        val playsArray = root.getAsJsonArray("plays") ?: JsonArray()

        val plays = playsArray.mapNotNull { playElement ->
            try {
                val play = playElement.asJsonObject

                val teamObj = play.getAsJsonObject("team")
                val teamId = teamObj?.get("id")?.asString ?: ""
                val teamInfo = teamIdMap[teamId] ?: Pair("", "")

                val participantsArray = play.getAsJsonArray("participants")
                val participantNames = participantsArray?.mapNotNull { p ->
                    p.asJsonObject.getAsJsonObject("athlete")?.get("displayName")?.asString
                } ?: emptyList()

                val periodObj = play.getAsJsonObject("period")
                val periodNumber = periodObj?.get("number")?.asInt ?: 1

                val clockObj = play.getAsJsonObject("clock")
                val clockDisplay = clockObj?.get("displayValue")?.asString ?: ""

                val typeObj = play.getAsJsonObject("type")
                val playType = typeObj?.get("text")?.asString ?: ""

                PlayByPlay.Play(
                    id = play.get("id")?.asString ?: "",
                    sequenceNumber = play.get("sequenceNumber")?.asString ?: "",
                    text = play.get("text")?.asString ?: "",
                    shortText = play.get("shortDescription")?.asString ?: "",
                    clock = clockDisplay,
                    period = periodNumber,
                    periodDisplay = periodObj?.get("displayValue")?.asString ?: "",
                    teamId = teamId,
                    teamName = teamInfo.first,
                    awayScore = play.get("awayScore")?.asInt ?: 0,
                    homeScore = play.get("homeScore")?.asInt ?: 0,
                    isScoringPlay = play.get("scoringPlay")?.asBoolean ?: false,
                    scoreValue = play.get("scoreValue")?.asInt ?: 0,
                    isShootingPlay = play.get("shootingPlay")?.asBoolean ?: false,
                    playType = playType,
                    participants = participantNames
                )
            } catch (e: Exception) {
                null
            }
        }

        return PlayByPlay(gameId, plays)
    }
}
