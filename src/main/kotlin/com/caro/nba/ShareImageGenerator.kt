package com.caro.nba

import com.caro.nba.model.NBAGame
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * 比分分享图生成器（纯客户端 Swing 2D）
 *
 * 将一场比赛渲染成社交传播友好的深色卡片（1000×520）：
 * 顶部日期与品牌条 → 左右两队（圆形 logo，网络加载失败降级为缩写徽章）→ 中央大号比分与状态徽章 → 底部水印。
 *
 * 线程约定：generate 含 logo 网络请求，必须在后台线程调用；结果图经 EDT 展示/复制。
 */
object ShareImageGenerator {

    private const val W = 1000
    private const val H = 520

    /** 底部水印（传播锚点：仓库名） */
    private const val WATERMARK = "NBA Live Score · IDEA 插件   |   github.com/CaroNiu/NBA-Live-Score-IdeaPlugin"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 中文大字用微软雅黑，数字比分用 Arial 加粗；未安装该字体的系统由 JVM 自动回退 */
    private val fontZh = Font("Microsoft YaHei", Font.BOLD, 34)
    private val fontZhSmall = Font("Microsoft YaHei", Font.PLAIN, 22)
    private val fontBrand = Font("Arial", Font.BOLD, 20)
    private val fontScore = Font("Arial", Font.BOLD, 96)
    private val fontBadge = Font("Microsoft YaHei", Font.BOLD, 24)
    private val fontWatermark = Font("Microsoft YaHei", Font.PLAIN, 16)

    /** 生成分享图（后台线程调用：含 logo 网络加载） */
    fun generate(game: NBAGame, dateLabel: String): BufferedImage {
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // 深色渐变背景（深蓝夜场配色）
        g.paint = GradientPaint(0f, 0f, Color(0x0F, 0x20, 0x27), W.toFloat(), H.toFloat(), Color(0x2C, 0x53, 0x64))
        g.fillRect(0, 0, W, H)

        // 顶部：日期（左）与品牌（右）
        g.font = fontZhSmall
        g.color = Color(255, 255, 255, 190)
        g.drawString(dateLabel, 48, 70)
        g.font = fontBrand
        g.color = Color(255, 255, 255, 120)
        val brand = "NBA LIVE SCORE"
        g.drawString(brand, W - 48 - g.fontMetrics.stringWidth(brand), 70)

        // 左右两队：圆形 logo（失败降级缩写徽章）+ 中文队名
        val cy = 260
        val r = 72
        drawTeam(g, loadLogo(game.awayTeam.logo, r * 2), game.awayTeam.name, 170, cy, r)
        drawTeam(g, loadLogo(game.homeTeam.logo, r * 2), game.homeTeam.name, W - 170, cy, r)

        // 中央比分：未开赛显示 VS
        val scoreText = if (game.status == "scheduled") "VS" else "${game.awayScore} - ${game.homeScore}"
        g.font = fontScore
        g.color = Color.WHITE
        val scoreW = g.fontMetrics.stringWidth(scoreText)
        g.drawString(scoreText, (W - scoreW) / 2, cy + 34)

        // 状态徽章（圆角半透明）
        val badge = when (game.status) {
            "scheduled" -> "未开始 · ${game.getClockDisplay()}"
            "in_progress" -> "${game.getStatusDisplay()} ${game.clock}".trim()
            else -> "终场"
        }
        g.font = fontBadge
        val bw = g.fontMetrics.stringWidth(badge) + 48
        val bh = 52
        g.color = Color(255, 255, 255, 36)
        g.fill(RoundRectangle2D.Float((W - bw) / 2f, (cy + 78).toFloat(), bw.toFloat(), bh.toFloat(), 26f, 26f))
        g.color = Color(255, 255, 255, 230)
        g.drawString(badge, (W - g.fontMetrics.stringWidth(badge)) / 2, cy + 78 + 36)

        // 底部水印
        g.font = fontWatermark
        g.color = Color(255, 255, 255, 110)
        g.drawString(WATERMARK, (W - g.fontMetrics.stringWidth(WATERMARK)) / 2, H - 28)

        g.dispose()
        return img
    }

    /** 一侧队伍：圆形 logo + 下方居中中文队名 */
    private fun drawTeam(g: Graphics2D, logo: Image?, name: String, cx: Int, cy: Int, r: Int) {
        if (logo != null) {
            val oldClip = g.clip
            g.clip = Ellipse2D.Float((cx - r).toFloat(), (cy - r).toFloat(), (2 * r).toFloat(), (2 * r).toFloat())
            g.drawImage(logo, cx - r, cy - r, 2 * r, 2 * r, null)
            g.clip = oldClip
            // logo 描边圈
            g.color = Color(255, 255, 255, 60)
            g.drawOval(cx - r, cy - r, 2 * r, 2 * r)
        } else {
            g.color = Color(255, 255, 255, 30)
            g.fillOval(cx - r, cy - r, 2 * r, 2 * r)
            g.font = fontZh
            g.color = Color.WHITE
            val abbr = name.take(2)
            val fm = g.fontMetrics
            g.drawString(abbr, cx - fm.stringWidth(abbr) / 2, cy + fm.ascent / 2 - 6)
        }
        // 队名（下方居中）
        g.font = fontZh
        g.color = Color.WHITE
        val nw = g.fontMetrics.stringWidth(name)
        g.drawString(name, cx - nw / 2, cy + r + 52)
    }

    /** 拉取队徽图片（失败返回 null，由缩写徽章兜底） */
    private fun loadLogo(url: String, size: Int): Image? {
        if (url.isEmpty()) return null
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                ImageIO.read(resp.body?.byteStream())
                    ?.getScaledInstance(size, size, Image.SCALE_SMOOTH)
            }
        }.getOrNull()
    }

    /** 复制图片到系统剪贴板（EDT 调用） */
    fun copyToClipboard(image: Image) {
        val clipboard = Toolkit.getDefaultToolkit().getSystemClipboard()
        clipboard.setContents(ImageSelection(image), null)
    }

    /** AWT 图片剪贴板载体（微信/QQ/画图均可直接粘贴） */
    private class ImageSelection(private val image: Image) : Transferable, ClipboardOwner {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
        override fun lostOwnership(clipboard: Clipboard, contents: Transferable) { /* 图片已在剪贴板快照中，无需处理 */ }
    }
}
