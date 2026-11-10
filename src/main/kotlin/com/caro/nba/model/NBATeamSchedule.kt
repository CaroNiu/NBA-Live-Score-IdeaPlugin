package com.caro.nba.model

/**
 * 球队赛程（ESPN teams/{abbr}/schedule）
 */
data class NBATeamSchedule(
    val teamName: String,      // 中文队名（走 teamNameMap 映射）
    val teamAbbr: String,      // 如 LAL
    val teamLogo: String,
    val recentGames: List<ScheduleGame>,   // 已打完，最新在前（最多 5 场）
    val upcomingGames: List<ScheduleGame>  // 未开赛，最近在前（最多 5 场）
) {
    /**
     * 一场赛程条目
     */
    data class ScheduleGame(
        val dateIso: String,       // ISO 时间（UTC）
        val opponentName: String,  // 对手中文名
        val isHome: Boolean,       // 本队是否主场
        val played: Boolean,
        val win: Boolean = false,
        val scoreDisplay: String = ""  // 已打完：本队-对手 比分
    )
}
