package com.caro.nba

import com.caro.nba.model.NBATeamSchedule
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
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
import javax.swing.SwingConstants

/**
 * 球队赛程弹窗：近 5 场（含 W/L 徽章与比分）+ 未来 5 场（对手与本地开球时刻）。
 * 数据来自 ESPN teams/{abbr}/schedule；入口为比分卡片上的球队名点击。
 */
class TeamScheduleDialog(project: Project?, private val schedule: NBATeamSchedule) : DialogWrapper(project) {

    init {
        title = "${schedule.teamName} · 赛程"
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10)).apply {
            preferredSize = Dimension(420, 440)
        }

        // 顶部：队名 + 缩写
        panel.add(
            JLabel("<html><b style='font-size:15px'>${esc(schedule.teamName)}</b>　<span style='color:gray'>${esc(schedule.teamAbbr)}</span></html>").apply {
                horizontalAlignment = SwingConstants.CENTER
                border = JBUI.Borders.emptyBottom(4)
            },
            BorderLayout.NORTH
        )

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
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

        panel.add(JBScrollPane(body), BorderLayout.CENTER)

        panel.add(
            JLabel("数据来源 ESPN · 点击比分卡片上的队名随时查看").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
                horizontalAlignment = SwingConstants.CENTER
            },
            BorderLayout.SOUTH
        )
        return panel
    }

    // ---------------- 行组件 ----------------

    private fun sectionTitle(text: String): JComponent = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD, 13f)
        border = JBUI.Borders.empty(6, 4, 2, 4)
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

    /** 已打完：日期 | vs 对手(主/客) | W/L 徽章 + 比分 */
    private fun recentRow(g: NBATeamSchedule.ScheduleGame): JComponent {
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
    private fun upcomingRow(g: NBATeamSchedule.ScheduleGame): JComponent {
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
