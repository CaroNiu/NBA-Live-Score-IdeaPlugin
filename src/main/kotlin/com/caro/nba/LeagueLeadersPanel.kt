package com.caro.nba

import com.caro.nba.model.LeaderCategory
import com.caro.nba.model.LeaderEntry
import com.caro.nba.service.LeagueLeadersService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingConstants

/**
 * 数据王面板（排名页第三个 tab）
 *
 * - 今日榜：当日全部比赛的单场最佳聚合（scoreboard leaders，零额外接口）
 * - 赛季榜：场均 Top5（ESPN core API，球员名经 $ref 二次解析）
 * - 数据固定来自 ESPN，与顶部数据源下拉无关
 * - 首次加入显示树（addNotify）才拉取；今日/赛季两个子 tab 各自只加载一次
 */
class LeagueLeadersPanel : JPanel(BorderLayout()) {

    private val service = LeagueLeadersService()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val todayListPanel = JPanel()
    private val seasonListPanel = JPanel()
    private val todayDateLabel = JLabel(" ")
    private val seasonHintLabel = JLabel(" ")

    private var todayLoaded = false
    private var seasonLoaded = false

    private val subTabs = JTabbedPane(JTabbedPane.TOP)

    init {
        background = JBColor.background()

        val baseFont = subTabs.font
        subTabs.apply {
            this.font = baseFont.deriveFont(Font.BOLD, 13f)
            background = JBColor.background()
        }

        todayListPanel.layout = BoxLayout(todayListPanel, BoxLayout.Y_AXIS)
        todayListPanel.background = JBColor.background()
        seasonListPanel.layout = BoxLayout(seasonListPanel, BoxLayout.Y_AXIS)
        seasonListPanel.background = JBColor.background()

        val todayHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 2, 12)
            add(todayDateLabel, BorderLayout.WEST)
            add(JLabel("单场数据 · 来源 ESPN").apply { foreground = JBColor.GRAY; font = font.deriveFont(11f) }, BorderLayout.EAST)
        }
        val todayWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(todayHeader, BorderLayout.NORTH)
            add(todayListPanel, BorderLayout.CENTER)
        }

        val seasonHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 2, 12)
            add(seasonHintLabel, BorderLayout.WEST)
            add(JLabel("场均数据").apply { foreground = JBColor.GRAY; font = font.deriveFont(11f) }, BorderLayout.EAST)
        }
        val seasonWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(seasonHeader, BorderLayout.NORTH)
            add(seasonListPanel, BorderLayout.CENTER)
        }

        subTabs.addTab("今日榜", JBScrollPane(todayWrap))
        subTabs.addTab("赛季榜", JBScrollPane(seasonWrap))

        subTabs.addChangeListener {
            when (subTabs.selectedIndex) {
                0 -> if (!todayLoaded) loadToday()
                1 -> if (!seasonLoaded) loadSeason()
            }
        }

        add(subTabs, BorderLayout.CENTER)
    }

    /** 组件真正加入显示树时才首拉，避免设置页预构建时的无用请求 */
    override fun addNotify() {
        super.addNotify()
        if (!todayLoaded) loadToday()
    }

    /** 今日数据王 */
    private fun loadToday() {
        todayLoaded = true
        todayDateLabel.text = "加载中..."
        scope.launch {
            val eastern = NBAScorePanel.toEasternDate(LocalDate.now())
            val result = service.todayLeaders(eastern)
            ApplicationManager.getApplication().invokeLater {
                result.fold(
                    onSuccess = { leaders ->
                        todayDateLabel.text = if (leaders.isEmpty()) "今日暂无比赛数据" else
                            "今日（${LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))}）· 来源 ${leaders.source}"
                        renderCategories(todayListPanel, leaders.categories, emptyHint = "今日暂无比赛数据")
                    },
                    onFailure = { renderError(todayListPanel, "数据不可达（${it.message ?: "网络异常"}）") }
                )
            }
        }
    }

    /** 赛季数据王 */
    private fun loadSeason() {
        seasonLoaded = true
        seasonHintLabel.text = "加载中..."
        scope.launch {
            val result = service.seasonLeaders()
            ApplicationManager.getApplication().invokeLater {
                result.fold(
                    onSuccess = { leaders ->
                        seasonHintLabel.text = "${leaders.season} 赛季 · 来源 ${leaders.source}"
                        renderCategories(seasonListPanel, leaders.categories, emptyHint = "新赛季尚未产生统计数据")
                    },
                    onFailure = {
                        seasonHintLabel.text = " "
                        renderError(seasonListPanel, "数据不可达（${it.message ?: "网络异常"}）")
                    }
                )
            }
        }
    }

    /** 渲染三类目榜单（空数据时显示占位提示） */
    private fun renderCategories(panel: JPanel, categories: List<LeaderCategory>, emptyHint: String) {
        panel.removeAll()
        val hasData = categories.any { it.entries.isNotEmpty() }
        if (!hasData) {
            panel.add(Box.createVerticalStrut(40))
            panel.add(JLabel(emptyHint).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = JBColor.GRAY
            })
        } else {
            for (category in categories) {
                panel.add(categoryHeader(category))
                panel.add(Box.createVerticalStrut(4))
                if (category.entries.isEmpty()) {
                    panel.add(JLabel("  暂无数据").apply { foreground = JBColor.GRAY; font = font.deriveFont(12f) })
                } else {
                    category.entries.forEachIndexed { i, entry ->
                        panel.add(leaderRow(i + 1, entry))
                        panel.add(Box.createVerticalStrut(2))
                    }
                }
                panel.add(Box.createVerticalStrut(14))
            }
        }
        panel.add(Box.createVerticalGlue())
        panel.revalidate()
        panel.repaint()
    }

    private fun categoryHeader(category: LeaderCategory): JComponent =
        JLabel(category.displayName).apply {
            font = font.deriveFont(Font.BOLD, 14f)
            border = JBUI.Borders.empty(6, 12, 2, 12)
            alignmentX = Component.LEFT_ALIGNMENT
        }

    /** 单行：排名徽章 + 球员名 + 数值（前三名淡色底） */
    private fun leaderRow(rank: Int, entry: LeaderEntry): JComponent {
        val row = JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 34)
            border = JBUI.Borders.empty(3, 12, 3, 12)
            background = if (rank <= 3) JBColor(0xF8F9FA, 0x2D2D2D) else JBColor.background()
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val left = JPanel(BorderLayout(10, 0)).apply { isOpaque = false }
        left.add(JLabel(rank.toString()).apply {
            preferredSize = Dimension(24, 24)
            horizontalAlignment = SwingConstants.CENTER
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = Color.WHITE
            background = when (rank) {
                1 -> JBColor(0xE03A3E, 0xC90C2E)
                2 -> JBColor(0xF39C12, 0xE67E22)
                3 -> JBColor(0xF1C40F, 0xF39C12)
                else -> JBColor.GRAY
            }
            isOpaque = true
        }, BorderLayout.WEST)
        left.add(JLabel(entry.playerName.ifEmpty { "（球员信息解析失败）" }).apply {
            font = font.deriveFont(Font.BOLD, 13f)
        }, BorderLayout.CENTER)

        val value = JLabel(entry.displayValue).apply {
            font = font.deriveFont(Font.BOLD, 13f)
            horizontalAlignment = SwingConstants.RIGHT
        }

        row.add(left, BorderLayout.WEST)
        row.add(value, BorderLayout.EAST)
        return row
    }

    private fun renderError(panel: JPanel, message: String) {
        panel.removeAll()
        panel.add(Box.createVerticalStrut(40))
        panel.add(JLabel("⚠️ $message").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = JBColor.GRAY
        })
        panel.add(Box.createVerticalGlue())
        panel.revalidate()
        panel.repaint()
    }

    fun dispose() {
        scope.cancel()
    }
}
