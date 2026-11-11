package com.caro.nba

import com.caro.nba.model.NBABroadcast
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 「在哪看」弹窗：官方转播渠道导流
 *
 * ⚠️ 合规边界：只链官方页面（腾讯比赛页/CCTV5 节目单/咪咕/League Pass），不提供任何第三方直播流。
 * 各场次是否免费转播以平台页面为准。
 */
class WhereToWatchDialog(
    project: Project?,
    private val matched: NBABroadcast?,   // 与比分卡匹配到的腾讯场次（可能为 null）
    private val awayName: String,
    private val homeName: String,
    private val channelText: String = ""  // 直播吧渠道标注原文（如 "CCTV5 腾讯视频 全美直播"）
) : DialogWrapper(project) {

    companion object {
        /** 官方渠道静态入口 */
        private val CHANNELS = listOf(
            Triple("📺 CCTV5 节目单（央视持权转播）", "https://tv.cctv.com/epg/", "节目单"),
            Triple("📺 腾讯体育（NBA 持权转播，部分场次免费）", "https://kbs.sports.qq.com/nba/", "NBA 频道"),
            Triple("📺 咪咕视频（NBA 持权转播）", "https://www.miguvideo.com/", "首页"),
            Triple("🏀 NBA League Pass（官方国际版）", "https://watch.nba.com/", "官方入口")
        )

        /** 央视网 CCTV5 官方免费直播页（电视信号网络同步，免费） */
        private const val CCTV5_LIVE_URL = "https://tv.cctv.com/live/cctv5/"
    }

    init {
        title = "在哪看：$awayName vs $homeName"
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(460, 300)
        }

        // 比赛信息头
        panel.add(
            JLabel("<html><b style='font-size:14px'>${esc(awayName)} vs ${esc(homeName)}</b></html>").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(4, 0, 8, 0)
            }
        )

        // CCTV5 电视转播（直播吧渠道标注）：免费真直播，直达央视网官方直播页
        val hasCctv = channelText.contains("CCTV5")
        if (hasCctv) {
            panel.add(channelRow("▶ CCTV5 正在/即将转播本场 —— 打开央视网官方直播页（免费）", CCTV5_LIVE_URL))
            panel.add(Box.createVerticalStrut(6))
        }

        // 匹配到的腾讯场次：按官方 isPay 标注显示免费/会员，直入该场比赛页
        if (matched != null) {
            val statusText = when {
                matched.statusText.isNotEmpty() -> "（${matched.statusText}）"
                matched.startTimeBeijing.length >= 16 -> "（北京时间 ${matched.startTimeBeijing.substring(11, 16)} 开球）"
                else -> ""
            }
            val payHint = when (matched.isPay) {
                false -> "✅ 本场免费直播（官方免费场）"
                true -> "🔒 本场为会员场"
                null -> if (matched.isGold) "金牌场，通常需会员" else "是否免费以页面为准"
            }
            val playbackHint = if (matched.hasPlayback) " · 支持回放" else ""
            val row = channelRow("📺 腾讯体育 · $payHint$statusText$playbackHint", matched.gamePageUrl())
            // 免费场绿色高亮
            if (matched.isPay == false) {
                row.foreground = JBColor(0x1E7B34, 0x6FCF8B)
            }
            panel.add(row)
            panel.add(Box.createVerticalStrut(6))
        }

        // 通用官方渠道
        panel.add(JLabel("全部官方观看渠道：").apply {
            font = font.deriveFont(Font.BOLD, 12f)
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(6, 0, 4, 0)
        })
        for ((title, url, _) in CHANNELS) {
            panel.add(channelRow(title, url))
            panel.add(Box.createVerticalStrut(4))
        }

        panel.add(Box.createVerticalStrut(10))
        panel.add(
            JLabel("本插件仅提供官方平台入口导流，不提供第三方直播流；场次是否免费转播以平台页面为准。").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
        )
        return panel
    }

    /** 可点击渠道行（下划线蓝字，浏览器打开官方页） */
    private fun channelRow(title: String, url: String): JComponent =
        JLabel("<html><a href=''>$title</a></html>").apply {
            font = font.deriveFont(13f)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            alignmentX = JComponent.LEFT_ALIGNMENT
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    BrowserUtil.browse(url)
                }
            })
        }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
}
