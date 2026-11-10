package com.caro.nba.service

import com.caro.nba.NBASettingsState
import com.caro.nba.NBAScorePanel
import com.caro.nba.model.NBAGame
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 晨间提醒启动入口（项目级 postStartupActivity，见 plugin.xml）
 *
 * 多项目同时打开时 DailyBriefingWatcher.start() 幂等，仅首个项目真正启动循环。
 */
class DailyBriefingActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        DailyBriefingWatcher.start()
    }
}

/**
 * 晨间提醒（应用级单例）
 *
 * 职责：IDE 打开后（及运行中每小时）检查当天是否已发过晨间提醒，未发则拉取比赛数据推送：
 * 1. 今日赛程提醒：关注球队今天的比赛 + 本地开球时刻（settings.scheduleReminderEnabled）
 * 2. 每日战报：昨日赛果摘要（最多 5 场）+ 今日场次数（settings.dailyReportEnabled）
 *
 * 设计要点：
 * - 每天只发一次：settings.lastBriefingDate 记本地日期；数据拉取成功才置位，失败静默等下轮重试
 * - 纯客户端：复用 NBADataService 多源降级链，查询美东今天/昨天（NBAScorePanel.toEasternDate 转换）
 * - 两个通知独立开关，都关闭时不置位（用户当天开启仍可收到）
 * - 通知复用「NBA Score Notifications」组与 OpenNbaScoreToolWindowAction（与 FollowScoreWatcher 同款）
 * - 开球时刻显示做多源时区容错（startTime 语义各源不一，见 formatLocalKickoff）
 */
object DailyBriefingWatcher {

    /** 启动后首检延迟：避让 IDE 启动期的磁盘索引与网络竞争 */
    private const val FIRST_CHECK_DELAY_MS = 45_000L

    /** 后续复查间隔：覆盖 IDE 开着跨天的场景 */
    private const val RECHECK_INTERVAL_MS = 60 * 60_000L

    /** 中文数据源（直播吧/新浪）的开球时间约定时区 */
    private const val CN_TZ = "Asia/Shanghai"

    @Volatile
    private var started = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** 每日战报最多展示的昨日赛果条数 */
    private const val REPORT_MAX_LINES = 5

    fun start() {
        if (started) return
        started = true
        scope.launch {
            delay(FIRST_CHECK_DELAY_MS)
            while (isActive) {
                runCatching { checkOnce() }  // 提醒失败绝不影响 IDE
                delay(RECHECK_INTERVAL_MS)
            }
        }
    }

    /** 单次检查：连胜里程碑（受 notifyEnabled 控制）+ 当天未发过的晨间提醒 */
    private fun checkOnce() {
        val settings = NBASettingsState.getInstance()
        checkStreakMilestones(settings)
        if (!settings.scheduleReminderEnabled && !settings.dailyReportEnabled) return
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        if (settings.lastBriefingDate == todayStr) return

        val easternToday = NBAScorePanel.toEasternDate(today)
        val todayGames = NBADataService().getGames(easternToday).getOrNull()?.games ?: return  // 失败不置位，下轮重试
        val yesterdayGames = if (settings.dailyReportEnabled) {
            NBADataService().getGames(easternToday.minusDays(1)).getOrNull()?.games ?: emptyList()
        } else emptyList()

        if (settings.scheduleReminderEnabled) {
            sendScheduleReminder(todayGames, settings.followedTeams.toSet())
        }
        if (settings.dailyReportEnabled) {
            sendDailyReport(yesterdayGames, todayGames.size)
        }

        settings.lastBriefingDate = todayStr
    }

    /**
     * 连胜/连败里程碑：关注球队跨入 ≥5 场区间时提醒一次（类型切换或从低位升入均算跨入）。
     * 数据复用排名接口的 streak 字段（"W5"/"L3"），每天检查一次即可（连胜是慢变量）。
     */
    private fun checkStreakMilestones(settings: NBASettingsState) {
        if (!settings.notifyEnabled) return
        val followed = settings.followedTeams.toSet()
        if (followed.isEmpty()) return
        val standings = StandingsService().getStandingsWithSource().first.getOrNull() ?: return
        val all = standings.eastern.teams + standings.western.teams

        for (team in all) {
            if (team.teamName !in followed) continue
            val streak = team.streak
            val type = streak.firstOrNull()?.toString() ?: continue
            val n = streak.drop(1).toIntOrNull() ?: continue
            if (n < 5) continue

            val snapshot = settings.teamStreakSnapshot[team.teamName] ?: ""
            val oldType = snapshot.firstOrNull()?.toString() ?: ""
            val oldN = snapshot.drop(1).toIntOrNull() ?: 0
            // 跨入判定：类型切换（W↔L，新一轮）或快照数值 <5（首次升入）
            val crossed = type != oldType || oldN < 5
            if (!crossed) continue

            settings.teamStreakSnapshot[team.teamName] = streak
            if (type == "W") {
                notifyInfo("连胜里程碑", "🔥 ${team.teamName} 已 ${n} 连胜（${team.wins}胜${team.losses}负），状态火热！")
            } else {
                notifyInfo("连败里程碑", "❄️ ${team.teamName} 遭遇 ${n} 连败（${team.wins}胜${team.losses}负），盼触底反弹")
            }
        }
    }

    // ---------------- 两类通知 ----------------

    /** 今日赛程提醒：关注球队今天有比赛才发 */
    private fun sendScheduleReminder(games: List<NBAGame>, followed: Set<String>) {
        if (followed.isEmpty()) return
        val matched = games.filter { it.awayTeam.name in followed || it.homeTeam.name in followed }
        if (matched.isEmpty()) return
        val content = matched.joinToString("<br>") { g ->
            "${g.awayTeam.name} vs ${g.homeTeam.name} · ${formatLocalKickoff(g)}"
        }
        notifyInfo("今日关注比赛", content)
    }

    /** 每日战报：昨日赛果 + 今日场次 */
    private fun sendDailyReport(yesterday: List<NBAGame>, todayCount: Int) {
        val finished = yesterday.filter { it.status == "finished" }
        if (finished.isEmpty() && todayCount == 0) return

        val sb = StringBuilder()
        if (finished.isEmpty()) {
            sb.append("昨日无比赛<br>")
        } else {
            sb.append("<b>昨日赛果</b><br>")
            finished.take(REPORT_MAX_LINES).forEach { g ->
                sb.append("${g.awayTeam.name} <b>${g.awayScore} - ${g.homeScore}</b> ${g.homeTeam.name}<br>")
            }
            if (finished.size > REPORT_MAX_LINES) sb.append("…等共 ${finished.size} 场<br>")
        }
        if (todayCount > 0) sb.append("<b>今日共 $todayCount 场</b>，点击打开 NBA Score 查看")
        notifyInfo("NBA 每日战报", sb.toString().trim())
    }

    private fun notifyInfo(title: String, htmlContent: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("NBA Score Notifications")
                .createNotification(title, htmlContent, NotificationType.INFORMATION)
                .addAction(OpenNbaScoreToolWindowAction())
                .notify(null)
        }
    }

    // ---------------- 开球时刻多源容错 ----------------

    /**
     * 各源 startTime 语义不一：ESPN=UTC ISO（2026-11-10T00:00Z）、直播吧/新浪=北京时间文本、
     * NBA.com=展示文本。三级降级尽力转为用户本地时刻显示：
     * 1) ISO/Offset 解析成功 → 转本地；2) 「yyyy-MM-dd HH:mm」按北京时间解析转本地；3) 原样展示 detail
     */
    private fun formatLocalKickoff(game: NBAGame): String {
        val raw = game.startTime
        try {
            val local = OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault())
            return "${local.monthValue}月${local.dayOfMonth}日 ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        } catch (_: DateTimeParseException) {
        }
        try {
            val cn = LocalDateTime.parse(
                raw.take(16),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            ).atZone(ZoneId.of(CN_TZ)).withZoneSameInstant(ZoneId.systemDefault())
            return "${cn.monthValue}月${cn.dayOfMonth}日 ${cn.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        } catch (_: DateTimeParseException) {
        }
        return game.detail.ifEmpty { raw }
    }
}
