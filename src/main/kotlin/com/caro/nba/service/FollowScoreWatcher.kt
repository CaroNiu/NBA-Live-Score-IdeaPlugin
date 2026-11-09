package com.caro.nba.service

import com.caro.nba.NBASettingsState
import com.caro.nba.model.NBAGame
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 关注球队比分监听（应用级单例）
 *
 * 职责：
 * 1. 定时轮询「关注的球队」在美东今天的比赛（走 NBADataService 多源降级链）
 * 2. 检测状态迁移发 IDE 通知：开赛 / 进入新的一节 / 关键分（分差 ≤5）/ 完赛
 * 3. 维护「关注比赛列表」推给状态栏 widget，多场时由 widget 轮播显示
 *
 * 设计要点：
 * - 通知每场比赛每类只发一次（快照 announced 集合去重），重启 IDE 不补发旧状态
 * - 有人订阅才轮询（状态栏 widget 注册即订阅；全部注销自动停轮询，不浪费请求）
 * - 轮询节奏：有进行中的关注比赛 15s，其余 60s（等开赛）；设置变更即时生效
 * - 线程模型与各面板一致：协程内同步请求，结果经 invokeLater 回 EDT 通知订阅者
 */
object FollowScoreWatcher {

    /** 有进行中的关注比赛时的轮询间隔 */
    private const val ACTIVE_INTERVAL_MS = 15_000L

    /** 无进行中比赛时的轮询间隔（等开赛/等关注配置变化） */
    private const val IDLE_INTERVAL_MS = 60_000L

    /** 订阅者：状态栏 widget 等，回调在 EDT */
    interface Listener {
        fun onFollowedGamesChanged(games: List<NBAGame>)
    }

    private data class Snapshot(
        val status: String,
        val period: Int,
        val scoreDiff: Int,
        val announced: MutableSet<String>
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    @Volatile
    private var pollJob: Job? = null

    @Volatile
    private var refreshRequested = false

    @Volatile
    private var disposed = false

    /** 当前关注比赛列表（最近一次轮询结果，供 widget 初始化/轮播显示） */
    @Volatile
    var followedGames: List<NBAGame> = emptyList()
        private set

    // ---------------- 订阅 ----------------

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        startPollingIfNeeded()
        // 立即把已有结果推给新订阅者（首次注册时通常为空列表，widget 先隐藏）
        notifyListeners()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    /** 设置页 apply 后调用：关注球队/开关可能变化，清快照并立即重新拉取 */
    fun onSettingsChanged() {
        snapshots.clear()
        if (listeners.isNotEmpty()) startPollingIfNeeded()
    }

    /**
     * 手动刷新（状态栏刷新按钮等调用）：打断本轮等待立即拉一次。
     * 轮询等待按 200ms 分片，标记位在下一个分片即被响应。
     */
    fun refreshNow() {
        refreshRequested = true
        startPollingIfNeeded()
    }

    // ---------------- 轮询 ----------------

    private fun startPollingIfNeeded() {
        if (disposed || pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refreshRequested = false
                val interval = pollOnce()
                // 分片等待：期间手动刷新请求可立即唤醒下一轮
                var waited = 0L
                while (isActive && !refreshRequested && waited < interval) {
                    delay(200)
                    waited += 200
                }
            }
        }
    }

    /**
     * 单轮轮询：拉关注球队比赛 → 迁移检测发通知 → 推送焦点比赛
     * @return 下一轮间隔毫秒
     */
    private fun pollOnce(): Long {
        val settings = NBASettingsState.getInstance()
        val followed = settings.followedTeams
        if (followed.isEmpty() || listeners.isEmpty()) return IDLE_INTERVAL_MS * 2
        // 状态栏与通知都关了：没有消费者，不发请求（重新开启经 onSettingsChanged 即时生效）
        if (!settings.statusBarEnabled && !settings.notifyEnabled) return IDLE_INTERVAL_MS * 2

        val today = LocalDate.now(ZoneId.of("America/New_York"))
        val result = NBADataService().getGames(today)
        val followedSet = followed.toSet()
        val games = result.getOrNull()?.games?.filter { g ->
            g.awayTeam.name in followedSet || g.homeTeam.name in followedSet
        } ?: emptyList()

        if (result.isSuccess) {
            // 拉取失败时保留旧列表（数据源抖动不应让状态栏 widget 闪烁消失）
            followedGames = games
            if (games.isNotEmpty()) detectAndNotify(games)
        }
        notifyListeners()

        return if (games.any { it.status == "in_progress" }) ACTIVE_INTERVAL_MS else IDLE_INTERVAL_MS
    }

    // ---------------- 状态迁移与通知 ----------------

    /**
     * 按比赛对比状态快照，触发一次性通知：
     * - scheduled -> in_progress：开赛
     * - period 增加：进入第 N 节 / 加时
     * - 进行中分差 <=5 且上次 >5：关键分时刻（一场最多提醒一次）
     * - -> finished：完赛 + 最终比分
     */
    private fun detectAndNotify(games: List<NBAGame>) {
        for (game in games) {
            val old = snapshots[game.gameId]
            val newDiff = Math.abs(game.homeScore - game.awayScore)
            val announced: MutableSet<String> = old?.announced ?: initialAnnounced(game)

            if (old != null) {
                if (old.status == "scheduled" && game.status == "in_progress" && "start" !in announced) {
                    announced += "start"
                    notifyInfo("比赛开始", "${game.awayTeam.name} vs ${game.homeTeam.name} 已开赛")
                }
                if (game.status == "in_progress" && game.period > old.period && game.period >= 1 && "q${game.period}" !in announced) {
                    announced += "q${game.period}"
                    notifyInfo(
                        if (game.period <= 4) "进入第${game.period}节" else "进入加时",
                        "${game.awayTeam.name} ${game.awayScore}-${game.homeScore} ${game.homeTeam.name}"
                    )
                }
                if (game.status == "in_progress" && newDiff <= 5 && old.scoreDiff > 5 && "clutch" !in announced) {
                    announced += "clutch"
                    notifyInfo(
                        "关键分时刻（分差 $newDiff）",
                        "${game.awayTeam.name} ${game.awayScore}-${game.homeScore} ${game.homeTeam.name}，比赛胶着"
                    )
                }
                if (old.status != "finished" && game.status == "finished" && "finish" !in announced) {
                    announced += "finish"
                    notifyInfo(
                        "比赛结束",
                        "最终比分：${game.awayTeam.name} ${game.awayScore}-${game.homeScore} ${game.homeTeam.name}"
                    )
                }
            }

            snapshots[game.gameId] = Snapshot(game.status, game.period, newDiff, announced)
        }
        // 清理已完赛的快照，避免长期驻留
        snapshots.keys.retainAll(games.map { it.gameId }.toSet())
    }

    /** 首次见到的比赛：按当前状态预填 announced，避免「IDE 启动即轰炸」补发旧状态 */
    private fun initialAnnounced(game: NBAGame): MutableSet<String> = when (game.status) {
        "finished" -> mutableSetOf("start", "finish")
        "in_progress" -> mutableSetOf("start")
        else -> mutableSetOf()
    }

    private fun notifyInfo(title: String, content: String) {
        if (!NBASettingsState.getInstance().notifyEnabled) return
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("NBA Score Notifications")
                .createNotification(title, content, NotificationType.INFORMATION)
                .addAction(OpenNbaScoreToolWindowAction())
                .notify(null)
        }
    }

    private fun notifyListeners() {
        val games = followedGames
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onFollowedGamesChanged(games) }
        }
    }
}

/** 通知动作：激活 NBA Score 工具窗口 */
class OpenNbaScoreToolWindowAction : AnAction("打开 NBA Score") {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project? = e.project
        if (project != null) {
            ToolWindowManager.getInstance(project).getToolWindow("NBA Score")?.activate(null)
        }
    }
}
