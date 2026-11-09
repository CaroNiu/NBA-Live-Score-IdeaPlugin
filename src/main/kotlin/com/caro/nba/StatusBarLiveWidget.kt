package com.caro.nba

import com.caro.nba.datasource.DataSource
import com.caro.nba.model.NBAGame
import com.caro.nba.service.FollowScoreWatcher
import com.caro.nba.service.GameDetailService
import com.caro.nba.service.GameRef
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.ZoneId
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

/**
 * 状态栏文字直播 widget（与比分轮播互相独立）
 *
 * - 默认只显示一个「选择直播」小按钮，不占空间
 * - 点击弹出菜单列出进行中的关注比赛，选中后常驻显示该场最新一条文字直播
 * - 显示期间跟随轮询节奏（15s）自动刷新，比分变化/事件推进滚动更新
 * - × 按钮取消观看恢复为入口按钮；比赛结束后保留完赛信息不再拉取
 * - 总开关与比分 widget 共用设置页「状态栏常驻比分」
 */
class StatusBarLiveWidget(private val project: Project) : CustomStatusBarWidget {

    companion object {
        const val ID = "NBA.LiveWidget"

        /** 文字直播自动刷新间隔 */
        private const val LIVE_REFRESH_MS = 15_000

        /** 状态栏单行直播文字最大长度 */
        private const val LIVE_TEXT_MAX_LEN = 30
    }

    private val fetchScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val detailService = GameDetailService()

    private var games: List<NBAGame> = emptyList()

    /** 用户当前观看的比赛（games 最新引用随广播刷新） */
    @Volatile
    private var selectedGameId: String? = null

    /** 拉取中的比赛 id，防重复请求 */
    @Volatile
    private var fetchingId: String? = null

    /** 最新一条直播文字（空串=暂无数据） */
    @Volatile
    private var latestText: String = ""

    /** 选择直播比赛（弹出菜单） */
    private val chooseAction = object : AnAction("选择文字直播", "选择要观看的文字直播比赛", AllIcons.Actions.Preview) {
        override fun actionPerformed(e: AnActionEvent) {
            showChoosePopup()
        }
    }

    /** 关闭直播，恢复入口按钮 */
    private val closeAction = object : AnAction("关闭文字直播", "停止观看文字直播", AllIcons.Actions.Close) {
        override fun actionPerformed(e: AnActionEvent) {
            clearSelection()
        }
    }

    /** 平台原生紧凑工具栏按钮（高度贴合状态栏，不撑高） */
    private val chooseToolbar: ActionToolbar =
        ActionManager.getInstance()
            .createActionToolbar("NBAStatusBarLive", DefaultActionGroup(chooseAction), true)
            .apply { setTargetComponent(liveLabel) }

    private val closeToolbar: ActionToolbar =
        ActionManager.getInstance()
            .createActionToolbar("NBAStatusBarLiveClose", DefaultActionGroup(closeAction), true)
            .apply { setTargetComponent(liveLabel) }

    private val liveLabel = JLabel().apply {
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1f)
        border = JBUI.Borders.empty(0, 4)
        foreground = UIUtil.getLabelForeground()
        // 隐蔽模式：关闭按钮平时隐藏，悬停直播文本时浮现
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (currentMode() == "stealth") closeToolbar.component.isVisible = true
            }

            override fun mouseExited(e: MouseEvent) {
                if (currentMode() == "stealth") closeToolbar.component.isVisible = false
            }
        })
    }

    private val panel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(chooseToolbar.component, BorderLayout.CENTER)
    }

    /** 显示期间定时刷新最新直播文字（回调在 EDT） */
    private val refreshTimer = Timer(LIVE_REFRESH_MS) { fetchIfNeeded() }

    private val listener = object : FollowScoreWatcher.Listener {
        override fun onFollowedGamesChanged(games: List<NBAGame>) {
            this@StatusBarLiveWidget.games = games
            updateView()
        }
    }

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        FollowScoreWatcher.addListener(listener)
    }

    override fun dispose() {
        refreshTimer.stop()
        fetchScope.cancel()
        FollowScoreWatcher.removeListener(listener)
    }

    override fun getComponent(): JComponent = panel

    // ---------------- 选择与视图 ----------------

    /** 弹出「进行中的关注比赛」选择菜单；无进行中比赛时列出未开赛场次 */
    private fun showChoosePopup() {
        val candidates = games.filter { it.status == "in_progress" }
            .ifEmpty { games.filter { it.status == "scheduled" } }
        val entries: List<Pair<String, String?>> = when {
            candidates.isNotEmpty() -> candidates.map {
                "${it.awayTeam.name} ${it.awayScore}-${it.homeScore} ${it.homeTeam.name} · ${it.getStatusDisplay()}" to it.gameId
            }
            else -> listOf("暂无进行中的关注比赛" to null)
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(entries)
            .setTitle("选择文字直播比赛")
            .setRenderer(SimpleListPopupEntryRenderer())
            .setItemChosenCallback { chosen ->
                val gameId = chosen.second ?: return@setItemChosenCallback
                selectGame(gameId)
            }
            .createPopup()
            .show(RelativePoint.getNorthEastOf(chooseToolbar.component))
    }

    private fun selectGame(gameId: String) {
        selectedGameId = gameId
        latestText = ""
        updateView()
        fetchIfNeeded()
        if (!refreshTimer.isRunning) refreshTimer.start()
    }

    private fun clearSelection() {
        selectedGameId = null
        latestText = ""
        refreshTimer.stop()
        updateView()
    }

    /** 依据「是否选中」切换视图：入口按钮 <-> 直播文本 + 关闭按钮 */
    private fun updateView() {
        val enabled = NBASettingsState.getInstance().statusBarEnabled
        val selected = selectedGameId?.let { id -> games.find { it.gameId == id } }
        panel.removeAll()
        if (selected == null || !enabled) {
            panel.add(chooseToolbar.component, BorderLayout.CENTER)
            panel.isVisible = enabled
            refreshTimer.stop()
        } else {
            liveLabel.text = displayText(selected)
            liveLabel.toolTipText = tooltipText(selected)
            panel.add(liveLabel, BorderLayout.CENTER)
            closeToolbar.component.isVisible = currentMode() != "stealth"
            panel.add(closeToolbar.component, BorderLayout.EAST)
            panel.isVisible = true
        }
        panel.revalidate()
        panel.repaint()
    }

    /** 当前显示样式（normal=完整队名 / compact=缩写 / stealth=仅比分） */
    private fun currentMode(): String = NBASettingsState.getInstance().displayMode

    /** 节次缩写：Q1-Q4 / OT */
    private fun periodShort(game: NBAGame): String = when {
        game.status == "finished" -> "终"
        game.period in 1..4 -> "Q${game.period}"
        game.period > 4 -> "OT"
        else -> ""
    }

    /** 直播区文本，按显示样式分档渲染（完整事件始终在 tooltip 里） */
    private fun displayText(game: NBAGame): String = when (currentMode()) {
        "compact" -> when (game.status) {
            "in_progress" -> "${game.awayTeam.abbreviation} ${game.awayScore}-${game.homeScore} ${game.homeTeam.abbreviation} · ${periodShort(game)} ${game.clock}".trim()
            "finished" -> "${game.awayTeam.abbreviation} ${game.awayScore}-${game.homeScore} ${game.homeTeam.abbreviation} · 终"
            else -> "${game.awayTeam.abbreviation}-${game.homeTeam.abbreviation} · 未开始"
        }
        "stealth" -> when (game.status) {
            "scheduled" -> "--"
            else -> "${game.awayScore}-${game.homeScore}"
        }
        else -> when (game.status) {
            "in_progress" -> if (latestText.isBlank()) "直播加载中 · ${game.awayTeam.abbreviation} ${game.awayScore}-${game.homeScore} ${game.homeTeam.abbreviation}"
            else "${shorten(latestText)} ｜ ${game.awayTeam.abbreviation} ${game.awayScore}-${game.homeScore} ${game.homeTeam.abbreviation}"
            "finished" -> "已结束 ${game.awayTeam.name} ${game.awayScore}-${game.homeScore} ${game.homeTeam.name}"
            else -> "${game.awayTeam.name} vs ${game.homeTeam.name} · 未开始"
        }
    }

    private fun tooltipText(game: NBAGame): String {
        val base = "<b>${esc(game.awayTeam.name)} ${game.awayScore}-${game.homeScore} ${esc(game.homeTeam.name)}</b>"
        return when (game.status) {
            "in_progress" -> "<html>$base · ${esc(game.getStatusDisplay())}<br>${esc(if (latestText.isBlank()) "正在获取最新直播文字…" else latestText)}<br>每15秒自动刷新 · 点击 × 关闭</html>"
            else -> "<html>$base · ${esc(game.getStatusDisplay())}<br>点击 × 关闭</html>"
        }
    }

    // ---------------- 拉取 ----------------

    /** 显示中的比赛且进行中才拉取；结束/未开始只显示静态信息 */
    private fun fetchIfNeeded() {
        val game = selectedGameId?.let { id -> games.find { it.gameId == id } } ?: return
        if (game.status != "in_progress") return
        if (fetchingId == game.gameId) return
        fetchingId = game.gameId
        fetchScope.launch {
            val text = fetchLatestPlayText(game)
            ApplicationManager.getApplication().invokeLater {
                fetchingId = null
                latestText = text
                updateView()
            }
        }
    }

    /** 取 sequenceNumber 最大的那条事件（各源 plays 排序方向不一，按序号定位最稳） */
    private fun fetchLatestPlayText(game: NBAGame): String {
        return try {
            val ref = GameRef(
                LocalDate.now(ZoneId.of("America/New_York")),
                game.homeTeam.abbreviation,
                game.awayTeam.abbreviation,
                DataSource.fromId(NBASettingsState.getInstance().dataSource)
            )
            val (result, _) = detailService.getPlayByPlay(
                game.gameId, game.homeTeam.id, game.awayTeam.id, ref
            )
            val latest = result.getOrNull()?.plays
                ?.maxByOrNull { it.sequenceNumber.toLongOrNull() ?: Long.MIN_VALUE }
                ?: return ""
            "${latest.clock} ${latest.text}".trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun shorten(text: String): String =
        if (text.length <= LIVE_TEXT_MAX_LEN) text else text.take(LIVE_TEXT_MAX_LEN - 1) + "…"

    private fun esc(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** 简单渲染：显示条目文本、无法选择的条目置灰 */
private class SimpleListPopupEntryRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): java.awt.Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        @Suppress("UNCHECKED_CAST")
        val entry = value as? Pair<String, String?>
        text = entry?.first ?: value?.toString() ?: ""
        isEnabled = entry?.second != null
        return this
    }
}

/**
 * 状态栏文字直播 widget 工厂：注册到 plugin.xml 的 statusBarWidgetFactory 扩展点。
 * 用户可在 IDE 状态栏右键菜单中单独启用/禁用本 widget。
 */
class StatusBarLiveWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = StatusBarLiveWidget.ID
    override fun getDisplayName(): String = "NBA 文字直播"
    override fun isAvailable(project: Project): Boolean =
        NBASettingsState.getInstance().statusBarEnabled
    override fun createWidget(project: Project): StatusBarWidget = StatusBarLiveWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }
}
