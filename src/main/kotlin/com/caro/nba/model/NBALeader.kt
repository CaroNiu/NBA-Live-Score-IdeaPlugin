package com.caro.nba.model

/**
 * 数据王榜单模型（ESPN 统计数据）
 */
data class LeaderEntry(
    val playerName: String,    // 球员名（ESPN 英文名）
    val displayValue: String   // 数值展示串（如 "33.5"、"45"）
)

/** 一个统计类目榜单（如 得分王） */
data class LeaderCategory(
    val displayName: String,   // 类目中文名（得分 / 篮板 / 助攻）
    val entries: List<LeaderEntry>
)

/** 今日数据王（当日全部比赛的单场最佳聚合；优先直播吧中文数据，降级 ESPN） */
data class TodayLeaders(
    val date: String,
    val categories: List<LeaderCategory>,
    val source: String = "ESPN"   // 数据来源展示名
) {
    fun isEmpty(): Boolean = categories.all { it.entries.isEmpty() }
}

/** 赛季数据王（场均；优先直播吧中文数据，降级 ESPN） */
data class SeasonLeaders(
    val season: String,        // 如 "2026-27"
    val categories: List<LeaderCategory>,
    val source: String = "ESPN"   // 数据来源展示名
) {
    fun isEmpty(): Boolean = categories.all { it.entries.isEmpty() }
}
