package com.caro.nba

import com.caro.nba.model.NBATeamSchedule
import com.caro.nba.model.NBATeamSchedule.ScheduleGame
import com.caro.nba.model.TableSection
import com.caro.nba.model.TeamInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.ScrollPaneConstants

/**
 * 球队信息弹窗（球队维度三件套）：📋 赛程 / 👥 阵容与数据 / 🏥 伤病
 *
 * - 赛程：近 5 场（W/L 徽章与比分）+ 未来 5 场（对手与本地开球时刻）——ESPN teams/{abbr}/schedule
 * - 阵容与数据、伤病名单：直播吧 nba_v2/team 接口（teamId 体系，中文数据）
 * 入口为比分卡片上的球队名点击。teamInfo 为 null（非直播吧源）时仅显示赛程 tab。
 */
class TeamScheduleDialog(
    project: Project?,
    private val schedule: NBATeamSchedule,
    private val teamInfo: TeamInfo?
) : DialogWrapper(project) {

    init {
        title = "${schedule.teamName} · 球队信息"
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JTabbedPane(JTabbedPane.TOP)
        tabs.font = tabs.font.deriveFont(Font.BOLD, 12f)

        // Tab1: 赛程（近 5 场 + 未来 5 场）
        val schedulePanel = JPanel(BorderLayout(0, 8)).apply { isOpaque = false }
        schedulePanel.add(scheduleSectionComponent(), BorderLayout.CENTER)
        tabs.addTab("📋 赛程", JBScrollPane(schedulePanel))

        // Tab2/3: 阵容与伤病（teamInfo 提供表格数据）
        teamInfo?.let { info ->
            val infoPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                info.tableSections.forEach { sec ->
                    add(sectionTitle(sec.title))
                    add(Box.createVerticalStrut(4))
                    add(wrapTable(buildTable(sec)))
                    add(Box.createVerticalStrut(10))
                }
                add(Box.createVerticalGlue())
            }
            tabs.addTab("👥 阵容与数据", JBScrollPane(infoPanel))

            val injuryPanel = JPanel(BorderLayout(0, 6)).apply { isOpaque = false }
            val injuries = info.tableSections.firstOrNull { it.title == "伤病名单" }
            if (injuries != null) {
                injuryPanel.add(sectionTitle("🏥 伤病名单"), BorderLayout.NORTH)
                injuryPanel.add(wrapTable(buildTable(injuries)), BorderLayout.CENTER)
            } else {
                injuryPanel.add(JLabel("当前无伤病名单数据").apply {
                    foreground = JBColor.GRAY
                    horizontalAlignment = SwingConstants.CENTER
                }, BorderLayout.CENTER)
            }
            tabs.addTab("🏥 伤病", JBScrollPane(injuryPanel))
        }

        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(560, 520)
        panel.add(tabs, BorderLayout.CENTER)
        panel.add(
            JLabel("赛程来源 ESPN · 阵容/伤病来源 直播吧 · 点击比分卡片上的队名随时查看").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
                horizontalAlignment = SwingConstants.CENTER
                border = JBUI.Borders.empty(4)
            },
            BorderLayout.SOUTH
        )
        return panel
    }

    // ---------------- 组件构建 ----------------

    private fun sectionTitle(text: String): JComponent = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD, 13f)
        border = JBUI.Borders.empty(6, 4, 2, 4)
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

    private fun wrapTable(table: JTable): JComponent = JBScrollPane(table).apply {
        preferredSize = Dimension(560, (table.rowCount * table.rowHeight + 28).coerceAtMost(170))
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        border = JBUI.Borders.empty(2, 4, 6, 4)
    }

    private fun buildTable(section: TableSection): JTable {
        val model = object : javax.swing.table.DefaultTableModel(section.headers.toTypedArray(), 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        section.rows.forEach { model.addRow(it.toTypedArray()) }
        return javax.swing.JTable(model).apply {
            font = font.deriveFont(12f)
            rowHeight = 24
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            tableHeader.font = tableHeader.font.deriveFont(Font.BOLD, 12f)
            showHorizontalLines = true
            packColumns(this)
        }
    }

    /** 列宽按内容自适应（表头 + 最长单元格，钳制 45~220），消除默认 75px 的截断感 */
    private fun packColumns(table: javax.swing.JTable) {
        for (i in 0 until table.columnCount) {
            val col = table.columnModel.getColumn(i)
            var w = table.tableHeader.getFontMetrics(table.tableHeader.font)
                .stringWidth(col.headerValue.toString()) + 26
            for (r in 0 until table.rowCount) {
                val v = table.getValueAt(r, i)?.toString() ?: ""
                w = maxOf(w, table.getFontMetrics(table.font).stringWidth(v) + 20)
            }
            col.preferredWidth = w.coerceIn(45, 220)
            col.minWidth = 45
        }
    }

    private fun scheduleSectionComponent(): JComponent {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        body.add(sectionTitle(if (schedule.recentGames.isEmpty()) "近期无已结束比赛" else "近 ${schedule.recentGames.size} 场"))
        schedule.recentGames.forEach { g ->
            body.add(recentRow(g))
            body.add(Box.createVerticalStrut(3))
        }

        body.add(Box.createVerticalStrut(10))
        body.add(sectionTitle(if (schedule.upcomingGames.isEmpty()) "暂无可查的后续赛程" else "未来 ${schedule.upcomingGames.size} 场"))
        schedule.upcomingGames.forEach { g ->
            body.add(upcomingRow(g))
            body.add(Box.createVerticalStrut(3))
        }
        body.add(Box.createVerticalGlue())
        return body
    }

    /** 已打完：日期 | vs 对手(主/客) | W/L 徽章 + 比分 */
    private fun recentRow(g: ScheduleGame): JComponent {
        val row = JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            border = JBUI.Borders.empty(3, 4, 3, 4)
            background = if (g.win) JBColor(0xEFF7EF, 0x1F3323) else JBColor(0xFDF0F0, 0x3A2020)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        row.add(JLabel("${formatLocal(g.dateIso)}　${if (g.isHome) "vs" else "@"} ${esc(g.opponentName)}").apply {
            font = font.deriveFont(12f)
        }, BorderLayout.WEST)

        val right = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        right.add(JLabel(g.scoreDisplay).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            horizontalAlignment = SwingConstants.RIGHT
        }, BorderLayout.WEST)
        right.add(JLabel(if (g.win) "胜" else "负").apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = if (g.win) JBColor(0x1E7B34, 0x6FCF8B) else JBColor(0xC0392B, 0xE77C6E)
            horizontalAlignment = SwingConstants.RIGHT
        }, BorderLayout.EAST)
        row.add(right, BorderLayout.EAST)
        return row
    }

    /** 未开赛：本地时间 | vs/@ 对手 */
    private fun upcomingRow(g: ScheduleGame): JComponent {
        val row = JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            border = JBUI.Borders.empty(3, 4, 3, 4)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        row.add(JLabel("${formatLocal(g.dateIso)}　${if (g.isHome) "vs" else "@"} ${esc(g.opponentName)}").apply {
            font = font.deriveFont(12f)
        }, BorderLayout.WEST)
        return row
    }

    /** ESPN ISO UTC 时间 → 本地「M月d日 HH:mm」 */
    private fun formatLocal(dateIso: String): String {
        val local = runCatching {
            LocalDateTime.parse(dateIso.take(19))
                .atZone(ZoneId.of("UTC"))
                .withZoneSameInstant(ZoneId.systemDefault())
        }.getOrNull() ?: return dateIso
        return "${local.monthValue}月${local.dayOfMonth}日 ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
}
