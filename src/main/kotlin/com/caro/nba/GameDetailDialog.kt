package com.caro.nba

import com.caro.nba.datasource.DataSource
import com.caro.nba.model.GameDetail
import com.caro.nba.service.GameDetailService
import com.caro.nba.service.GameRef
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ActionEvent
import java.net.URI
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * 比赛详情对话框 - 展示球员统计数据
 *
 * @param gameRef 比赛引用信息：中文数据源的 gameId 与 ESPN 不通用，用于失败时反查 ESPN eventId
 * @param onOpenPlayByPlay 点「文字转播」时的回调（参数为详情加载后的最新比赛状态），
 *                          由比分面板提供——固定到工具窗口「📺 直播」标签页实时观看并关闭本对话框
 */
class GameDetailDialog(
    private val project: Project,
    private val gameId: String,
    private val homeTeamName: String,
    private val awayTeamName: String,
    private val homeTeamId: String = "",
    private val awayTeamId: String = "",
    private val gameRef: GameRef? = null,
    private val onOpenPlayByPlay: ((status: String) -> Unit)? = null
) : DialogWrapper(project) {

    private val service = GameDetailService()

    private var mainPanel: JPanel? = null
    private var contentPanel: JPanel? = null
    private var loadingLabel: JLabel? = null
    private var currentStatus: String = "scheduled"  // 存储比赛状态

    init {
        title = "$awayTeamName vs $homeTeamName"
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent? {
        mainPanel = JPanel(BorderLayout())
        mainPanel?.preferredSize = Dimension(880, 620)

        // 加载中提示
        loadingLabel = JLabel("正在加载比赛详情...", SwingConstants.CENTER).apply {
            font = font.deriveFont(16f)
            border = EmptyBorder(50, 0, 50, 0)
        }
        mainPanel?.add(loadingLabel, BorderLayout.CENTER)

        // 后台线程加载数据
        Thread {
            try {
                val (result, actualSource) = service.getGameDetail(gameId, gameRef)
                result.fold(
                    onSuccess = { detail ->
                        SwingUtilities.invokeLater { showGameDetail(detail, actualSource) }
                    },
                    onFailure = { error ->
                        SwingUtilities.invokeLater { showError(friendlyError(error.message)) }
                    }
                )
            } catch (e: Exception) {
                SwingUtilities.invokeLater { showError(e.message ?: "未知错误") }
            }
        }.start()

        return mainPanel
    }

    // 文字转播按钮：固定到工具窗口「📺 直播」标签页实时观看，并关闭本详情
    override fun createActions(): Array<Action> {
        val playByPlayAction = object : AbstractAction("📺 文字转播") {
            override fun actionPerformed(e: ActionEvent?) {
                onOpenPlayByPlay?.invoke(currentStatus)
                close(OK_EXIT_CODE)
            }
        }
        return arrayOf(playByPlayAction, okAction)
    }

    private fun showGameDetail(detail: GameDetail, actualSource: DataSource) {
        currentStatus = detail.status  // 存储当前比赛状态
        
        mainPanel?.removeAll()

        contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
        }

        // 1. 比分区域
        addScoreSection(detail)

        // 2. 球员统计数据表格
        if (detail.players != null) {
            addPlayerStatsSection(detail)
        } else {
            // 中文源 boxscore 不含球员统计；若走了跨源反查，说明实际数据来路避免「选直播吧却提示新浪」的困惑
            val idSource = gameRef?.idSource
            val hint = if (idSource != null && idSource != actualSource) {
                "所选数据源（${idSource.displayName}）不提供比赛详情，<br>以上比分来自 ${actualSource.displayName} 的反查数据，不含球员统计。<br>" +
                        "如需球员数据请在比分面板切换到 ESPN 数据源后重新打开。"
            } else {
                "当前数据源（${actualSource.displayName}）不提供球员统计数据，<br>" +
                        "如需球员数据请在比分面板切换到 ESPN 数据源后重新打开。"
            }
            contentPanel?.add(JLabel("<html><div style='padding: 10px; text-align: center;'>$hint</div></html>").apply {
                font = font.deriveFont(12f)
                foreground = JBColor.GRAY
                alignmentX = Component.CENTER_ALIGNMENT
            })
            contentPanel?.add(Box.createVerticalStrut(15))
        }

        val scrollPane = JBScrollPane(contentPanel).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        mainPanel?.add(scrollPane, BorderLayout.CENTER)
        mainPanel?.revalidate()
        mainPanel?.repaint()
    }

    private fun addScoreSection(detail: GameDetail) {
        val panel = JPanel(BorderLayout(10, 0)).apply {
            border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                "比分"
            )
//            maximumSize = Dimension(Int.MAX_VALUE, 120)
            // 移除最大高度限制，让面板根据内容自适应
            preferredSize = Dimension(840, 160)
        }

        // 比分显示
        val scorePanel = JPanel(GridLayout(2, 3, 20, 10)).apply {
            border = JBUI.Borders.empty(10)
        }

        // 客队（detail 队名缺失时用比分列表传入的队名兜底）
        scorePanel.add(JLabel(detail.awayTeam.name.ifBlank { awayTeamName }).apply {
            font = font.deriveFont(Font.BOLD, 14f)
            horizontalAlignment = SwingConstants.CENTER
        })
        scorePanel.add(JLabel("VS").apply {
            font = font.deriveFont(12f)
            foreground = JBColor.GRAY
            horizontalAlignment = SwingConstants.CENTER
        })
        scorePanel.add(JLabel(detail.homeTeam.name.ifBlank { homeTeamName }).apply {
            font = font.deriveFont(Font.BOLD, 14f)
            horizontalAlignment = SwingConstants.CENTER
        })

        // 比分
        scorePanel.add(JLabel(detail.awayTeam.score.toString()).apply {
            font = font.deriveFont(Font.BOLD, 42f)
            horizontalAlignment = SwingConstants.CENTER
            foreground = if (detail.awayTeam.score > detail.homeTeam.score) JBColor(0x0066CC, 0x4A9EFF) else JBColor.BLACK
        })
        scorePanel.add(JLabel("-").apply {
            font = font.deriveFont(28f)
            foreground = JBColor.GRAY
            horizontalAlignment = SwingConstants.CENTER
        })
        scorePanel.add(JLabel(detail.homeTeam.score.toString()).apply {
            font = font.deriveFont(Font.BOLD, 42f)
            horizontalAlignment = SwingConstants.CENTER
            foreground = if (detail.homeTeam.score > detail.awayTeam.score) JBColor(0x0066CC, 0x4A9EFF) else JBColor.BLACK
        })

        panel.add(scorePanel, BorderLayout.CENTER)

        // 状态
        val statusText = when (detail.status) {
            "scheduled" -> "未开始"
            "in_progress" -> "进行中 ${if (detail.period > 4) "OT${detail.period - 4}" else "第${detail.period}节"}"
            "finished" -> "已结束"
            else -> detail.status
        }
        val statusLabel = JLabel(statusText).apply {
            font = font.deriveFont(13f)
            horizontalAlignment = SwingConstants.CENTER
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(3)
        }
        panel.add(statusLabel, BorderLayout.SOUTH)

        contentPanel?.add(panel)
        contentPanel?.add(Box.createVerticalStrut(10))
    }

    private fun addPlayerStatsSection(detail: GameDetail) {
        val players = detail.players ?: return

        // 客队球员表格
        if (players.awayPlayers.isNotEmpty()) {
            val awayPanel = createTeamPlayersPanel(detail.awayTeam.name, players.awayPlayers, players.awayTotals)
            contentPanel?.add(awayPanel)
            contentPanel?.add(Box.createVerticalStrut(15))
        }

        // 主队球员表格
        if (players.homePlayers.isNotEmpty()) {
            val homePanel = createTeamPlayersPanel(detail.homeTeam.name, players.homePlayers, players.homeTotals)
            contentPanel?.add(homePanel)
        }
    }

    private fun createTeamPlayersPanel(teamName: String, players: List<GameDetail.Player>, totals: GameDetail.Player?): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                "$teamName 球员数据"
            )
            maximumSize = Dimension(Int.MAX_VALUE, 400)
        }

        // 表格列：与直播吧数据页一致，不缩减
        val columnNames = arrayOf(
            "球员", "位置", "出场时间", "投篮", "三分", "罚球",
            "前篮板", "后篮板", "总篮板", "助攻", "抢断", "盖帽", "失误", "犯规", "+/-", "得分"
        )
        val model = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        players.forEach { player ->
            model.addRow(arrayOf(
                "${player.name} (#${player.jersey})",
                player.position,
                player.minutes,
                "${player.fgMade}-${player.fgAttempts}",
                "${player.threeMade}-${player.threeAttempts}",
                "${player.ftMade}-${player.ftAttempts}",
                player.offensiveRebounds.ifBlank { "-" },
                player.defensiveRebounds.ifBlank { "-" },
                player.rebounds,
                player.assists,
                player.steals,
                player.blocks,
                player.turnovers,
                player.fouls.ifBlank { "-" },
                player.plusMinus,
                player.points
            ))
        }

        // 球队总计行（直播吧提供；ESPN 无则不出）
        if (totals != null) {
            model.addRow(arrayOf(
                "总计", "", totals.minutes,
                "${totals.fgMade}-${totals.fgAttempts}",
                "${totals.threeMade}-${totals.threeAttempts}",
                "${totals.ftMade}-${totals.ftAttempts}",
                totals.offensiveRebounds.ifBlank { "-" },
                totals.defensiveRebounds.ifBlank { "-" },
                totals.rebounds,
                totals.assists,
                totals.steals,
                totals.blocks,
                totals.turnovers,
                totals.fouls.ifBlank { "-" },
                totals.plusMinus,
                totals.points
            ))
        }

        val table = JTable(model).apply {
            rowHeight = 25
            font = font.deriveFont(12f)
            setGridColor(JBColor.border())

            // 列宽：球员列稍宽，其余紧凑
            val widths = intArrayOf(120, 48, 64, 62, 56, 56, 48, 48, 48, 42, 42, 42, 42, 42, 44, 46)
            for (i in widths.indices) {
                columnModel.getColumn(i).preferredWidth = widths[i]
            }

            // 居中对齐数字列
            val centerRenderer = DefaultTableCellRenderer().apply {
                horizontalAlignment = SwingConstants.CENTER
            }
            for (i in 1 until columnCount) {
                columnModel.getColumn(i).cellRenderer = centerRenderer
            }

            // 球员名称列左对齐，并添加点击事件
            columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    foreground = JBColor(0x0066CC, 0x4A9EFF)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    return this
                }
            }

            // 点击球员名称打开详情（总计行不在 players 范围内，自动排除）
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (col == 0 && row >= 0 && row < players.size) {
                        val player = players[row]
                        openPlayerDetail(player)
                    }
                }
            })
        }

        val scrollPane = JScrollPane(table).apply {
            preferredSize = Dimension(840, ((players.size + 1) * 25 + 30).coerceAtMost(300))
        }

        panel.add(scrollPane, BorderLayout.CENTER)

        // 点击提示
        val hintLabel = JLabel("💡 点击球员名称查看详情").apply {
            font = font.deriveFont(10f)
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(5)
            horizontalAlignment = SwingConstants.RIGHT
        }
        panel.add(hintLabel, BorderLayout.SOUTH)

        return panel
    }

    private fun openPlayerDetail(player: GameDetail.Player) {
        val options = arrayOf("打开 ESPN 球员页面", "取消")
        val choice = JOptionPane.showOptionDialog(
            mainPanel,
            "查看 ${player.name} 的详细信息？",
            "球员详情",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]
        )

        if (choice == 0) {
            try {
                // 使用 ESPN 球员页面 URL
                val url = if (player.playerUrl.isNotEmpty()) {
                    player.playerUrl
                } else {
                    "https://www.espn.com/nba/player/_/id/${player.id}"
                }
                Desktop.getDesktop().browse(URI(url))
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(mainPanel, "无法打开链接: ${e.message}")
            }
        }
    }

    private fun showError(message: String) {
        mainPanel?.removeAll()
        // JLabel 不支持 \n 换行，用 html 排版多行提示
        val errorLabel = JLabel("<html><div style='text-align: center; padding: 20px;'>❌ $message</div></html>").apply {
            foreground = JBColor.RED
            font = font.deriveFont(14f)
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }
        mainPanel?.add(errorLabel, BorderLayout.CENTER)
        mainPanel?.revalidate()
        mainPanel?.repaint()
    }

    /**
     * 将底层报错翻译为用户可理解的提示
     */
    private fun friendlyError(message: String?): String {
        val msg = message ?: "加载失败"
        return if (msg.contains("所有数据源均失败")) {
            "无法获取比赛详情。<br>中文源未收录该场比赛的详情数据，<br>且 ESPN 反查也失败（当前网络可能无法访问 ESPN）。"
        } else {
            msg
        }
    }
}