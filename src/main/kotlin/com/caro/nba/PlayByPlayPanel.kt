package com.caro.nba

import com.caro.nba.datasource.DataSource
import com.caro.nba.model.PlayByPlay
import com.caro.nba.service.GameDetailService
import com.caro.nba.service.GameRef
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * 文字转播面板 - 展示比赛实时文字转播
 *
 * 支持在对话框内切换数据来源（自动中文优先 / 直播吧 / 新浪 / ESPN），
 * 选择持久化到 NBASettingsState.playByPlaySource。
 */
class PlayByPlayPanel(
    private val project: Project,
    private val gameId: String,
    private val homeTeamName: String,
    private val awayTeamName: String,
    private val homeTeamId: String,
    private val awayTeamId: String,
    private val gameStatus: String = "scheduled",
    private val gameRef: GameRef? = null  // 中文源 gameId 与 ESPN 不通用时用于跨源反查
) : DialogWrapper(project) {

    private val service = GameDetailService()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var mainPanel: JPanel? = null
    private var loadingLabel: JLabel? = null
    private var infoLabel: JLabel? = null
    private var refreshButton: JButton? = null
    private var lastUpdateLabel: JLabel? = null
    private var sourceBadge: JLabel? = null
    private var sourceCombo: JComboBox<String>? = null
    private var scrollPane: JBScrollPane? = null

    /** 自动刷新定时器（进行中比赛每 30 秒拉一次，加载成功且未完赛时续约） */
    private var autoRefreshTimer: Timer? = null

    /** 加载中标志：防止刷新/切源并发重入 */
    private var loading = false

    /** 程序性设置下拉选中项时跳过 itemStateChanged 的 guard */
    private var suppressComboEvents = false

    private val isGameFinished: Boolean = gameStatus == "finished"
    private val isGameScheduled: Boolean = gameStatus == "scheduled"

    companion object {
        /** 下拉档位 -> 数据源（null=自动，中文优先） */
        private val COMBO_SOURCES = listOf<DataSource?>(null, DataSource.ZHIBO8, DataSource.SINA, DataSource.ESPN)

        private const val COMBO_AUTO = "自动（中文优先）"
        private const val AUTO_REFRESH_INTERVAL_MS = 30_000
        private val COMBO_LABELS = listOf(COMBO_AUTO, "直播吧 · 中文", "新浪体育 · 中文", "ESPN · English")

        /** 实际生效源 -> 徽标文案 */
        private fun badgeText(source: DataSource): String = when (source) {
            DataSource.ZHIBO8 -> "直播吧中文"
            DataSource.SINA -> "新浪中文"
            else -> "ESPN 英文"
        }

        private fun comboIndexOfSource(source: DataSource?): Int =
            COMBO_SOURCES.indexOf(source).coerceAtLeast(0)
    }

    init {
        title = "文字转播: $awayTeamName vs $homeTeamName"
        setOKButtonText("关闭")
        setSize(600, 700)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.preferredSize = Dimension(550, 650)
        panel.border = JBUI.Borders.empty(10)
        mainPanel = panel

        // 顶部工具栏：刷新 + 来源切换 | 比赛状态 | 来源徽标 + 更新时间
        val toolbar = JPanel(BorderLayout(10, 5))

        refreshButton = JButton("🔄 刷新").apply {
            addActionListener { loadPlayByPlay(manual = true) }
        }

        sourceCombo = JComboBox(COMBO_LABELS.toTypedArray()).apply {
            suppressComboEvents = true
            selectedIndex = comboIndexOfSource(sourceFromSettings())
            suppressComboEvents = false
            addActionListener {
                if (!suppressComboEvents) {
                    saveSourceToSettings(selectedSource())
                    loadPlayByPlay(manual = true)
                }
            }
        }

        val westBox = Box.createHorizontalBox()
        westBox.add(refreshButton)
        westBox.add(Box.createHorizontalStrut(8))
        westBox.add(JLabel("来源:"))
        westBox.add(Box.createHorizontalStrut(4))
        westBox.add(sourceCombo)
        toolbar.add(westBox, BorderLayout.WEST)

        val statusLabel = JLabel(when {
            isGameFinished -> "📋 已结束"
            isGameScheduled -> "⏰ 未开始"
            else -> "🔴 进行中"
        }).apply {
            foreground = when {
                isGameFinished -> JBColor.GRAY
                isGameScheduled -> JBColor(0xFF9900, 0xFFCC33)
                else -> JBColor(0x009900, 0x66CC66)
            }
        }
        toolbar.add(statusLabel, BorderLayout.CENTER)

        sourceBadge = JLabel("").apply {
            font = font.deriveFont(10f)
            foreground = JBColor(0x0066CC, 0x4A9EFF)
        }
        lastUpdateLabel = JLabel("").apply {
            font = font.deriveFont(10f)
            foreground = JBColor.GRAY
        }
        val eastBox = Box.createHorizontalBox()
        eastBox.add(sourceBadge)
        eastBox.add(Box.createHorizontalStrut(10))
        eastBox.add(lastUpdateLabel)
        toolbar.add(eastBox, BorderLayout.EAST)

        panel.add(toolbar, BorderLayout.NORTH)

        // 加载中提示
        loadingLabel = JLabel("正在加载文字转播...", SwingConstants.CENTER).apply {
            font = font.deriveFont(16f)
        }
        panel.add(loadingLabel, BorderLayout.CENTER)

        // 创建空的 scrollPane
        scrollPane = JBScrollPane().apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            isVisible = false
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        // 延迟加载数据
        SwingUtilities.invokeLater {
            loadPlayByPlay()
        }

        return panel
    }

    /** 当前下拉选中的数据源档位（null=自动） */
    private fun selectedSource(): DataSource? =
        COMBO_SOURCES.getOrNull(sourceCombo?.selectedIndex ?: 0)

    /** 读取持久化的来源偏好；"auto"/空/非法值返回 null（自动） */
    private fun sourceFromSettings(): DataSource? {
        val id = NBASettingsState.getInstance().playByPlaySource
        if (id.isBlank() || id == "auto") return null
        return DataSource.values().find { it.id == id }
    }

    private fun saveSourceToSettings(source: DataSource?) {
        NBASettingsState.getInstance().playByPlaySource = source?.id ?: "auto"
    }

    /**
     * @param manual 用户主动触发（点刷新/切源）。未开赛拦截只作用于首次自动加载——
     * 状态快照可能滞后（如详情还是 scheduled 而比赛已开打），手动操作时应让数据源给出真实回答
     */
    private fun loadPlayByPlay(manual: Boolean = false) {
        // 未开赛的比赛首次加载不发请求，直接提示
        if (isGameScheduled && !manual) {
            showInfo("🕐 比赛尚未开始\n\n开赛前 10 分钟左右这里会提供文字直播，\n届时点击「刷新」即可获取中文解说。")
            return
        }
        if (loading) return

        loading = true
        setControlsEnabled(false)
        SwingUtilities.invokeLater {
            removeInfoLabel()
            loadingLabel?.isVisible = true
            scrollPane?.isVisible = false
        }

        val override = selectedSource()
        scope.launch {
            val (result, actualSource) = service.getPlayByPlay(
                gameId, homeTeamId, awayTeamId, gameRef, override
            )
            SwingUtilities.invokeLater {
                loading = false
                setControlsEnabled(true)
                result.fold(
                    onSuccess = { pbp ->
                        showPlayByPlay(pbp, actualSource, override == null)
                        updateLastRefreshTime()
                        scheduleAutoRefresh(pbp)
                    },
                    onFailure = { error ->
                        showError(error.message ?: "加载失败", override)
                    }
                )
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        refreshButton?.isEnabled = enabled
        sourceCombo?.isEnabled = enabled
    }

    /**
     * 自动刷新调度：比赛进行中（未完赛）每 30 秒自动拉一次，无需手动点刷新。
     * - 完赛（直播吧流以「比赛结束」收尾）后停止
     * - 加载失败不续约，避免对故障源无意义重试（手动刷新会重新拉起）
     */
    private fun scheduleAutoRefresh(pbp: PlayByPlay) {
        autoRefreshTimer?.stop()
        autoRefreshTimer = null
        // 直播吧流完赛时最后一条 pid_text 为「比赛结束」；列表顺序无保证，任意一条命中即视为已完赛
        val gameEnded = pbp.plays.any {
            it.periodDisplay.contains("结束") || it.periodDisplay.contains("终场")
        }
        if (gameEnded) return

        autoRefreshTimer = Timer(AUTO_REFRESH_INTERVAL_MS) { loadPlayByPlay() }.apply {
            isRepeats = false  // 单次触发，成功后重新调度，避免失败源被持续轮询
            start()
        }
    }

    private fun showPlayByPlay(pbp: PlayByPlay, actualSource: DataSource, isAutoMode: Boolean) {
        loadingLabel?.isVisible = false
        sourceBadge?.text = badgeText(actualSource)

        // 自动刷新会整体重建列表：先记录滚动位置，重建后恢复，避免阅读位置跳回顶部
        val prevScrollValue = scrollPane?.verticalScrollBar?.value ?: 0

        // 创建内容面板
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)

        // 空数据提示：未开赛/数据源无文字直播时给出说明而不是空白
        if (pbp.plays.isEmpty()) {
            content.add(JLabel("<html><div style='padding: 30px; text-align: center;'>"
                    + "该比赛暂无文字转播数据。<br>比赛临近开赛或进行中时才会有解说流，<br>可稍后点击刷新重试。</div></html>").apply {
                font = font.deriveFont(13f)
                foreground = JBColor.GRAY
                alignmentX = Component.CENTER_ALIGNMENT
            })
            scrollPane?.viewport?.view = content
            scrollPane?.isVisible = true
            mainPanel?.revalidate()
            mainPanel?.repaint()
            return
        }

        // 自动模式下实际落到了英文流：说明中文源都没拿到，给出提示
        if (isAutoMode && actualSource == DataSource.ESPN) {
            content.add(JLabel("<html><div style='padding: 4px 0 8px 0; text-align: center;'>"
                    + "ℹ️ 中文解说流暂未开放（直播未开始时无中文流），当前显示 ESPN 英文流</div></html>").apply {
                font = font.deriveFont(11f)
                foreground = JBColor.GRAY
                alignmentX = Component.CENTER_ALIGNMENT
            })
        }

        // 按节数分组显示
        val playsByPeriod = pbp.plays.groupBy { it.period }
        val sortedPeriods = playsByPeriod.keys.sortedDescending()

        for (period in sortedPeriods) {
            val periodPlays = playsByPeriod[period] ?: continue
            val periodDisplay = periodPlays.firstOrNull()?.periodDisplay ?: "第${period}节"

            // 节标题（period=0 表示未识别节次，不重复渲染组头）
            if (period > 0) {
                content.add(JLabel("━━━ $periodDisplay ━━━").apply {
                    font = font.deriveFont(Font.BOLD, 13f)
                    foreground = JBColor(0x0066CC, 0x4A9EFF)
                    alignmentX = Component.CENTER_ALIGNMENT
                    border = EmptyBorder(10, 0, 10, 0)
                })
            }

            // 该节的所有事件
            val sortedPlays = periodPlays.sortedByDescending {
                val clockParts = it.clock.split(":")
                val minutes = clockParts.getOrNull(0)?.toIntOrNull() ?: 0
                val seconds = clockParts.getOrNull(1)?.toIntOrNull() ?: 0
                minutes * 60 + seconds
            }

            for (play in sortedPlays) {
                content.add(createPlayPanel(play))
            }
        }

        // 更新 scrollPane 内容
        scrollPane?.viewport?.view = content
        scrollPane?.isVisible = true
        loadingLabel?.isVisible = false

        mainPanel?.revalidate()
        mainPanel?.repaint()

        // 恢复滚动位置（布局完成后执行才能取到新的滚动范围）
        if (prevScrollValue > 0) {
            SwingUtilities.invokeLater {
                scrollPane?.verticalScrollBar?.value = prevScrollValue
            }
        }
    }

    private fun createPlayPanel(play: PlayByPlay.Play): JPanel {
        // 高度随内容自适应（长解说换行后超过一行），宽度撑满
        val panel = object : JPanel(BorderLayout(8, 3)) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(0xEEEEEE, 0x2D2D2D))
        }

        // 左侧：时间和比分
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)

        leftPanel.add(JLabel("[${play.clock}]").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor.GRAY
        })

        if (play.isScoringPlay) {
            leftPanel.add(JLabel("${play.awayScore} - ${play.homeScore}").apply {
                font = font.deriveFont(Font.BOLD, 13f)
                foreground = JBColor(0xFF6600, 0xFF9933)
            })
        }

        // 右侧：事件描述（JTextArea 自动换行——长解说（如两队球员数据汇总）不再被单行截断）
        val rightPanel = JPanel()
        rightPanel.layout = BoxLayout(rightPanel, BoxLayout.Y_AXIS)

        rightPanel.add(JTextArea(play.text).apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            isOpaque = false
            isFocusable = false
            font = font.deriveFont(12f)
            foreground = if (play.isScoringPlay) JBColor(0x0066CC, 0x4A9EFF) else JBColor.BLACK
            alignmentX = Component.LEFT_ALIGNMENT
        })

        if (play.playType.isNotBlank()) {
            rightPanel.add(JLabel(play.playType).apply {
                font = font.deriveFont(9f)
                foreground = when {
                    play.isScoringPlay -> JBColor(0xFF3300, 0xFF6633)
                    play.playType.contains("Rebound") || play.playType.contains("篮板") -> JBColor(0x009900, 0x66CC66)
                    play.playType.contains("Foul") || play.playType.contains("犯规") -> JBColor(0xFF9900, 0xFFCC33)
                    else -> JBColor.GRAY
                }
            })
        }

        panel.add(leftPanel, BorderLayout.WEST)
        panel.add(rightPanel, BorderLayout.CENTER)

        return panel
    }

    private fun updateLastRefreshTime() {
        val now = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
        lastUpdateLabel?.text = "最后更新: $now"
    }

    /** 移除 CENTER 区域上的说明/错误提示（避免多次提示叠加） */
    private fun removeInfoLabel() {
        infoLabel?.let { mainPanel?.remove(it) }
        infoLabel = null
    }

    /**
     * 显示非错误的说明信息（灰色，居中）
     */
    private fun showInfo(message: String) {
        loadingLabel?.isVisible = false
        scrollPane?.isVisible = false
        removeInfoLabel()

        infoLabel = JLabel("<html><div style='padding: 20px; text-align: center;'>"
                + message.replace("\n", "<br>") + "</div></html>").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(14f)
            horizontalAlignment = SwingConstants.CENTER
        }
        mainPanel?.add(infoLabel, BorderLayout.CENTER)
        mainPanel?.revalidate()
        mainPanel?.repaint()
    }

    private fun showError(message: String, override: DataSource?) {
        loadingLabel?.isVisible = false
        scrollPane?.isVisible = false
        removeInfoLabel()

        // 手动指定源的失败给出去向引导；自动模式的底层报错翻译为用户可理解的提示
        val friendly = when {
            override == DataSource.ZHIBO8 ->
                "直播吧未收录该场比赛的图文直播。<br>可切换为「自动」或「ESPN」查看其他来源。"
            override == DataSource.SINA ->
                "该比赛暂未建立新浪直播间（临近开赛/进行中才有）。<br>可切换为「自动」「直播吧」或「ESPN」。"
            override == DataSource.ESPN ->
                "ESPN 数据获取失败，请检查网络后重试，<br>或切换「自动」使用中文源。"
            message.contains("所有数据源均失败") ->
                "暂时无法获取文字转播。<br>比赛进行中时点击「刷新」重试。"
            message.contains("没有建立") || message.contains("无文字直播") ->
                "该比赛暂未开放文字直播间。<br>临近开赛或进行中时点击「刷新」重试。"
            else -> message
        }
        infoLabel = JLabel("<html><div style='padding: 20px; text-align: center;'>ℹ️ $friendly</div></html>").apply {
            foreground = JBColor(0xCC8800, 0xFFCC33)  // 橙色提示而非红色错误
            font = font.deriveFont(14f)
            horizontalAlignment = SwingConstants.CENTER
        }
        mainPanel?.add(infoLabel, BorderLayout.CENTER)
        mainPanel?.revalidate()
        mainPanel?.repaint()
    }

    override fun dispose() {
        autoRefreshTimer?.stop()
        scope.cancel()
        super.dispose()
    }
}
