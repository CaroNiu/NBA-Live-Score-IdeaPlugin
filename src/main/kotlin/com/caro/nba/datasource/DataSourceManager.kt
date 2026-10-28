package com.caro.nba.datasource

import com.caro.nba.datasource.impl.BallDontLieGameDetailDataSource
import com.caro.nba.datasource.impl.BallDontLieScoreDataSource
import com.caro.nba.datasource.impl.BallDontLieStandingsDataSource
import com.caro.nba.datasource.impl.EspnGameDetailDataSource
import com.caro.nba.datasource.impl.EspnScoreDataSource
import com.caro.nba.datasource.impl.EspnStandingsDataSource
import com.caro.nba.datasource.impl.NbaComScoreDataSource
import com.caro.nba.datasource.impl.NbaComStandingsDataSource
import com.caro.nba.datasource.impl.TheSportsDbScoreDataSource
import com.caro.nba.datasource.impl.TheSportsDbStandingsDataSource
import com.caro.nba.datasource.impl.Zhibo8GameDetailDataSource
import com.caro.nba.datasource.impl.Zhibo8PlayByPlayDataSource
import com.caro.nba.datasource.impl.Zhibo8ScoreDataSource
import com.caro.nba.datasource.impl.SinaScoreDataSource
import com.caro.nba.datasource.impl.SinaPlayByPlayDataSource
import com.caro.nba.datasource.impl.MockPlayoffDataSource
import com.caro.nba.model.GameDetail
import com.caro.nba.model.NBAScoreboard
import com.caro.nba.model.NBAStandings
import com.caro.nba.model.PlayByPlay
import com.caro.nba.model.PlayoffBracketResponse
import java.time.LocalDate

/**
 * 数据源管理器
 *
 * 职责：
 * 1. 根据当前设置选择主数据源
 * 2. 若主数据源不支持某功能或请求失败，自动 fallback 到备用数据源
 * 3. 对外暴露统一的查询接口
 *
 * 所有支持的数据源（按推荐优先级）：
 * - ESPN（默认，稳定可靠）
 * - NBA.com 官方（stats.nba.com，数据权威）
 * - Ball Don't Lie（需 Key，开源社区）
 * - TheSportsDB（完全免费，多运动项目）
 * - 腾讯NBA（中文数据源，国内访问快，含季后赛对阵图）
 */
class DataSourceManager(
    private val primarySource: DataSource = DataSource.ESPN,
    private val ballDontLieApiKey: String = "",
    private val sportsDbApiKey: String = "3",  // TheSportsDB 免费公共 Key
    private val playByPlaySourceOverride: DataSource? = null  // 文字转播手动指定源（null=自动，中文优先）
) {
    // fallback 优先级：中文源前置（国内网络访问海外源普遍受限）。
    // 腾讯不在此列表：h5sports.match.qq.com 域名已注销（2026-09 实测三家 DNS 均 NXDOMAIN），接口彻底下线
    private val fallbackPriority = listOf(
        DataSource.SINA, DataSource.ZHIBO8, DataSource.ESPN,
        DataSource.NBA_COM, DataSource.SPORTS_DB, DataSource.BALL_DONT_LIE
    )
    // 所有可用数据源实现，按 fallback 优先级排序
    private val scoreDataSources: List<Pair<DataSource, ScoreDataSource>> by lazy {
        buildFallbackChain { source ->
            when (source) {
                DataSource.ESPN -> EspnScoreDataSource()
                DataSource.NBA_COM -> NbaComScoreDataSource()
                DataSource.BALL_DONT_LIE -> {
                    if (ballDontLieApiKey.isNotBlank()) BallDontLieScoreDataSource(ballDontLieApiKey) else null
                }
                DataSource.SPORTS_DB -> TheSportsDbScoreDataSource(sportsDbApiKey)
                DataSource.TENCENT -> null  // 接口域名已注销，见 fallbackPriority 注释
                DataSource.ZHIBO8 -> Zhibo8ScoreDataSource()
                DataSource.SINA -> SinaScoreDataSource()
            }
        }
    }

    private val standingsDataSources: List<Pair<DataSource, StandingsDataSource>> by lazy {
        buildFallbackChain { source ->
            when (source) {
                DataSource.ESPN -> EspnStandingsDataSource()
                DataSource.NBA_COM -> NbaComStandingsDataSource()
                DataSource.BALL_DONT_LIE -> {
                    if (ballDontLieApiKey.isNotBlank()) BallDontLieStandingsDataSource(ballDontLieApiKey) else null
                }
                DataSource.SPORTS_DB -> TheSportsDbStandingsDataSource(sportsDbApiKey)
                DataSource.TENCENT -> null  // 接口域名已注销，见 fallbackPriority 注释
                DataSource.ZHIBO8 -> null  // 直播吧无排名接口
                DataSource.SINA -> null  // 新浪无排名接口
            }
        }
    }

    private val gameDetailDataSources: List<Pair<DataSource, GameDetailDataSource>> by lazy {
        buildFallbackChain { source ->
            when (source) {
                DataSource.ESPN -> EspnGameDetailDataSource()
                DataSource.NBA_COM -> null  // NBA.com 比赛详情暂未实现
                DataSource.BALL_DONT_LIE -> {
                    if (ballDontLieApiKey.isNotBlank()) BallDontLieGameDetailDataSource(ballDontLieApiKey) else null
                }
                DataSource.SPORTS_DB -> null
                DataSource.TENCENT -> null  // 接口域名已注销，见 fallbackPriority 注释
                // 直播吧：比赛详情（比分/每节比分/完整中文技术统计）
                DataSource.ZHIBO8 -> Zhibo8GameDetailDataSource()
                // 新浪：boxscore 详情（比分/每节比分）+ 中文文字转播；无球员统计
                DataSource.SINA -> SinaPlayByPlayDataSource()
            }
        }
    }

    /**
     * 文字转播专用链：与主数据源/公共优先级解耦（看直播的诉求是语言优先，比分诉求是稳定优先）
     * - 自动（override=null）：中文优先，直播吧在前（历史比赛可用且 gameId 与比分列表同源零反查）
     * - 手动（override!=null）：仅指定源，失败不降级（"手动"的语义）
     */
    private val playByPlayDataSources: List<Pair<DataSource, GameDetailDataSource>> by lazy {
        if (playByPlaySourceOverride != null) {
            buildPlayByPlayImpl(playByPlaySourceOverride)
                ?.let { listOf(playByPlaySourceOverride to it) } ?: emptyList()
        } else {
            listOf(DataSource.ZHIBO8, DataSource.SINA, DataSource.ESPN)
                .mapNotNull { source -> buildPlayByPlayImpl(source)?.let { source to it } }
        }
    }

    private fun buildPlayByPlayImpl(source: DataSource): GameDetailDataSource? = when (source) {
        DataSource.ZHIBO8 -> Zhibo8PlayByPlayDataSource()
        DataSource.SINA -> SinaPlayByPlayDataSource()
        DataSource.ESPN -> EspnGameDetailDataSource()
        else -> null  // 其余源无文字转播能力（BallDontLie 免费版不支持，见 DataSource 枚举）
    }

    private val playoffDataSources: List<Pair<DataSource, PlayoffDataSource>> by lazy {
        buildFallbackChain { source ->
            when (source) {
                DataSource.TENCENT -> null  // 接口域名已注销，见 fallbackPriority 注释
                DataSource.ESPN -> MockPlayoffDataSource()  // ESPN 无对阵图 API，用 mock
                DataSource.NBA_COM -> MockPlayoffDataSource()
                DataSource.BALL_DONT_LIE -> null
                DataSource.SPORTS_DB -> null
                DataSource.ZHIBO8 -> null  // 直播吧无季后赛对阵图接口
                DataSource.SINA -> null  // 新浪无季后赛对阵图接口
            }
        }
    }

    /**
     * 构建 fallback 链：主数据源放在最前，其他按优先级排列
     * @param factory 根据数据源创建实现，返回 null 表示该源在此场景不可用
     */
    private inline fun <T> buildFallbackChain(
        factory: (DataSource) -> T?
    ): List<Pair<DataSource, T>> {
        val result = mutableListOf<Pair<DataSource, T>>()

        // 先加主数据源
        factory(primarySource)?.let { result.add(primarySource to it) }

        // 再加其他数据源，按优先级排列（腾讯已下线，不在 fallbackPriority 中）
        fallbackPriority.forEach { source ->
            if (source != primarySource) {
                factory(source)?.let { result.add(source to it) }
            }
        }

        return result
    }

    /**
     * 获取比分（带自动 fallback）
     * @return 成功的结果 + 实际使用的数据源
     */
    fun getScores(date: LocalDate): Pair<Result<NBAScoreboard>, DataSource> {
        return executeWithFallback(scoreDataSources, { it.supportsScores }) { it.getScores(date) }
    }

    /**
     * 获取排名（带自动 fallback）
     */
    fun getStandings(): Pair<Result<NBAStandings>, DataSource> {
        return executeWithFallback(standingsDataSources, { it.supportsStandings }) { it.getStandings() }
    }

    /**
     * 获取比赛详情（带自动 fallback）
     */
    fun getGameDetail(gameId: String): Pair<Result<GameDetail>, DataSource> {
        return executeWithFallback(gameDetailDataSources, { it.supportsGameDetail }) { it.getGameDetail(gameId) }
    }

    /**
     * 获取文字转播（文字转播专用链，见 playByPlayDataSources 注释）
     */
    fun getPlayByPlay(gameId: String, homeTeamId: String, awayTeamId: String): Pair<Result<PlayByPlay>, DataSource> {
        return executeWithFallback(playByPlayDataSources, { it.supportsPlayByPlay }) {
            it.getPlayByPlay(gameId, homeTeamId, awayTeamId)
        }
    }

    /**
     * 获取季后赛对阵图
     */
    fun getPlayoffBracket(): Pair<Result<PlayoffBracketResponse>, DataSource> {
        return executeWithFallback(playoffDataSources, { it.supportsPlayoffs }) { it.getPlayoffBracket() }
    }

    /**
     * 通用 fallback 执行逻辑
     */
    private inline fun <T, D> executeWithFallback(
        sources: List<Pair<DataSource, D>>,
        supportCheck: (DataSource) -> Boolean,
        action: (D) -> Result<T>
    ): Pair<Result<T>, DataSource> {
        val errors = mutableListOf<String>()
        var firstSource: DataSource? = null

        DataSourceCommon.debugLog("降级链: ${sources.joinToString(" -> ") { it.first.id }}")

        for ((source, impl) in sources) {
            if (!supportCheck(source)) continue
            if (firstSource == null) firstSource = source

            DataSourceCommon.debugLog("→ 请求 ${source.displayName} ...")
            val result = action(impl)
            if (result.isSuccess) {
                DataSourceCommon.debugLog("✓ ${source.displayName} 成功")
                return result to source
            }
            val errorMsg = result.exceptionOrNull()?.message ?: "未知错误"
            DataSourceCommon.debugLog("✗ ${source.displayName} 失败: $errorMsg")
            errors.add("${source.displayName}: $errorMsg")
        }

        val failure: Result<T> = Result.failure(Exception("所有数据源均失败：\n${errors.joinToString("\n")}"))
        return failure to (firstSource ?: DataSource.ESPN)
    }
}
