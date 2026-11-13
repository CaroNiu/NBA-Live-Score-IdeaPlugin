package com.caro.nba

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

/**
 * 球员资料卡弹窗（插件内嵌查看，不跳浏览器）——网页内容全量同步
 *
 * 数据：直播吧 nba_v2_player 接口（playerId 与单场统计的 player_id 同体系）。
 * 展示（自上而下）：
 * 1. 基本资料（中英文名/队名/号码/位置/身高/体重/生日/国家/选秀/球龄/薪金）
 * 2. 职业生涯 · 场均（逐年逐队全表）
 * 3. 职业生涯 · 总计（逐年逐队全表）
 * 4. 季后赛 · 场均（有数据才显示）
 * 5. 近期比赛（近 5 场全列）
 * 字段解析均为防御式；数据源失败时上层兜底打开浏览器资料页。
 */
class PlayerCardDialog(
    project: Project?,
    private val fallbackName: String,
    private val data: JsonObject
) : DialogWrapper(project) {

    init {
        title = "球员资料卡"
        setOKButtonText("关闭")
        init()
    }

    // ---- 防御取值 ----
    private fun JsonObject.str(vararg keys: String): String =
        keys.firstNotNullOfOrNull { k ->
            get(k)?.takeIf { it.isJsonPrimitive && it.asString.isNotBlank() }?.asString
        } ?: "-"

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    override fun createCenterPanel(): JComponent {
        val player = data.obj("player")
        val displayName = player?.str("displayName")?.takeIf { it != "-" } ?: fallbackName

        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(980, 660)

        // ---- 头部：左侧头像 + 右侧姓名与资料网格 ----
        val code = player?.str("playerCode")?.takeIf { it != "-" } ?: ""
        val headerPanel = JPanel(BorderLayout(16, 0)).apply {
            border = JBUI.Borders.empty(10, 16, 10, 16)
        }

        // 头像（异步加载 avatar，失败降级 playerLogo，均失败显示占位）
        val avatarUrl = player?.str("avatar")?.takeIf { it != "-" }
            ?: player?.str("playerLogo")?.takeIf { it != "-" } ?: ""
        val avatarLabel = JLabel("🏀").apply {
            preferredSize = Dimension(116, 116)
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            font = font.deriveFont(48f)
            border = JBUI.Borders.customLine(JBColor.border(), 1)
        }
        if (avatarUrl.isNotBlank()) {
            Thread {
                // 用项目共享 OkHttp 拉字节（沙箱/代理环境下比 ImageIO.read(URL) 的 URLConnection 更可靠）
                val img = runCatching {
                    val bytes = com.caro.nba.datasource.DataSourceCommon.client
                        .newCall(okhttp3.Request.Builder().url(avatarUrl).build())
                        .execute().use { resp ->
                            if (!resp.isSuccessful) null else resp.body?.bytes()
                        }
                    bytes?.let { b -> javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(b)) }
                }.getOrNull()
                if (img != null) {
                    // 居中裁正方形（原图为竖版 2:3，直接缩放会偏移），再缩放到 110×110
                    val side = minOf(img.width, img.height)
                    val sx = (img.width - side) / 2
                    val sy = (img.height - side) / 2
                    val square = img.getSubimage(sx, sy, side, side)
                    val scaled = square.getScaledInstance(110, 110, java.awt.Image.SCALE_SMOOTH)
                    javax.swing.SwingUtilities.invokeLater { avatarLabel.icon = javax.swing.ImageIcon(scaled) }
                }
            }.start()
        }
        headerPanel.add(avatarLabel, BorderLayout.WEST)

        // 右侧：姓名大字 + 两列资料表（HTML table，整齐对齐）
        val nameHtml = StringBuilder("<b style='font-size:17px'>${esc(displayName)}</b>")
        if (code != "-" && code != displayName) nameHtml.append("　<span style='color:gray'>$code</span>")
        val infoRows = listOf(
            listOf("球队" to player?.str("teamName"), "号码" to player?.str("jerseyNo")),
            listOf("位置" to player?.str("position"), "身高" to player?.str("height")),
            listOf("体重" to player?.str("weight"), "生日" to player?.str("dob")),
            listOf("国家" to player?.str("country"), "选秀" to player?.str("draftYear")),
            listOf("球龄" to player?.str("experience"), "薪金" to player?.str("salary"))
        )
        val infoTable = infoRows.joinToString("") { row ->
            "<tr>" + row.joinToString("") { (label, v) ->
                val value = v ?: "-"
                if (value == "-") "<td></td><td></td>"
                else "<td style='color:gray;padding-right:8px'>$label</td><td>${esc(value)}</td>"
            } + "</tr>"
        }
        val rightPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel("<html>$nameHtml<br><table style='font-size:12px;margin-top:6px'>$infoTable</table></html>").apply {
                font = font.deriveFont(13f)
            }, BorderLayout.CENTER)
        }
        headerPanel.add(rightPanel, BorderLayout.CENTER)

        // ---- 全量表区域（垂直堆叠 + 滚动） ----
        val tablesPanel = JPanel()
        tablesPanel.layout = javax.swing.BoxLayout(tablesPanel, javax.swing.BoxLayout.Y_AXIS)

        buildStatTable(data.obj("career")?.obj("average"))?.let {
            tablesPanel.add(sectionTitle("职业生涯 · 场均"))
            tablesPanel.add(wrapTable(it))
        }
        buildStatTable(data.obj("career")?.obj("total"))?.let {
            tablesPanel.add(sectionTitle("职业生涯 · 总计"))
            tablesPanel.add(wrapTable(it))
        }
        buildStatTable(data.obj("playoff")?.obj("average"))?.let {
            tablesPanel.add(sectionTitle("季后赛 · 场均"))
            tablesPanel.add(wrapTable(it))
        }
        // 赛季逐场得分走势（seasonGames.list 按日期升序取最近 20 场，单系列折线）
        val seasonGames = data.obj("seasonGames")?.getAsJsonArray("list")
        if (seasonGames != null && seasonGames.size() > 0) {
            val rows = seasonGames.mapNotNull { it?.takeIf { e -> e.isJsonObject }?.asJsonObject }
                .sortedBy { it.str("日期") }
                .takeLast(20)
            if (rows.size >= 2) {
                val values = rows.mapNotNull { it.str("得分").toIntOrNull() }
                if (values.size >= 2) {
                    tablesPanel.add(sectionTitle("本季得分走势（最近 ${values.size} 场）"))
                    val chart = ScoreTrendChart(
                        values = values,
                        labels = rows.map { it.str("日期").take(5).substring(5) },  // "MM-dd"
                        color = JBColor(0x0066CC, 0x4A9EFF),
                        legendLabel = "单场得分"
                    )
                    tablesPanel.add(
                        JScrollPane(chart).apply {
                            preferredSize = Dimension(940, 170)
                            border = JBUI.Borders.empty(2, 12, 6, 12)
                            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                        }
                    )
                }
            }
        }

        buildStatTable(data.obj("recent5Games"))?.let {
            tablesPanel.add(sectionTitle("近期比赛"))
            tablesPanel.add(wrapTable(it))
        }

        val scroll = JBScrollPane(tablesPanel)
        scroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scroll.border = JBUI.Borders.empty()

        val south = JLabel("数据来源 直播吧").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(11f)
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.empty(4)
        }

        panel.add(headerPanel, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        panel.add(south, BorderLayout.SOUTH)
        return panel
    }

    /** 通用表格构建：items=表头，list=行数据（中文键对象），任一缺失返回 null */
    private fun buildStatTable(section: JsonObject?): JTable? {
        if (section == null) return null
        val itemsArr = section.getAsJsonArray("items") ?: return null
        val listArr = section.getAsJsonArray("list") ?: return null
        if (itemsArr.size() == 0 || listArr.size() == 0) return null

        val columns = itemsArr.map { it.asString }
        val model = object : DefaultTableModel(columns.toTypedArray(), 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        for (rowElement in listArr) {
            val rowObj = rowElement?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            model.addRow(columns.map { col -> rowObj.str(col) }.toTypedArray())
        }
        return JTable(model).apply {
            font = font.deriveFont(12f)
            rowHeight = 24
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            tableHeader.font = font.deriveFont(Font.BOLD, 12f)
            showVerticalLines = true
            showHorizontalLines = true
        }
    }

    private fun wrapTable(table: JTable): JComponent = JScrollPane(table).apply {
        preferredSize = Dimension(940, (table.rowCount * table.rowHeight + 28).coerceAtMost(180))
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        border = JBUI.Borders.empty(2, 12, 6, 12)
    }

    private fun sectionTitle(title: String): JComponent = JLabel(title).apply {
        font = font.deriveFont(Font.BOLD, 13f)
        border = JBUI.Borders.empty(10, 12, 2, 12)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
}
