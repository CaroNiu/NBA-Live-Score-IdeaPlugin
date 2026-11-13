package com.caro.nba

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * 得分走势折线图（手绘 Graphics2D，零依赖）
 *
 * 支持两种形态：
 * - 双系列（详情页每节得分：客队蓝/主队橙，数值错开标注）——主构造
 * - 单系列（球员资料卡逐场得分：单色，X 轴为场次标签）——[singleSeries] 工厂
 * 主题自适应（JBColor），数据不足两节不渲染。
 */
class ScoreTrendChart private constructor(
    private val series: List<Series>,
    private val xLabels: List<String>,
    private val chartHeight: Int,
    private val showYGrid: Boolean
) : JComponent() {

    /** 一条曲线：名称（图例）+ 颜色 + 逐点数值 */
    data class Series(val label: String, val color: Color, val values: List<Int>)

    /** 详情页双系列工厂：客队蓝 / 主队橙，X 轴 Q1..OTn */
    constructor(awayLines: List<String>, homeLines: List<String>) : this(
        series = listOf(
            Series("客队", JBColor(0x0066CC, 0x4A9EFF), awayLines.mapNotNull { it.toIntOrNull() }),
            Series("主队", JBColor(0xE67E22, 0xF39C12), homeLines.mapNotNull { it.toIntOrNull() })
        ),
        xLabels = (0 until minOf(awayLines.size, homeLines.size)).map { i ->
            if (i < 4) "Q${i + 1}" else "OT${i - 3}"
        },
        chartHeight = 150,
        showYGrid = true
    )

    /** 单系列工厂（球员资料卡逐场得分） */
    constructor(values: List<Int>, labels: List<String>, color: Color, legendLabel: String) : this(
        series = listOf(Series(legendLabel, color, values)),
        xLabels = labels,
        chartHeight = 130,
        showYGrid = false
    )

    private val gridColor = JBColor(0xE0E0E0, 0x3A3A3A)
    private val axisColor = JBColor.GRAY

    override fun getPreferredSize(): Dimension = Dimension(700, chartHeight)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val valid = series.map { it.values.filterNotNull().let { v -> v } }.map { s -> s.takeIf { it.size >= 2 } }
        val n = valid.minOfOrNull { it?.size ?: 0 } ?: return
        if (n < 2) return

        val padLeft = 40
        val padRight = 46
        val padTop = 34
        val padBottom = 24
        val chartW = width - padLeft - padRight
        val chartH = height - padTop - padBottom
        if (chartW <= 20 || chartH <= 20) return

        val maxScore = series.maxOf { s -> s.values.take(n).maxOrNull() ?: 0 }
        val yMax = ((maxScore + 4) / 5) * 5 + 5

        fun x(i: Int): Double = if (n == 1) chartW / 2.0 else padLeft + chartW * i.toDouble() / (n - 1)
        fun y(v: Int): Double = padTop + chartH * (1 - v.toDouble() / yMax)

        if (showYGrid) {
            for (i in 1..2) {
                val v = yMax * i / 3
                val yy = y(v)
                g2.color = gridColor
                g2.stroke = BasicStroke(1f)
                g2.drawLine(padLeft, yy.toInt(), padLeft + chartW, yy.toInt())
                g2.color = axisColor
                g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                g2.drawString(v.toString(), 8, yy.toInt() + 4)
            }
        }

        // X 轴标签（全画会挤，最多隔点画）
        g2.color = axisColor
        g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
        val labelStep = if (n > 10) (n + 9) / 10 else 1
        for (i in 0 until n step labelStep) {
            val label = xLabels.getOrNull(i) ?: ""
            if (label.isNotEmpty()) g2.drawString(label, x(i).toInt() - 10, height - 8)
        }

        series.forEachIndexed { si, s ->
            val values = s.values
            g2.color = s.color
            g2.stroke = BasicStroke(2f)
            for (i in 0 until n - 1) {
                g2.drawLine(x(i).toInt(), y(values[i]).toInt(), x(i + 1).toInt(), y(values[i + 1]).toInt())
            }
            for (i in 0 until n) {
                g2.fillOval(x(i).toInt() - 3, y(values[i]).toInt() - 3, 6, 6)
                // 数值标签错开：双系列时奇偶系列上/下，单系列一律上方
                val labelY = if (series.size == 2 && si == 1) y(values[i]).toInt() + 14
                else y(values[i]).toInt() - 6
                g2.font = Font(Font.SANS_SERIF, Font.BOLD, 10)
                g2.drawString(values[i].toString(), x(i).toInt() - 6, labelY)
            }
        }

        // 图例（右上角）
        g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        var lx = width - 40
        for (s in series.asReversed()) {
            val textW = g2.fontMetrics.stringWidth(s.label) + 24
            lx -= textW
            g2.color = s.color
            g2.fillRect(lx, 8, 10, 3)
            g2.drawString(s.label, lx + 14, 16)
        }
    }
}
