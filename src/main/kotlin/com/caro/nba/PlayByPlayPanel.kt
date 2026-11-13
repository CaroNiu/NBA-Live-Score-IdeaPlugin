package com.caro.nba

import com.caro.nba.datasource.DataSource
import com.caro.nba.model.PlayByPlay
import com.caro.nba.service.GameDetailService
import com.caro.nba.service.GameRef
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
 * 嵌入在工具窗口「📺 直播」标签页内（替代原先的弹框），支持：
 * - 工具栏展示对阵与实时比分（取自最新一条直播消息的比分字段）
 * - 切换数据来源（自动中文优先 / 直播吧 / 新浪 / ESPN），选择持久化到 NBASettingsState.playByPlaySource
 * - 进行中比赛每 15 秒自动刷新，完赛后自动停止
 *
 * 生命周期：由 NBAScoreMainPanel 持有，换场/工具窗口销毁时调用 [dispose] 取消协程与定时器
 */
class PlayByPlayPanel(
    private val gameId: String,
    private val homeTeamName: String,
    private val awayTeamName: String,
    private val homeTeamId: String,
    private val awayTeamId: String,
    private val gameStatus: String = "scheduled",
    private val gameRef: GameRef? = null  // 中文源 gameId 与 ESPN 不通用时用于跨源反查
) : JPanel(BorderLayout(0, 10)) {

    private val service = GameDetailService()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var loadingLabel: JLabel? = null
    private var infoLabel: JLabel? = null
    private var refreshButton: JButton? = null
    private var lastUpdateLabel: JLabel? = null
    private var sourceBadge: JLabel? = null
    private var sourceCombo: JComboBox<String>? = null
    private var scrollPane: JBScrollPane? = null

    /** 对阵与实时比分（客 X - Y 主 · 节次），每次渲染取最新一条直播消息更新 */
    private var gameTitleLabel: JLabel? = null

    /** 自动刷新定时器（进行中比赛每 30 秒拉一次，加载成功且未完赛时续约） */
    private var autoRefreshTimer: Timer? = null

    // ---- 弹幕（直播吧只读聚合，不提供发送） ----
    private val danmuService = com.caro.nba.datasource.impl.Zhibo8DanmuDataSource()
    private var danmuToggleButton: JButton? = null
    private var danmuScrollPane: JBScrollPane? = null
    private var danmuListPanel: JPanel? = null
    private var danmuTimer: Timer? = null
    private var danmuVisible = false
    /** 弹幕自动刷新开关（侧栏头部 checkbox，默认开） */
    private var danmuAutoRefreshCheckBox: JCheckBox? = null
    /** 侧栏头部「最后刷新 HH:mm:ss」（轮询状态可见化） */
    private var danmuRefreshLabel: JLabel? = null
    private var danmuLoading = false
    /** 已展示的最大 order_id（增量拉取游标） */
    private var lastDanmuOrderId = -1L

    /** 文字直播容器（loadingLabel/scrollPane/infoLabel 三态挂载点，弹幕分栏的左侧） */
    private var mainCenter: JPanel? = null

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

        /** 自动刷新间隔：轮询只拉 max_sid + 最新分块，开销小，15 秒保证实时性 */
        private const val AUTO_REFRESH_INTERVAL_MS = 15_000

        /** 弹幕轮询间隔（只读展示，30 秒足够） */
        private const val DANMU_POLL_INTERVAL_MS = 30_000
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
        border = JBUI.Borders.empty(10)
        buildUi()

        // 延迟加载数据（等组件挂到容器后）
        SwingUtilities.invokeLater {
            loadPlayByPlay()
        }
    }

    private fun buildUi() {
        // 顶部工具栏：刷新 + 来源切换 | 比赛状态 | 来源徽标 + 更新时间
        // 两行布局：第一行比分（最重要），第二行控制按钮（避免按钮过多挤压比分）
        val toolbar = JPanel(BorderLayout(0, 4))
        val controlRow = JPanel(BorderLayout())
        toolbar.add(controlRow, BorderLayout.CENTER)

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
        westBox.add(Box.createHorizontalStrut(8))
        // 字号调节（文字直播/弹幕正文，持久化）
        val minusBtn = JButton("A-").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            margin = JBUI.insets(2, 6, 2, 6)
            toolTipText = "减小正文字号"
            addActionListener { adjustContentFontSize(-1f) }
        }
        val plusBtn = JButton("A+").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            margin = JBUI.insets(2, 6, 2, 6)
            toolTipText = "增大正文字号"
            addActionListener { adjustContentFontSize(+1f) }
        }
        westBox.add(minusBtn)
        westBox.add(Box.createHorizontalStrut(2))
        westBox.add(plusBtn)
        controlRow.add(westBox, BorderLayout.WEST)

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

        gameTitleLabel = JLabel("$awayTeamName vs $homeTeamName").apply {
            font = font.deriveFont(Font.BOLD, 15f)
            foreground = JBColor.foreground()
        }
        // 「📺 视频直播入口」：官方渠道导流（腾讯本场/CCTV5/咪咕/League Pass），合规边界见 WhereToWatchDialog
        val whereToWatchLabel = JLabel("📺 视频直播入口").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor(0x0066CC, 0x4A9EFF)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = "查看本场官方转播渠道（腾讯/CCTV5/咪咕/League Pass）"
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    openWhereToWatch()
                }
            })
        }
        // 比分行独占第一行（最重要的信息不被控制按钮挤压）
        val scoreRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 10, 2)).apply {
            isOpaque = false
            add(statusLabel)
            add(gameTitleLabel)
            add(whereToWatchLabel)
        }
        toolbar.add(scoreRow, BorderLayout.NORTH)

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
        eastBox.add(Box.createHorizontalStrut(8))
        danmuToggleButton = JButton("💬 弹幕").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            margin = JBUI.insets(2, 8, 2, 8)
            toolTipText = "切换显示直播吧弹幕（只读）"
            addActionListener { toggleDanmu() }
        }
        eastBox.add(danmuToggleButton)
        controlRow.add(eastBox, BorderLayout.EAST)

        add(toolbar, BorderLayout.NORTH)

        // 加载中提示
        loadingLabel = JLabel("正在加载文字转播...", SwingConstants.CENTER).apply {
            font = font.deriveFont(16f)
        }

        // 创建空的 scrollPane
        scrollPane = JBScrollPane().apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            isVisible = false
        }
        // CENTER：文字直播容器（左）与弹幕视图（右）组成的分栏；弹幕默认隐藏（divider 推到最右）
        val leftContainer = JPanel(BorderLayout()).apply {
            isOpaque = false
        }.also { mainCenter = it }
        leftContainer.add(loadingLabel, BorderLayout.CENTER)

        danmuListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        val danmuHeader = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 5)).apply {
            border = JBUI.Borders.empty(2, 10, 2, 10)
            add(JLabel("💬 直播吧弹幕 · 只读").apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = JBColor(0x0066CC, 0x4A9EFF)
            })
            danmuAutoRefreshCheckBox = JCheckBox("自动", true).apply {
                font = font.deriveFont(11f)
                isOpaque = false
                toolTipText = "自动刷新（每 30 秒）"
                addActionListener {
                    if (isSelected) startDanmuPolling() else stopDanmuPolling()
                }
            }
            add(danmuAutoRefreshCheckBox)
            // 轮询状态可见化：每次拉取更新，时间不走 = 轮询停了；走了但无新弹幕 = 比赛已结束正常停滞
            danmuRefreshLabel = JLabel("").apply {
                font = font.deriveFont(10f)
                foreground = JBColor.GRAY
            }
            add(danmuRefreshLabel)
        }
        val danmuView = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(danmuHeader, BorderLayout.NORTH)
            add(JBScrollPane(danmuListPanel), BorderLayout.CENTER)
        }
        danmuScrollPane = JBScrollPane(danmuView)

        val splitPane = javax.swing.JSplitPane(
            javax.swing.JSplitPane.HORIZONTAL_SPLIT, leftContainer, danmuScrollPane
        ).apply {
            isOpaque = false
            resizeWeight = 1.0
            dividerLocation = Int.MAX_VALUE  // 初始：弹幕侧隐藏
            isOneTouchExpandable = false
        }
        leftContainer.add(scrollPane, BorderLayout.CENTER)
        add(splitPane, BorderLayout.CENTER)
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
        scope.launch {            val (result, actualSource) = service.getPlayByPlay(
                gameId, homeTeamId, awayTeamId, gameRef, override
            )
            SwingUtilities.invokeLater {
                loading = false
                setControlsEnabled(true)
                result.fold(
                    onSuccess = { pbp ->
                        showPlayByPlay(pbp, actualSource, override == null)
                        updateLastRefreshTime()
                        // 有数据且未完赛才开启轮询；拉取失败不停轮询（下一周期自动重试）
                        if (pbp.plays.isNotEmpty()) ensureAutoRefresh(pbp)
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
     * 自动刷新：比赛进行中（未完赛）每 30 秒拉一次，无需手动点刷新。
     *
     * 定时器为周期触发 + loading 标志防重入，而非「单次触发成功续约」——
     * 后者一旦某轮拉取失败链路即永久断掉，表现成「不实时刷新」。
     * 停止条件：直播流出现「比赛结束/终场」，或状态快照本就是完赛。
     */
    private fun ensureAutoRefresh(pbp: PlayByPlay) {
        // 直播吧流完赛时 pid_text 为「比赛结束」；列表顺序无保证，任意一条命中即视为已完赛
        val gameEnded = pbp.plays.any {
            it.periodDisplay.contains("结束") || it.periodDisplay.contains("终场")
        }
        if (gameEnded || isGameFinished) {
            stopAutoRefresh()
            return
        }
        if (autoRefreshTimer?.isRunning == true) return

        autoRefreshTimer = Timer(AUTO_REFRESH_INTERVAL_MS) { loadPlayByPlay() }.apply {
            isRepeats = true  // 周期触发；某轮失败显示错误后，下一周期自动重试
            start()
        }
    }

    private fun stopAutoRefresh() {
        autoRefreshTimer?.stop()
        autoRefreshTimer = null
    }

    // ---------------- 弹幕（直播吧只读聚合） ----------------

    /** 切换弹幕侧栏显示：开=divider 拉到 55% 并启动轮询；关=推到最右并停止轮询 */
    private fun toggleDanmu() {
        danmuVisible = !danmuVisible
        if (danmuVisible) UsageTracker.track("danmu")
        danmuToggleButton?.text = if (danmuVisible) "📄 文字直播" else "💬 弹幕"
        val split = findSplitPane() ?: return
        if (danmuVisible) {
            split.dividerLocation = (split.width.coerceAtLeast(300) * 0.55).toInt()
            startDanmuPolling()
        } else {
            split.dividerLocation = split.width.coerceAtLeast(1)
            stopDanmuPolling()
        }
    }

    /** 找到 CENTER 的分栏（构造后挂载，弹幕首开时可能尚未索引） */
    private fun findSplitPane(): javax.swing.JSplitPane? =
        components.filterIsInstance<javax.swing.JSplitPane>().firstOrNull()

    private fun startDanmuPolling() {
        if (danmuTimer?.isRunning == true) return
        loadDanmu()  // 打开立即拉一次
        danmuTimer = Timer(DANMU_POLL_INTERVAL_MS) { loadDanmu() }.apply {
            isRepeats = true
            start()
        }
    }

    private fun stopDanmuPolling() {
        danmuTimer?.stop()
        danmuTimer = null
    }

    /**
     * 拉弹幕：首次打开回填最近 60 条（并行翻页），之后轮询只取增量（order_id > 游标）。
     * 新消息插入列表顶部（最新在最上，与文字直播一致）。
     */
    private fun loadDanmu() {
        if (danmuLoading || gameId.isBlank()) return
        danmuLoading = true
        val year = gameRef?.easternDate?.year ?: java.time.LocalDate.now().year
        val firstLoad = lastDanmuOrderId == -1L
        scope.launch {
            val fresh = (if (firstLoad) {
                danmuService.recent(gameId, year, count = 60)
            } else {
                danmuService.latest(gameId, year)
            }).getOrNull()
                ?.filter { it.orderId > lastDanmuOrderId }
                ?: emptyList()
            javax.swing.SwingUtilities.invokeLater {
                danmuLoading = false
                danmuRefreshLabel?.text = "最后刷新 " +
                    java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                if (fresh.isEmpty()) return@invokeLater
                lastDanmuOrderId = lastDanmuOrderId.coerceAtLeast(fresh.maxOf { it.orderId })
                val list = danmuListPanel ?: return@invokeLater
                // 最新在最上（与文字直播一致）
                val followed = NBASettingsState.getInstance().followedTeams
                for (d in fresh.sortedByDescending { it.orderId }) {
                    // 命中关注球队名的弹幕高亮（绿字 + 🌟 前缀），快速捕捉本队相关讨论
                    val hit = followed.firstOrNull { d.content.contains(it) }
                    val body = if (hit != null) {
                        "🌟<b style='color:#1E7B34'>${escDanmu(d.content)}</b>"
                    } else {
                        escDanmu(d.content)
                    }
                    list.add(
                        JLabel("<html><div style='width:100%'>" +
                                "<span style='color:gray'>${escDanmu(d.time.takeLast(5))}</span> $body" +
                                "</div></html>").apply {
                            font = font.deriveFont(NBASettingsState.getInstance().contentFontSize)
                            putClientProperty("danmuRow", true)
                            border = JBUI.Borders.empty(2, 8, 2, 8)
                            alignmentX = Component.LEFT_ALIGNMENT
                        }
                    )
                }
                // 上限保护：弹幕列表最多保留 200 条（移除底部旧项）
                while (list.componentCount > 200) {
                    list.remove(list.componentCount - 1)
                }
                list.revalidate()
                list.repaint()
            }
        }
    }

    private fun escDanmu(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** A-/A+：正文字号步进调节（11~18），持久化并即时应用到文字直播正文与弹幕行 */
    private fun adjustContentFontSize(delta: Float) {
        val settings = NBASettingsState.getInstance()
        val newSize = (settings.contentFontSize + delta).coerceIn(11f, 18f)
        if (newSize == settings.contentFontSize) return
        settings.contentFontSize = newSize
        applyContentFontSize()
    }

    /** 遍历文字直播内容与弹幕列表，即时应用新字号（只动正文 JTextArea 与弹幕行 JLabel） */
    private fun applyContentFontSize() {
        val size = NBASettingsState.getInstance().contentFontSize
        fun applyRecursive(c: java.awt.Container) {
            for (comp in c.components) {
                when (comp) {
                    is javax.swing.JTextArea -> comp.font = comp.font.deriveFont(size)
                    is javax.swing.JLabel -> if (comp.getClientProperty("danmuRow") == true) {
                        comp.font = comp.font.deriveFont(size)
                    }
                }
                if (comp is java.awt.Container) applyRecursive(comp)
            }
        }
        (scrollPane?.viewport?.view as? java.awt.Container)?.let { applyRecursive(it) }
        danmuListPanel?.let { applyRecursive(it) }
        revalidate()
        repaint()
    }

    private fun showPlayByPlay(pbp: PlayByPlay, actualSource: DataSource, isAutoMode: Boolean) {
        loadingLabel?.isVisible = false
        sourceBadge?.text = badgeText(actualSource)

        // 对阵实时比分：plays 按 sid 升序（见 buildPlays），最后一条即最新——比分字段随消息滚动更新
        pbp.plays.lastOrNull()?.let { latest ->
            gameTitleLabel?.text =
                "$awayTeamName ${latest.awayScore} - ${latest.homeScore} $homeTeamName · ${latest.periodDisplay}"
        }

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
            revalidate()
            repaint()
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

        // 各数据源的 plays 均为时间升序（直播吧按 live_sid、新浪按发帖时间、ESPN 按 sequenceNumber），
        // 倒序渲染即「最新在最上」。此处不能按 clock 重排：直播吧/新浪的 clock 是发帖时间「HH:mm」
        // （分钟精度），同一分钟内所有消息排序键相同，稳定排序会保留源顺序（旧消息在上），
        // 与整页「最新在上」方向相反——同分钟的连续播报（如「第一罚/第二罚/命中」）读起来就是错位的。
        // （ESPN 不出错纯属侥幸：其 clock 是比赛时钟，数字递减，降序恰好等于最新在前）
        // 节标题：period 变化时插入（倒序遍历=新节在前）；period=0 表示未识别节次，不渲染组头
        var lastPeriod = Int.MIN_VALUE
        for (play in pbp.plays.asReversed()) {
            if (play.period > 0 && play.period != lastPeriod) {
                lastPeriod = play.period
                content.add(JLabel("━━━ ${play.periodDisplay.ifEmpty { "第${play.period}节" }} ━━━").apply {
                    font = font.deriveFont(Font.BOLD, 13f)
                    foreground = JBColor(0x0066CC, 0x4A9EFF)
                    alignmentX = Component.CENTER_ALIGNMENT
                    border = EmptyBorder(10, 0, 10, 0)
                })
            }
            content.add(createPlayPanel(play))
        }

        // 更新 scrollPane 内容
        scrollPane?.viewport?.view = content
        scrollPane?.isVisible = true
        loadingLabel?.isVisible = false

        revalidate()
        repaint()

        // 最新消息固定在列表顶部：每次刷新（手动/自动）后回到顶部。
        // 布局完成后再次归零——JBScrollPane 在滚动条位于底部时有「贴底」惯性，
        // 仅在布局前设置会被后续布局过程覆盖回底部
        scrollPane?.verticalScrollBar?.value = 0
        SwingUtilities.invokeLater {
            scrollPane?.verticalScrollBar?.value = 0
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
            font = font.deriveFont(NBASettingsState.getInstance().contentFontSize)
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
        infoLabel?.let { mainCenter?.remove(it) }
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
        mainCenter?.add(infoLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
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
        mainCenter?.add(infoLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * 打开「视频直播入口」弹窗：优先匹配腾讯体育本场（官方比赛页），附 CCTV5/咪咕/League Pass 官方入口。
     * 纯官方导流，不解析直播流（合规边界见 WhereToWatchDialog）。
     */
    private fun openWhereToWatch() {
        scope.launch {
            val ds = com.caro.nba.datasource.impl.TencentBroadcastDataSource()
            val broadcast = ds.recentBroadcasts().getOrNull()
                ?.let { ds.findBroadcast(it, awayTeamName, homeTeamName) }
                ?.let { it.copy(isPay = ds.fetchIsPay(it.mid)) }
            val channelText = ds.homepageChannels().getOrNull()
                ?.firstOrNull { it.matches(awayTeamName, homeTeamName) }?.channelText ?: ""
            javax.swing.SwingUtilities.invokeLater {
                WhereToWatchDialog(null, broadcast, awayTeamName, homeTeamName, channelText).show()
            }
        }
    }

    /**
     * 释放面板资源（协程 + 自动刷新定时器）。
     * 由持有方（NBAScoreMainPanel）在换场或工具窗口销毁时调用，非 Disposable 回调
     */
    fun dispose() {
        stopAutoRefresh()
        stopDanmuPolling()
        scope.cancel()
    }
}
