package com.caro.nba

import com.caro.nba.datasource.DataSource
import com.caro.nba.datasource.DataSourceCommon
import com.caro.nba.model.NBAGame
import com.caro.nba.model.NBAScoreboard
import com.caro.nba.service.NBADataService
import com.caro.nba.service.GameRef
import com.caro.nba.NBASettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*
import javax.swing.*

/**
 * NBA 比分面板 - 主 UI（包含比分和排名Tab）
 *
 * @param onPlayByPlayClick 用户请求固定观看某场比赛的文字直播（参数：比赛、状态快照、跨源反查引用），
 *                          由 NBAScoreMainPanel 提供并切到「📺 直播」标签页
 */
class NBAScorePanel(
    private val project: Project,
    private val onPlayByPlayClick: ((game: NBAGame, status: String, ref: GameRef?) -> Unit)? = null
) : JPanel(BorderLayout()) {

    private val service = NBADataService()
    private var refreshJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 当前选择的日期（用户本地时区显示，但API查询时转换为美东时间）
    private var selectedDate = LocalDate.now()

    // 比分列表实际使用的数据源（gameId 的归属源，供详情/文字转播跨源反查）
    private var currentGamesSource: DataSource = DataSource.fromId(NBASettingsState.getInstance().dataSource)

    // 子面板
    private val gamesPanel = JPanel()
    private var standingsPanel: StandingsPanel? = null

    // UI 组件
    private val dateLabel = JLabel()
    private val prevDayButton = JButton("◀")
    private val nextDayButton = JButton("▶")
    private val todayButton = JButton("今天")
    private val datePickerButton = JButton("📅")
    private val refreshButton = JButton("刷新")
    private val autoRefreshCheckBox = JCheckBox("自动刷新", false)
    private val dataSourceCombo = JComboBox(DataSource.values().map { it.displayName }.toTypedArray())
    private val statusLabel = JLabel("准备就绪")

    // Tab 容器
    private val tabbedPane = JTabbedPane()

    // 防止重复初始化
    private var isDataLoaded = false

    init {
        setupUI()
        // 延迟加载数据，避免插件打开时卡顿
        SwingUtilities.invokeLater {
            loadGames()
            setupAutoRefresh()
        }
    }

    companion object {
        /**
         * 将本地日期转换为美国东部时间的日期（用于API查询）
         */
        fun toEasternDate(localDate: LocalDate): LocalDate {
            val easternZone = ZoneId.of("America/New_York")
            val localZone = ZoneId.systemDefault()

            // 在本地时区的开始时间
            val localDateTime = localDate.atStartOfDay(localZone)
            // 转换为美东时间
            return localDateTime.withZoneSameInstant(easternZone).toLocalDate()
        }
    }
    
    private fun setupUI() {
        // ===================== Tab 1: 比分 =====================
        val scorePanel = JPanel(BorderLayout())
        
        // 顶部工具栏 - 日期选择
        val datePanel = JPanel(FlowLayout(FlowLayout.CENTER, 5, 2))
        prevDayButton.preferredSize = Dimension(40, 25)
        nextDayButton.preferredSize = Dimension(40, 25)
        todayButton.preferredSize = Dimension(50, 25)
        datePickerButton.preferredSize = Dimension(35, 25)
        
        datePanel.add(prevDayButton)
        datePanel.add(todayButton)
        datePanel.add(dateLabel)
        datePanel.add(datePickerButton)
        datePanel.add(nextDayButton)
        
        // 操作栏
        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 2))
        actionPanel.add(refreshButton)
        actionPanel.add(autoRefreshCheckBox)
        actionPanel.add(Box.createHorizontalStrut(10))
        actionPanel.add(JLabel("数据源:").apply { font = font.deriveFont(Font.PLAIN, 11f) })
        actionPanel.add(dataSourceCombo.apply {
            preferredSize = Dimension(110, 24)
            font = font.deriveFont(Font.PLAIN, 11f)
            toolTipText = "选择数据来源，主源失败时会自动降级到备用源"
        })
        actionPanel.add(Box.createHorizontalStrut(10))
        actionPanel.add(statusLabel)
        
        // 顶部容器
        val topPanel = JPanel(BorderLayout())
        topPanel.add(datePanel, BorderLayout.NORTH)
        topPanel.add(actionPanel, BorderLayout.SOUTH)
        
        // 设置按钮事件
        prevDayButton.addActionListener { changeDate(-1) }
        nextDayButton.addActionListener { changeDate(1) }
        todayButton.addActionListener {
            selectedDate = LocalDate.now()
            updateDateLabel()
            loadGames()
        }
        datePickerButton.addActionListener { showDatePicker() }
        refreshButton.addActionListener { loadGames() }
        autoRefreshCheckBox.addActionListener { setupAutoRefresh() }

        // 数据源切换
        dataSourceCombo.addActionListener {
            val selectedIndex = dataSourceCombo.selectedIndex
            if (selectedIndex >= 0 && selectedIndex < DataSource.values().size) {
                val selectedSource = DataSource.values()[selectedIndex]
                NBASettingsState.getInstance().dataSource = selectedSource.id
                loadGames()  // 重新加载数据
            }
        }
        
        // 初始化数据源选中值
        val currentSource = DataSource.fromId(NBASettingsState.getInstance().dataSource)
        dataSourceCombo.selectedIndex = DataSource.values().indexOf(currentSource).coerceAtLeast(0)

        // 更新日期
        updateDateLabel()
        
        // 比赛列表
        gamesPanel.layout = BoxLayout(gamesPanel, BoxLayout.Y_AXIS)
        gamesPanel.border = JBUI.Borders.empty(10)
        
        val scrollPane = JBScrollPane(gamesPanel)
        scrollPane.preferredSize = Dimension(350, 400)
        
        scorePanel.add(topPanel, BorderLayout.NORTH)
        scorePanel.add(scrollPane, BorderLayout.CENTER)
        
        // ===================== Tab 2: 排名 =====================
        standingsPanel = StandingsPanel(project)
        
        // 添加到 Tab 容器
        tabbedPane.addTab("比分", scorePanel)
        tabbedPane.addTab("排名", standingsPanel)
        
        // 设置 Tab 图标样式
        tabbedPane.font = tabbedPane.font.deriveFont(Font.BOLD, 12f)
        
        add(tabbedPane, BorderLayout.CENTER)
        preferredSize = Dimension(370, 480)
    }
    
    /**
     * 更新日期标签显示
     */
    private fun updateDateLabel() {
        val todayLocal = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern("MM月dd日 EEEE", Locale.CHINA)
        val dateStr = selectedDate.format(formatter)

        dateLabel.text = if (selectedDate == todayLocal) {
            "今天 ($dateStr)"
        } else if (selectedDate == todayLocal.minusDays(1)) {
            "昨天 ($dateStr)"
        } else if (selectedDate == todayLocal.plusDays(1)) {
            "明天 ($dateStr)"
        } else {
            dateStr
        }
    }
    
    /**
     * 切换日期
     */
    private fun changeDate(delta: Int) {
        selectedDate = selectedDate.plusDays(delta.toLong())
        updateDateLabel()
        loadGames()
    }
    
    /**
     * 显示日期选择器（月历点选）
     */
    private fun showDatePicker() {
        val dialog = JDialog(
            SwingUtilities.getWindowAncestor(this),
            "选择日期",
            java.awt.Dialog.ModalityType.APPLICATION_MODAL
        )
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.contentPane = MonthCalendarPanel(selectedDate) { chosen ->
            dialog.dispose()
            selectedDate = chosen
            updateDateLabel()
            loadGames()
        }
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    /**
     * 月历面板：◀ ▶ 翻月，点击日期直接选中并关闭
     */
    private inner class MonthCalendarPanel(
        initial: LocalDate,
        private val onPick: (LocalDate) -> Unit
    ) : JPanel(BorderLayout()) {

        private var currentMonth: YearMonth = YearMonth.from(initial)
        private val selectedDateInPicker: LocalDate = initial
        private val monthLabel = JLabel("", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }
        private val gridPanel = JPanel(GridLayout(0, 7, 3, 3))

        init {
            border = JBUI.Borders.empty(12)

            // 顶部：◀ 月份 ▶ + 回到今天
            val header = JPanel(BorderLayout(6, 0)).apply {
                add(JButton("◀").apply {
                    preferredSize = Dimension(45, 28)
                    isFocusable = false
                    addActionListener { currentMonth = currentMonth.minusMonths(1); rebuild() }
                }, BorderLayout.WEST)
                add(monthLabel, BorderLayout.CENTER)
                add(JButton("▶").apply {
                    preferredSize = Dimension(45, 28)
                    isFocusable = false
                    addActionListener { currentMonth = currentMonth.plusMonths(1); rebuild() }
                }, BorderLayout.EAST)
            }

            val todayButton = JButton("回到今天").apply {
                isFocusable = false
                addActionListener { onPick(LocalDate.now()) }
            }

            val north = JPanel(BorderLayout(0, 8))
            north.add(header, BorderLayout.NORTH)
            north.add(todayButton, BorderLayout.SOUTH)

            add(north, BorderLayout.NORTH)
            add(gridPanel, BorderLayout.CENTER)
            rebuild()
        }

        private fun rebuild() {
            monthLabel.text = "${currentMonth.year}年${currentMonth.monthValue}月"
            gridPanel.removeAll()

            // 星期标题行（周一开始）
            for (dow in DayOfWeek.entries) {
                gridPanel.add(JLabel(dow.getDisplayName(TextStyle.NARROW, Locale.CHINA)).apply {
                    horizontalAlignment = SwingConstants.CENTER
                    foreground = JBColor.GRAY
                    font = font.deriveFont(Font.PLAIN, 11f)
                })
            }

            // 首列补位（周一开始对齐）
            val leading = currentMonth.atDay(1).dayOfWeek.value - 1
            repeat(leading) { gridPanel.add(JLabel("")) }

            // 每日按钮
            val today = LocalDate.now()
            for (day in 1..currentMonth.lengthOfMonth()) {
                val date = currentMonth.atDay(day)
                gridPanel.add(JButton(day.toString()).apply {
                    isFocusable = false
                    preferredSize = Dimension(44, 32)
                    margin = Insets(2, 2, 2, 2)
                    if (date == today) {
                        font = font.deriveFont(Font.BOLD, 12f)
                        foreground = JBColor(0x0066CC, 0x4A9EFF)
                    }
                    if (date == selectedDateInPicker) {
                        isOpaque = true
                        background = JBColor(0x0066CC, 0x4A9EFF)
                    }
                    addActionListener { onPick(date) }
                })
            }

            // 尾部补齐整行
            val used = leading + currentMonth.lengthOfMonth()
            repeat((7 - used % 7) % 7) { gridPanel.add(JLabel("")) }

            gridPanel.revalidate()
            gridPanel.repaint()
        }
    }
    
    /**
     * 加载比赛数据
     */
    private fun loadGames() {
        statusLabel.text = "加载中..."
        refreshButton.isEnabled = false
        dataSourceCombo.isEnabled = false

        scope.launch {
            // 将用户选择的本地日期转换为美东日期进行API查询
            val queryDate = toEasternDate(selectedDate)
            DataSourceCommon.debugLog("加载比分: 本地日期=$selectedDate -> 美东查询日期=$queryDate")
            val (result, source) = service.getGamesWithSource(queryDate)
            currentGamesSource = source

            DataSourceCommon.debugLog(
                result.fold(
                    onSuccess = { "比分结果: ${source.displayName} 返回 ${it.games.size} 场" },
                    onFailure = { "比分结果: 失败 - ${it.message}" }
                )
            )

            ApplicationManager.getApplication().invokeLater {
                refreshButton.isEnabled = true
                dataSourceCombo.isEnabled = true
                result.fold(
                    onSuccess = { scoreboard ->
                        updateGamesPanel(scoreboard)
                        statusLabel.text = "✅ ${source.displayName} · 共 ${scoreboard.games.size} 场"
                    },
                    onFailure = { error ->
                        showError(error.message ?: "加载失败")
                        statusLabel.text = "❌ 加载失败"
                    }
                )
            }
        }
    }
    
    /**
     * 更新比赛列表显示
     */
    private fun updateGamesPanel(scoreboard: NBAScoreboard) {
        gamesPanel.removeAll()
        
        if (scoreboard.games.isEmpty()) {
            val emptyPanel = JPanel()
            emptyPanel.layout = BoxLayout(emptyPanel, BoxLayout.Y_AXIS)
            val emptyLabel = JLabel("该日期没有比赛安排 🏀")
            emptyLabel.alignmentX = Component.CENTER_ALIGNMENT
            emptyPanel.add(Box.createVerticalStrut(50))
            emptyPanel.add(emptyLabel)
            gamesPanel.add(emptyPanel)
        } else {
            for (game in scoreboard.games) {
                gamesPanel.add(createGameCard(game))
                gamesPanel.add(Box.createVerticalStrut(8))
            }
        }
        
        gamesPanel.revalidate()
        gamesPanel.repaint()
    }
    
    /**
     * 创建比赛卡片
     */
    private fun createGameCard(game: NBAGame): JPanel {
        val card = JPanel()
        card.layout = BorderLayout(5, 5)
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1),
            JBUI.Borders.empty(8)
        )
        card.background = UIManager.getColor("Panel.background") ?: JBColor.PanelBackground
        card.maximumSize = Dimension(Int.MAX_VALUE, 100)
        card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        
        // 点击打开详情页面
        card.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                showGameDetail(game)
            }
        })
        
        // 左侧：比赛状态
        val statusPanel = JPanel()
        statusPanel.layout = BoxLayout(statusPanel, BoxLayout.Y_AXIS)
        statusPanel.isOpaque = false
        
        val statusLabel = JLabel(game.getStatusDisplay()).apply {
            font = font.deriveFont(Font.BOLD, 13f)
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = when (game.status) {
                "in_progress" -> JBColor(0x0066CC, 0x4A9EFF)
                "finished" -> JBColor.GRAY
                else -> JBColor(0x333333, 0xCCCCCC)
            }
        }
        
        val timeLabel = JLabel(game.getClockDisplay()).apply {
            font = font.deriveFont(11f)
            foreground = JBColor.GRAY
            alignmentX = Component.CENTER_ALIGNMENT
        }
        
        statusPanel.add(statusLabel)
        statusPanel.add(Box.createVerticalStrut(2))
        statusPanel.add(timeLabel)
        
        // 右侧：队伍和比分
        val scorePanel = JPanel(GridBagLayout())
        scorePanel.isOpaque = false
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(2, 5, 2, 5)
        
        // 客队名称
        gbc.gridx = 0; gbc.gridy = 0
        gbc.weightx = 1.0
        scorePanel.add(JLabel(game.awayTeam.name).apply {
            font = font.deriveFont(Font.BOLD, 12f)
        }, gbc)
        
        // VS
        gbc.gridx = 1
        gbc.weightx = 0.0
        scorePanel.add(JLabel("vs").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(10f)
        }, gbc)
        
        // 主队名称
        gbc.gridx = 2
        gbc.weightx = 1.0
        scorePanel.add(JLabel(game.homeTeam.name).apply {
            font = font.deriveFont(Font.BOLD, 12f)
        }, gbc)
        
        // 客队比分
        gbc.gridx = 0; gbc.gridy = 1
        val awayScoreLabel = JLabel(if (game.status == "scheduled") "-" else game.awayScore.toString()).apply {
            font = font.deriveFont(Font.BOLD, 22f)
            horizontalAlignment = SwingConstants.CENTER
            foreground = if (game.awayScore > game.homeScore && game.status != "scheduled") {
                JBColor(0x0066CC, 0x4A9EFF)
            } else {
                JBColor.BLACK
            }
        }
        scorePanel.add(awayScoreLabel, gbc)
        
        // 中间空白
        gbc.gridx = 1
        scorePanel.add(Box.createHorizontalStrut(20), gbc)
        
        // 主队比分
        gbc.gridx = 2
        val homeScoreLabel = JLabel(if (game.status == "scheduled") "-" else game.homeScore.toString()).apply {
            font = font.deriveFont(Font.BOLD, 22f)
            horizontalAlignment = SwingConstants.CENTER
            foreground = if (game.homeScore > game.awayScore && game.status != "scheduled") {
                JBColor(0x0066CC, 0x4A9EFF)
            } else {
                JBColor.BLACK
            }
        }
        scorePanel.add(homeScoreLabel, gbc)
        
        // 底部操作条：左「📺 直播」（固定到直播标签页实时观看），右「点击查看详情」
        val detailHint = JLabel("点击查看详情 ›").apply {
            font = font.deriveFont(10f)
            foreground = JBColor.GRAY
            horizontalAlignment = SwingConstants.RIGHT
        }

        val liveLink = JLabel("📺 直播").apply {
            font = font.deriveFont(Font.BOLD, 10f)
            foreground = JBColor(0x0066CC, 0x4A9EFF)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    // 消费事件，避免冒泡触发卡片整体的「查看详情」
                    e?.consume()
                    onPlayByPlayClick?.invoke(game, game.status, buildGameRef(game))
                }
            })
        }

        val southPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(liveLink, BorderLayout.WEST)
            add(detailHint, BorderLayout.EAST)
        }

        card.add(statusPanel, BorderLayout.WEST)
        card.add(scorePanel, BorderLayout.CENTER)
        card.add(southPanel, BorderLayout.SOUTH)
        
        return card
    }
    
    /**
     * 构建跨源反查引用：各数据源 gameId 不通用，
     * 附上「归属源+美东日期+队名缩写」供详情/文字转播跨源定位同一场比赛
     */
    private fun buildGameRef(game: NBAGame): GameRef? {
        return if (game.homeTeam.abbreviation.isNotBlank() && game.awayTeam.abbreviation.isNotBlank()) {
            GameRef(
                toEasternDate(selectedDate),
                game.homeTeam.abbreviation,
                game.awayTeam.abbreviation,
                currentGamesSource
            )
        } else {
            null
        }
    }

    /**
     * 显示比赛详情
     */
    private fun showGameDetail(game: NBAGame) {
        val dialog = GameDetailDialog(
            project,
            game.gameId,
            game.homeTeam.name,
            game.awayTeam.name,
            game.homeTeam.id,
            game.awayTeam.id,
            buildGameRef(game),
            onOpenPlayByPlay = { status ->
                // 详情内点「文字转播」：关闭详情，固定到直播标签页实时观看
                onPlayByPlayClick?.invoke(game, status, buildGameRef(game))
            }
        )
        dialog.show()
    }
    
    /**
     * 显示错误信息
     */
    private fun showError(message: String) {
        gamesPanel.removeAll()
        val errorLabel = JLabel("❌ $message")
        errorLabel.foreground = JBColor.RED
        errorLabel.alignmentX = Component.CENTER_ALIGNMENT
        gamesPanel.add(errorLabel)
        gamesPanel.revalidate()
        gamesPanel.repaint()
    }
    
    /**
     * 设置自动刷新
     */
    private fun setupAutoRefresh() {
        refreshJob?.cancel()
        
        if (autoRefreshCheckBox.isSelected) {
            refreshJob = scope.launch {
                while (isActive) {
                    delay(60_000) // 60秒刷新一次
                    loadGames()
                }
            }
        }
    }
    
    fun dispose() {
        refreshJob?.cancel()
        scope.cancel()
        standingsPanel?.dispose()
    }
}
