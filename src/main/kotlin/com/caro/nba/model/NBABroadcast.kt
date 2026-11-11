package com.caro.nba.model

/**
 * 转播渠道模型（腾讯体育赛程聚合 + 官方平台静态入口）
 */
data class NBABroadcast(
    val mid: String,              // 腾讯比赛 id（"100000:10022600203"）
    val homeName: String,         // 主队中文名
    val awayName: String,         // 客队中文名
    val startTimeBeijing: String, // 北京开球时间 "yyyy-MM-dd HH:mm:ss"
    val matchDesc: String,        // "NBA常规赛"
    val statusText: String,       // "第4节"/"完赛"/""（未开赛）
    val isGold: Boolean,          // 金牌场（腾讯重点场次，通常需会员）
    val hasPlayback: Boolean,     // 有回放
    /** 腾讯本场是否免费直播（multiUpdate 详情获取；null=未知/查询失败） */
    val isPay: Boolean? = null,
    /** 直播吧首页渠道标注原文（如 "CCTV5 腾讯视频 全美直播"） */
    val channelText: String = ""
) {
    /** 是否有 CCTV5 电视转播（免费） */
    fun hasCctv5(): Boolean = channelText.contains("CCTV5")

    /** PC 版比赛详情页（官方页面，含直播/回放入口） */
    fun gamePageUrl(): String = "https://sports.qq.com/kbsweb/game.htm?mid=$mid"

    /** 是否与指定两队（中文名）匹配（不分主客） */
    fun matches(teamA: String, teamB: String): Boolean {
        val set = setOf(teamA, teamB)
        return set.contains(homeName) && set.contains(awayName)
    }
}
