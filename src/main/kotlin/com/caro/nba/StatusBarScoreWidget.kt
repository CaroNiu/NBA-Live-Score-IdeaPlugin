package com.caro.nba

import com.caro.nba.model.NBAGame
import com.caro.nba.service.FollowScoreWatcher
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

/**
 * 状态栏常驻比分 widget（关注球队的比分轮播）
 *
 * - 只负责比分：多场比赛每 5 秒自动轮换显示一场；「下一场」按钮手动切换，「刷新」按钮立即拉取
 * - 文字直播是独立 widget（StatusBarLiveWidget），用户主动选择比赛后观看，互不干扰
 * - 无关注球队 / 无关注比赛时自动隐藏，不占状态栏空间
 * - 点击比分文本唤起「NBA Score」工具窗口
 * - 设置页「关注与提醒」tab 的「状态栏常驻比分」开关控制总开关
 */
class StatusBarScoreWidget(private val project: Project) : CustomStatusBarWidget {

    companion object {
        const val ID = "NBA.ScoreWidget"

        /** 多场比赛轮播切换间隔 */
        private const val CAROUSEL_INTERVAL_MS = 5_000
    }

    private val label = JLabel().apply {
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1f)
        border = JBUI.Borders.empty(0, 4)
        foreground = UIUtil.getLabelForeground()
        isVisible = false
        toolTipText = "NBA 关注比赛比分（点击打开 NBA Score）"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                ToolWindowManager.getInstance(project).getToolWindow("NBA Score")?.activate(null)
            }
        })
    }

    /** 下一场比赛（手动翻页，仅多场时可用） */
    private val nextAction = object : AnAction("下一场比赛", "切换显示下一场关注比赛", AllIcons.Actions.Forward) {
        override fun actionPerformed(e: AnActionEvent) {
            if (games.isNotEmpty()) {
                carouselIndex = (carouselIndex + 1) % games.size
                render()
            }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = games.size > 1
        }
    }

    /** 立即刷新 */
    private val refreshAction = object : AnAction("立即刷新", "立即拉取关注比赛最新比分", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            FollowScoreWatcher.refreshNow()
        }
    }

    /** 平台原生紧凑工具栏按钮（高度贴合状态栏，不撑高） */
    private val toolbar: ActionToolbar =
        ActionManager.getInstance()
            .createActionToolbar("NBAStatusBarScore", DefaultActionGroup(nextAction, refreshAction), true)
            .apply { setTargetComponent(label) }

    private val panel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(label, BorderLayout.CENTER)
        add(toolbar.component, BorderLayout.EAST)
        // 隐蔽模式：按钮平时隐藏，鼠标移到比分文本上才浮现
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                if (currentMode() == "stealth" && !toolbar.component.isVisible) {
                    toolbar.component.isVisible = true
                }
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (currentMode() == "stealth") toolbar.component.isVisible = true
            }

            override fun mouseExited(e: MouseEvent) {
                if (currentMode() == "stealth") toolbar.component.isVisible = false
            }
        })
    }

    private var games: List<NBAGame> = emptyList()
    private var carouselIndex = 0

    /** 多场比赛时定时轮换显示（回调在 EDT），启停统一由 render 收敛管理 */
    private val carouselTimer = Timer(CAROUSEL_INTERVAL_MS) {
        if (games.size > 1) {
            carouselIndex = (carouselIndex + 1) % games.size
            render()
        }
    }

    private val listener = object : FollowScoreWatcher.Listener {
        override fun onFollowedGamesChanged(games: List<NBAGame>) {
            this@StatusBarScoreWidget.games = games
            if (carouselIndex >= games.size) carouselIndex = 0
            render()
        }
    }

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        FollowScoreWatcher.addListener(listener)
    }

    override fun dispose() {
        carouselTimer.stop()
        FollowScoreWatcher.removeListener(listener)
    }

    override fun getComponent(): JComponent = panel

    private fun render() {
        val enabled = NBASettingsState.getInstance().statusBarEnabled
        if (games.isEmpty() || !enabled) {
            panel.isVisible = false
            label.isVisible = false
            carouselTimer.stop()
            return
        }
        if (carouselIndex >= games.size) carouselIndex = 0
        val game = games[carouselIndex]
        label.text = formatText(game)
        label.toolTipText = "<html><b>${esc(game.awayTeam.name)} vs ${esc(game.homeTeam.name)}</b><br>" +
                (if (games.size > 1) "关注比赛 ${carouselIndex + 1}/${games.size} · 每5秒轮播 · " else "") +
                "${esc(game.getStatusDisplay())} · 点击打开 NBA Score</html>"
        panel.isVisible = true
        label.isVisible = true
        toolbar.component.isVisible = currentMode() != "stealth"
        // updateActionsImmediately 已废弃，官方替代为 updateActionsAsync（异步工具栏本就是延迟生效）
        toolbar.updateActionsAsync()
        if (games.size > 1) carouselTimer.start() else carouselTimer.stop()
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

    /** 状态栏单行文本，按显示样式分档渲染 */
    private fun formatText(game: NBAGame): String = when (currentMode()) {
        "compact" -> when (game.status) {
            "in_progress" -> "${game.awayTeam.abbreviation} ${game.awayScore}-${game.homeScore} ${game.homeTeam.abbreviation}" +
                    " · ${periodShort(game)} ${game.clock}".trim()
            "scheduled" -> "${game.awayTeam.abbreviation}-${game.homeTeam.abbreviation} · ${game.detail.ifEmpty { "未开始" }}"
            else -> "${game.awayTeam.abbreviation} ${game.awayScore}-${game.homeScore} ${game.homeTeam.abbreviation} · 终"
        }
        "stealth" -> when (game.status) {
            "scheduled" -> Regex("\\d{2}:\\d{2}").find(game.detail)?.value ?: "--"
            else -> "${game.awayScore}-${game.homeScore}"
        }
        else -> when (game.status) {
            "in_progress" -> "${game.awayTeam.name} ${game.awayScore}-${game.homeScore} ${game.homeTeam.name}" +
                    " · ${game.detail.ifEmpty { game.getStatusDisplay() }}"
            "scheduled" -> "${game.awayTeam.name} vs ${game.homeTeam.name} · ${game.detail.ifEmpty { "未开始" }}"
            else -> "${game.awayTeam.name} ${game.awayScore}-${game.homeScore} ${game.homeTeam.name} · 已结束"
        }
    }

    private fun esc(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * 状态栏 widget 工厂：注册到 plugin.xml 的 statusBarWidgetFactory 扩展点。
 * 总开关存于设置（statusBarEnabled），关闭后 IDE 重新打开或刷新状态栏时不再创建；
 * 运行中切换由 widget 自行隐藏文本兜底。
 */
class StatusBarScoreWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = StatusBarScoreWidget.ID
    override fun getDisplayName(): String = "NBA 比分"
    override fun isAvailable(project: Project): Boolean =
        NBASettingsState.getInstance().statusBarEnabled
    override fun createWidget(project: Project): StatusBarWidget = StatusBarScoreWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }
}
