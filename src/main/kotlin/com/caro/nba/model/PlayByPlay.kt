package com.caro.nba.model

/**
 * 实时文字转播数据模型
 */
data class PlayByPlay(
    val gameId: String,
    val plays: List<Play>
) {
    data class Play(
        val id: String,
        val sequenceNumber: String,
        val text: String,                    // 事件描述
        val shortText: String,               // 简短描述
        val clock: String,                   // 比赛时间 "11:43"
        val period: Int,                     // 节数 1-4 (OT为5+)
        val periodDisplay: String,           // "1st Quarter"
        val teamId: String,                  // 球队ID
        val teamName: String = "",           // 球队名称（需要映射）
        val teamLogo: String = "",           // 球队logo
        val awayScore: Int,                  // 客队比分
        val homeScore: Int,                  // 主队比分
        val isScoringPlay: Boolean,          // 是否得分
        val scoreValue: Int,                 // 得分值
        val isShootingPlay: Boolean,         // 是否投篮
        val playType: String,                // 事件类型
        val participants: List<String> = emptyList()  // 参与球员
    )

    /**
     * 一条直播吧弹幕（只读展示，不提供发送）
     */
    data class Danmu(
        val orderId: Long,     // 全局递增序号（增量拉取游标）
        val content: String,   // 弹幕文本
        val time: String       // 发布时间 "yyyy-MM-dd HH:mm:ss"
    )
}
