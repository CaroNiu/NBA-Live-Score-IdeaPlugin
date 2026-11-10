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

    /** 默认底部水印（传播锚点：仓库名），可被设置里的自定义文案替代 */
    private const val DEFAULT_WATERMARK = "NBA Live Score · IDEA 插件   |   github.com/CaroNiu/NBA-Live-Score-IdeaPlugin"

    /**
     * 分享图主题（色板）
     * @param id 存储标识（settings.shareImageTheme）
     */
    data class ShareTheme(
        val id: String,
        val displayName: String,
        val gradTop: Color,
        val gradBottom: Color
    )

    /** 内置三套主题 */
    val THEMES = listOf(
        ShareTheme("night", "暗夜（默认）", Color(0x0F, 0x20, 0x27), Color(0x2C, 0x53, 0x64)),
        ShareTheme("sunset", "落日", Color(0x35, 0x5C, 0x7D), Color(0xC0, 0x6C, 0x84)),
        ShareTheme("christmas", "圣诞", Color(0x0B, 0x3D, 0x2E), Color(0x7B, 0x11, 0x13))
    )

    /** 按 id 取主题，未知值回退暗夜 */
    fun themeById(id: String): ShareTheme = THEMES.firstOrNull { it.id == id } ?: THEMES.first()

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

    /**
     * 生成分享图（后台线程调用：含 logo 网络加载）
     * @param theme 配色主题（settings.shareImageTheme）
     * @param watermark 自定义水印文案，空则用默认仓库水印
     */
    fun generate(game: NBAGame, dateLabel: String, theme: ShareTheme = THEMES.first(), watermark: String = ""): BufferedImage {
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // 主题渐变背景
        g.paint = GradientPaint(0f, 0f, theme.gradTop, W.toFloat(), H.toFloat(), theme.gradBottom)
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

        // 底部水印（自定义文案优先，trim 后为空回退默认）
        val wm = watermark.trim().ifEmpty { DEFAULT_WATERMARK }
        g.font = fontWatermark
        g.color = Color(255, 255, 255, 110)
        g.drawString(wm, (W - g.fontMetrics.stringWidth(wm)) / 2, H - 28)

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
