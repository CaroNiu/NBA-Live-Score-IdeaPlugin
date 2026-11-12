package com.caro.nba.service

import com.caro.nba.datasource.impl.EspnStatsDataSource
import com.caro.nba.datasource.impl.Zhibo8LeaderDataSource
import com.caro.nba.model.NBATeamSchedule
import com.caro.nba.model.ScheduleDay
import com.caro.nba.model.SeasonLeaders
import com.caro.nba.model.TodayLeaders
import java.time.LocalDate

/**
 * 数据王/球队赛程服务
 *
 * - 今日数据王/球队赛程：仅 ESPN 提供，固定 ESPN
 * - 赛季数据王：直播吧优先（中文球员名/队名，100 人完整榜），失败降级 ESPN（英文，Top5）
 */
class LeagueLeadersService {
    private val stats = EspnStatsDataSource()
    private val zb8Leaders = Zhibo8LeaderDataSource()

    /** 今日数据王：直播吧优先（中文球员/队名），无数据或失败降级 ESPN；美东日期语义与比分页一致 */
    fun todayLeaders(easternDate: LocalDate): Result<TodayLeaders> {
        val zb8 = zb8Leaders.todayLeaders(easternDate)
        val zb8Usable = zb8.getOrNull()?.categories?.any { it.entries.isNotEmpty() } == true
        return if (zb8Usable) zb8 else stats.todayLeaders(easternDate)
    }

    /** 赛季数据王：直播吧优先（中文、Top10），降级 ESPN（英文、Top5） */
    fun seasonLeaders(): Result<SeasonLeaders> {
        val zb8 = zb8Leaders.seasonLeaders(topN = 10)
        val zb8Usable = zb8.getOrNull()?.categories?.any { it.entries.isNotEmpty() } == true
        return if (zb8Usable) zb8 else stats.seasonLeaders()
    }

    /** 球队赛程（近5场 + 未来5场） */
    fun teamSchedule(abbr: String): Result<NBATeamSchedule> = stats.teamSchedule(abbr)

    /** 未来 N 天联赛赛程（直播吧，北京时间口径） */
    fun upcomingSchedule(days: Int = 7): Result<List<ScheduleDay>> = zb8Leaders.upcomingSchedule(days)
}
