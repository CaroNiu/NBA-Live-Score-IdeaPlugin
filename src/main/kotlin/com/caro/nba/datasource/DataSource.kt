package com.caro.nba.datasource

/**
 * NBA 数据源枚举
 *
 * 覆盖国内外主流免费 NBA 数据源，按推荐优先级排序：
 * 1. ESPN - 最稳定、数据最全、国际访问快
 * 2. NBA.com (stats.nba.com) - 官方数据最权威
 * 3. Ball Don't Lie - 免费开源，社区活跃
 * 4. TheSportsDB - 多体育项目，完全免费
 * 5. 腾讯NBA - 国内访问快、中文队名
 */
enum class DataSource(
    val id: String,
    val displayName: String,
    val description: String,
    val supportsScores: Boolean = true,
    val supportsStandings: Boolean = true,
    val supportsPlayoffs: Boolean = true,
    val supportsGameDetail: Boolean = true,
    val supportsPlayByPlay: Boolean = false,
    val requiresApiKey: Boolean = false
) {
    ESPN(
        id = "espn",
        displayName = "ESPN API",
        description = "ESPN 官方公开 API，数据准确稳定，国际访问快，无需 Key",
        supportsScores = true,
        supportsStandings = true,
        supportsPlayoffs = false,
        supportsGameDetail = true,
        supportsPlayByPlay = true,
        requiresApiKey = false
    ),
    NBA_COM(
        id = "nba_com",
        displayName = "NBA.com 官方",
        description = "stats.nba.com 官方统计 API，数据最权威，国内可能较慢",
        supportsScores = true,
        supportsStandings = true,
        supportsPlayoffs = true,
        supportsGameDetail = true,
        requiresApiKey = false
    ),
    BALL_DONT_LIE(
        id = "ball_dont_lie",
        displayName = "Ball Don't Lie",
        description = "免费开源 NBA API（balldontlie.io），社区维护，需注册 Key",
        supportsScores = true,
        supportsStandings = true,
        supportsPlayoffs = false,
        supportsGameDetail = true,
        supportsPlayByPlay = false,  // 免费版无 play-by-play 端点
        requiresApiKey = true
    ),
    SPORTS_DB(
        id = "sports_db",
        displayName = "TheSportsDB",
        description = "全品类体育免费 API（thesportsdb.com），无需 Key",
        supportsScores = true,
        supportsStandings = true,
        supportsPlayoffs = false,
        supportsGameDetail = false,
        requiresApiKey = false
    ),
    TENCENT(
        id = "tencent",
        displayName = "腾讯NBA（已失效）",
        description = "腾讯体育接口已下线（h5sports.match.qq.com 域名注销），选择后将自动降级到其他源",
        supportsScores = true,
        supportsStandings = true,
        supportsPlayoffs = true,
        supportsGameDetail = false,
        requiresApiKey = false
    ),
    ZHIBO8(
        id = "zhibo8",
        displayName = "直播吧",
        description = "直播吧中文数据（zhibo8.com），国内访问快，中文队名，含比赛详情（中文技术统计）与中文图文直播",
        supportsScores = true,
        supportsStandings = false,
        supportsPlayoffs = false,
        supportsGameDetail = true,
        supportsPlayByPlay = true,
        requiresApiKey = false
    ),
    SINA(
        id = "sina",
        displayName = "新浪NBA",
        description = "新浪体育中文数据，NBA 专属接口，含每节比分、比赛详情与中文文字直播",
        supportsScores = true,
        supportsStandings = false,
        supportsPlayoffs = false,
        supportsGameDetail = true,
        supportsPlayByPlay = true,
        requiresApiKey = false
    );

    companion object {
        fun fromId(id: String?): DataSource {
            return values().find { it.id == id } ?: ESPN
        }
    }
}
