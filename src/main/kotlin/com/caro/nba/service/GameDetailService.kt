package com.caro.nba.service

import com.caro.nba.NBASettingsState
import com.caro.nba.datasource.DataSource
import com.caro.nba.datasource.DataSourceCommon
import com.caro.nba.datasource.DataSourceManager
import com.caro.nba.datasource.impl.findSinaMid
import com.caro.nba.datasource.impl.findZhibo8Id
import com.caro.nba.model.GameDetail
import com.caro.nba.model.PlayByPlay
import com.google.gson.JsonObject
import okhttp3.Request
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 比赛引用信息：用于跨数据源反查。
 *
 * 中文数据源（新浪/直播吧）的 gameId 与 ESPN 的 eventId 不通用，
 * 直接用其查对方接口必然失败；此时按「美东日期 + 两队缩写」
 * 反查出对方源的真实 id 后再查详情/文字转播。
 *
 * @param idSource gameId 的归属源（比分面板记录的实际数据源），决定是否需要反查
 */
data class GameRef(
    val easternDate: LocalDate,
    val homeAbbr: String,
    val awayAbbr: String,
    val idSource: DataSource = DataSource.ESPN
)

/**
 * 比赛详情服务
 *
 * 委托给 DataSourceManager 进行多数据源管理和自动 fallback。
 * 数据源配置从 NBASettingsState 读取。
 */
class GameDetailService {

    /**
     * 获取比赛详情（返回实际生效的数据源，供 UI 说明数据来路）
     * @param ref 比赛引用信息（各数据源 gameId 不通用时用于反查），可为 null
     */
    fun getGameDetail(gameId: String, ref: GameRef? = null): Pair<Result<GameDetail>, DataSource> {
        val (direct, directSource) = getGameDetailWithSource(gameId)
        if (direct.isSuccess || ref == null) return direct to directSource

        // 新浪反查：gameId 非新浪源（如直播吧/ESPN）且直接查失败时，
        // 按日期+队名反查新浪 mid 查 boxscore（新浪接口稳定且国内可达，优先于 ESPN）
        if (ref.idSource != DataSource.SINA) {
            val sinaMid = findSinaMid(ref.easternDate, ref.homeAbbr, ref.awayAbbr)
            if (sinaMid != null) {
                val (viaSina, _) = getGameDetailWithSource(sinaMid)
                if (viaSina.isSuccess) {
                    DataSourceCommon.debugLog("详情反查: $gameId -> 新浪 mid $sinaMid")
                    return viaSina to DataSource.SINA
                }
            }
        }

        // ESPN 反查兜底（依赖网络可达）
        val espnEventId = findEspnEventId(ref) ?: return direct to directSource
        DataSourceCommon.debugLog("详情反查: $gameId -> ESPN event $espnEventId")
        val (viaEspn, _) = getGameDetailWithSource(espnEventId)
        return viaEspn to DataSource.ESPN
    }

    /**
     * 获取比赛详情（返回实际使用的数据源）
     */
    fun getGameDetailWithSource(gameId: String): Pair<Result<GameDetail>, DataSource> {
        return try {
            val manager = buildManager()
            manager.getGameDetail(gameId)
        } catch (e: Exception) {
            val failure: Result<GameDetail> = Result.failure(e)
            return failure to DataSource.ESPN
        }
    }

    /**
     * 获取比赛文字转播数据（返回实际使用的数据源）
     *
     * 路由语义：
     * - 手动指定（sourceOverride != null）：仅目标源，id 反查失败不降级，由 UI 引导切源
     * - 自动（sourceOverride == null）：中文优先（直播吧 -> 新浪），全部失败后 ESPN 英文兜底
     *
     * @param ref 比赛引用信息（跨源反查 id 用），可为 null；无 ref 时无法反查，仅能命中 gameId 归属源
     */
    fun getPlayByPlay(
        gameId: String,
        homeTeamId: String,
        awayTeamId: String,
        ref: GameRef? = null,
        sourceOverride: DataSource? = null
    ): Pair<Result<PlayByPlay>, DataSource> {
        return try {
            val idSource = ref?.idSource ?: DataSource.ESPN

            if (sourceOverride != null) {
                val id = resolvePlayByPlayId(gameId, idSource, ref, sourceOverride)
                    ?: return failurePlayByPlay(
                        "无法在${sourceOverride.displayName}定位该场比赛",
                        sourceOverride
                    )
                return buildManager(sourceOverride).getPlayByPlay(id, homeTeamId, awayTeamId)
            }

            // 自动模式：中文优先（顺序与 DataSourceManager 自动链一致）
            for (source in listOf(DataSource.ZHIBO8, DataSource.SINA)) {
                val id = resolvePlayByPlayId(gameId, idSource, ref, source) ?: continue
                val (result, _) = buildManager(source).getPlayByPlay(id, homeTeamId, awayTeamId)
                if (result.isSuccess) return result to source
            }

            // 英文兜底：gameId 本身可用则直接用，否则反查 ESPN eventId
            val espnId = if (idSource == DataSource.ESPN) gameId else ref?.let { findEspnEventId(it) } ?: gameId
            buildManager(DataSource.ESPN).getPlayByPlay(espnId, homeTeamId, awayTeamId)
        } catch (e: Exception) {
            failurePlayByPlay(e.message ?: "文字转播加载失败", DataSource.ESPN)
        }
    }

    /**
     * 解析目标源可识别的比赛 id：gameId 已属目标源直接返回，否则跨源反查
     */
    private fun resolvePlayByPlayId(
        gameId: String,
        idSource: DataSource,
        ref: GameRef?,
        target: DataSource
    ): String? {
        if (idSource == target) return gameId
        if (ref == null) return null
        return when (target) {
            DataSource.ZHIBO8 -> findZhibo8Id(ref.easternDate, ref.homeAbbr, ref.awayAbbr)
            DataSource.SINA -> findSinaMid(ref.easternDate, ref.homeAbbr, ref.awayAbbr)
            DataSource.ESPN -> findEspnEventId(ref)
            else -> null
        }
    }

    private fun failurePlayByPlay(message: String, source: DataSource): Pair<Result<PlayByPlay>, DataSource> {
        val failure: Result<PlayByPlay> = Result.failure(Exception(message))
        return failure to source
    }

    private fun buildManager(playByPlayOverride: DataSource? = null): DataSourceManager {
        val settings = NBASettingsState.getInstance()
        val primarySource = DataSource.fromId(settings.dataSource)
        val sportsDbKey = settings.sportsDbApiKey.ifBlank { "3" }
        return DataSourceManager(primarySource, settings.ballDontLieApiKey, sportsDbKey, playByPlayOverride)
    }

    /**
     * 按日期+队名缩写反查 ESPN eventId
     * @return 匹配比赛的 ESPN eventId，未找到或网络失败返回 null
     */
    private fun findEspnEventId(ref: GameRef): String? {
        return try {
            val home = ref.homeAbbr.uppercase()
            val away = ref.awayAbbr.uppercase()
            if (home.isBlank() || away.isBlank()) return null

            val dateStr = ref.easternDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            // site.web.api.espn.com 可达性更好（site.api 对部分网络 403）
            val url = "https://site.web.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard?dates=$dateStr"
            DataSourceCommon.debugLog("详情反查 ESPN scoreboard: $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://www.espn.com")
                .header("Referer", "https://www.espn.com/")
                .build()

            DataSourceCommon.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DataSourceCommon.debugLog("详情反查 ESPN scoreboard 失败: HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val root = DataSourceCommon.gson.fromJson(body, JsonObject::class.java)
                val events = root.getAsJsonArray("events") ?: return null

                for (event in events) {
                    val competitors = event.asJsonObject
                        .getAsJsonArray("competitions")?.get(0)?.asJsonObject
                        ?.getAsJsonArray("competitors") ?: continue
                    val abbrs = competitors.mapNotNull { c ->
                        c.asJsonObject.getAsJsonObject("team")?.get("abbreviation")?.asString?.uppercase()
                    }.toSet()
                    if (abbrs == setOf(home, away)) {
                        return event.asJsonObject.get("id")?.asString
                    }
                }
                null
            }
        } catch (e: Exception) {
            DataSourceCommon.debugLog("详情反查异常: ${e.message}")
            null
        }
    }
}
