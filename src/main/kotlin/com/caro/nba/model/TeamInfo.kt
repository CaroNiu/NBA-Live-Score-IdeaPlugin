package com.caro.nba.model

/**
 * 球队信息（直播吧 nba_v2/team，teamId 与比分条目的 homeId/guestId 同体系）
 *
 * tableSections 为通用表结构（动态表头 + 中文键行对象），防御式渲染：
 * - 阵容表（player.info）：球员/位置/身高/体重/球号/经验/国家/薪资
 * - 球员数据表（player.data）：球员/场数/得分/篮板/助攻/...
 * - 伤病名单（teamInjuries）：球员/伤病/日期/状态
 */
data class TeamInfo(
    val teamName: String,
    val teamNameEn: String,
    val city: String,
    val tableSections: List<TableSection>
)

/** 通用表结构（动态表头 + 按表头顺序取值的行） */
data class TableSection(
    val title: String,
    val headers: List<String>,
    val rows: List<List<String>>
)
