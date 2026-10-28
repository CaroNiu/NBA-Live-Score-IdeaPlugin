package com.caro.nba.model

/**
 * 比赛详情数据模型
 */
data class GameDetail(
    val gameId: String,
    val status: String,
    val clock: String = "",
    val period: Int = 0,
    val venue: Venue? = null,
    val homeTeam: TeamDetail,
    val awayTeam: TeamDetail,
    val players: PlayerStats? = null,
    val highlights: List<Highlight> = emptyList(),
    val news: List<GameNews> = emptyList()
) {
    data class Venue(
        val name: String,
        val city: String
    )

    data class TeamDetail(
        val id: String,
        val name: String,
        val abbreviation: String,
        val logo: String,
        val score: Int = 0,
        val statistics: Map<String, String> = emptyMap(),
        val leaders: List<TeamLeader> = emptyList(),
        val record: String = ""
    )

    data class TeamLeader(
        val category: String,  // points, assists, rebounds
        val playerName: String,
        val playerJersey: String,
        val playerHeadshot: String,
        val value: String
    )

    data class PlayerStats(
        val homePlayers: List<Player>,
        val awayPlayers: List<Player>,
        /** 球队总计行（直播吧提供，ESPN 无；null 不展示） */
        val homeTotals: Player? = null,
        val awayTotals: Player? = null
    )

    data class Player(
        val id: String,
        val name: String,
        val jersey: String,
        val position: String,
        val minutes: String = "0",
        val points: String = "0",
        val rebounds: String = "0",
        val offensiveRebounds: String = "",  // 前篮板（直播吧提供，ESPN 无，空则表格显示 -）
        val defensiveRebounds: String = "",  // 后篮板
        val assists: String = "0",
        val steals: String = "0",
        val blocks: String = "0",
        val turnovers: String = "0",
        val fouls: String = "",              // 犯规（直播吧提供）
        val fgMade: String = "0",
        val fgAttempts: String = "0",
        val threeMade: String = "0",
        val threeAttempts: String = "0",
        val ftMade: String = "0",
        val ftAttempts: String = "0",
        val plusMinus: String = "0",
        val headshot: String = "",
        val playerUrl: String = ""
    )

    data class Highlight(
        val id: String,
        val title: String,
        val description: String,
        val thumbnailUrl: String,
        val videoUrl: String
    )

    data class GameNews(
        val id: String,
        val title: String,
        val description: String,
        val imageUrl: String,
        val published: String,
        val link: String
    )
}