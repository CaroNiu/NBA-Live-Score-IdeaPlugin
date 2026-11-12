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
 * 每节得分走势折线图（手绘 Graphics2D，零依赖）
 *
 * X 轴 = 节次（Q1..QT），Y 轴 = 单节得分；两条曲线（客队蓝 / 主队橙）+ 数据点 + 端点数值。
 * 主题自适应（JBColor），数据不足两节不渲染。
 */
class ScoreTrendChart(
    private val awayLines: List<String>,
    private val homeLines: List<String>
) : JComponent() {

    private val awayColor = JBColor(0x0066CC, 0x4A9EFF)
    private val homeColor = JBColor(0xE67E22, 0xF39C12)
    private val gridColor = JBColor(0xE0E0E0, 0x3A3A3A)
    private val axisColor = JBColor.GRAY

    override fun getPreferredSize(): Dimension = Dimension(700, 150)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val away = awayLines.mapNotNull { it.toIntOrNull() }
        val home = homeLines.mapNotNull { it.toIntOrNull() }
        val n = minOf(away.size, home.size)
        if (n < 2) return

        val padLeft = 40
        val padRight = 46
        val padTop = 34
        val padBottom = 24
        val chartW = width - padLeft - padRight
        val chartH = height - padTop - padBottom
        if (chartW <= 20 || chartH <= 20) return

        val maxScore = (away.take(n) + home.take(n)).maxOrNull() ?: 0
        val yMax = ((maxScore + 4) / 5) * 5 + 5  // 取 5 的倍数留出头部空间

        fun x(i: Int): Double = if (n == 1) chartW / 2.0 else padLeft + chartW * i.toDouble() / (n - 1)
        fun y(v: Int): Double = padTop + chartH * (1 - v.toDouble() / yMax)

        // 网格横线 + Y 轴刻度（3 条）
        g2.color = gridColor
        g2.stroke = BasicStroke(1f)
        for (i in 1..2) {
            val v = yMax * i / 3
            val yy = y(v)
            g2.drawLine(padLeft, yy.toInt(), padLeft + chartW, yy.toInt())
            g2.color = axisColor
            g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
            g2.drawString(v.toString(), 8, yy.toInt() + 4)
            g2.color = gridColor
        }
        // X 轴节次标签
        g2.color = axisColor
        g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
        for (i in 0 until n) {
            val label = if (i < 4) "Q${i + 1}" else "OT${i - 3}"
            g2.drawString(label, x(i).toInt() - 8, height - 8)
        }

        // 两条曲线（数值标签错开：客队上、主队下，避免同节重叠）
        fun drawSeries(values: List<Int>, color: Color, labelAbove: Boolean) {
            g2.color = color
            g2.stroke = BasicStroke(2f)
            for (i in 0 until n - 1) {
                g2.drawLine(
                    x(i).toInt(), y(values[i]).toInt(),
                    x(i + 1).toInt(), y(values[i + 1]).toInt()
                )
            }
            for (i in 0 until n) {
                g2.fillOval(x(i).toInt() - 3, y(values[i]).toInt() - 3, 6, 6)
                g2.font = Font(Font.SANS_SERIF, Font.BOLD, 10)
                val labelY = if (labelAbove) y(values[i]).toInt() - 6 else y(values[i]).toInt() + 14
                val labelX = x(i).toInt() - 6
                g2.drawString(values[i].toString(), labelX, labelY)
            }
        }
        drawSeries(away.take(n), awayColor, labelAbove = true)
        drawSeries(home.take(n), homeColor, labelAbove = false)

        // 图例（右上角，避免顶部截断）
        val legendY = 16
        g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        var lx = width - 130
        g2.color = awayColor
        g2.fillRect(lx, legendY - 8, 10, 3)
        g2.drawString("客队", lx + 14, legendY)
        lx += 50
        g2.color = homeColor
        g2.fillRect(lx, legendY - 8, 10, 3)
        g2.drawString("主队", lx + 14, legendY)
    }
}
