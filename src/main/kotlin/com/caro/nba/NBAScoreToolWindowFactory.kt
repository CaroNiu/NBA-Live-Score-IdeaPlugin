package com.caro.nba

import com.caro.nba.model.NBAGame
import com.caro.nba.service.GameRef
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * NBA 比分工具窗口工厂
 */
class NBAScoreToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 创建主面板（带标签页）；Content 本身是 Disposable，工具窗口关闭时释放各面板的协程/定时器
        val mainPanel = NBAScoreMainPanel(project)
        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        Disposer.register(content) { mainPanel.dispose() }
        toolWindow.contentManager.addContent(content)
    }

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setTitle("NBA Score")
    }
}

/**
 * 主面板 - 包含多个标签页（懒加载以提升性能）
 *
 * 标签索引约定：0=比分、1=排名、2=直播（文字转播，固定 Tab 实时观看）
 */
class NBAScoreMainPanel(project: Project) : JBTabbedPane() {

    companion object {
        private const val TAB_LIVE = 2
    }

    // 懒加载面板
    private val scorePanel by lazy { NBAScorePanel(project, onPlayByPlayClick = { game, status, ref ->
        openPlayByPlay(game, status, ref)
    }) }
    private val standingsPanel by lazy { StandingsPanel(project) }

    // 跟踪哪些标签页已初始化
    private val initializedTabs = BooleanArray(3)

    /** 直播标签页容器：初始为占位提示，选比赛后替换为 PlayByPlayPanel */
    private val playByPlayContainer = JPanel(BorderLayout())

    /** 当前正在观看的文字转播面板（换场/销毁时释放） */
    private var currentPlayByPlay: PlayByPlayPanel? = null

    init {
        tabPlacement = TOP

        // 只添加占位标签，不立即初始化面板
        addTab("🏀 比分", JPanel())
        addTab("📊 排名", JPanel())
        addTab("📺 直播", playByPlayContainer)

        // 设置标签提示
        setToolTipTextAt(0, "查看今日比赛比分")
        setToolTipTextAt(1, "查看东西部排名")
        setToolTipTextAt(TAB_LIVE, "固定展示所选比赛的文字直播，实时刷新")

        // 添加监听器，仅在首次点击标签时初始化内容
        addChangeListener { e ->
            val source = e.source as? JBTabbedPane ?: return@addChangeListener
            val selectedIndex = source.selectedIndex
            initializeTab(selectedIndex)
        }

        // 延迟初始化第一个标签页
        SwingUtilities.invokeLater {
            initializeTab(0)
        }
    }

    /**
     * 初始化指定标签页的内容（仅一次）
     */
    private fun initializeTab(index: Int) {
        if (index < 0 || index >= initializedTabs.size || initializedTabs[index]) {
            return
        }

        initializedTabs[index] = true
        when (index) {
            0 -> setComponentAt(0, scorePanel)
            1 -> {
                setComponentAt(1, standingsPanel)
                standingsPanel.loadDataIfNeeded()
            }
            TAB_LIVE -> setComponentAt(TAB_LIVE, playByPlayContainer)
        }
        if (index == TAB_LIVE) {
            showLivePlaceholder()
        }
    }

    /** 直播 tab 未选比赛时的占位提示 */
    private fun showLivePlaceholder() {
        if (playByPlayContainer.componentCount > 0) return
        playByPlayContainer.add(JPanel(BorderLayout()).apply {
            add(JLabel("<html><div style='text-align: center; padding: 40px;'>"
                    + "📺 在「比分」标签页点击比赛的 <b>📺 直播</b> 链接<br>"
                    + "即可在此固定观看该比赛的文字直播（每 15 秒自动刷新）</div></html>").apply {
                font = font.deriveFont(14f)
                foreground = Color.GRAY
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
            }, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        playByPlayContainer.revalidate()
        playByPlayContainer.repaint()
    }

    /**
     * 打开一场比赛的文字直播（固定在直播标签页内实时观看）
     * @param game 比赛实体（取 id/队名/队 id）
     * @param status 比赛状态快照（比分卡片或详情对话框提供的最新值）
     * @param ref 跨源反查引用（id 不通用的数据源之间定位同一场比赛）
     */
    private fun openPlayByPlay(game: NBAGame, status: String, ref: GameRef?) {
        initializeTab(TAB_LIVE)  // 从未点开过直播 tab 时先挂载容器

        // 换场：释放旧面板的协程与定时器，再挂新面板
        currentPlayByPlay?.dispose()
        val panel = PlayByPlayPanel(
            game.gameId,
            game.homeTeam.name,
            game.awayTeam.name,
            game.homeTeam.id,
            game.awayTeam.id,
            status,
            ref
        )
        currentPlayByPlay = panel

        playByPlayContainer.removeAll()
        playByPlayContainer.add(panel, BorderLayout.CENTER)
        playByPlayContainer.revalidate()
        playByPlayContainer.repaint()

        selectedIndex = TAB_LIVE
    }

    fun refresh() {
        if (initializedTabs[1]) {
            standingsPanel.refresh()
        }
    }

    fun dispose() {
        currentPlayByPlay?.dispose()
        currentPlayByPlay = null
        if (initializedTabs[0]) {
            scorePanel.dispose()
        }
        if (initializedTabs[1]) {
            standingsPanel.dispose()
        }
    }
}
