package com.caro.nba

import com.caro.nba.model.NewsArticle
import com.caro.nba.service.NewsService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * 赛后战报弹窗（插件内嵌阅读，不跳浏览器）
 *
 * 数据：直播吧比赛页「战报」tab 文章（NewsService.fetchZhanbao，zhanbao_url 链路）。
 * 渲染：与资讯阅读视图同款两阶段——先出文本版（图片占位），配图后台下载临时文件后
 * 以 file: URI 内嵌重渲染（GIF 自动播放；Swing 直连图床在 IDE 环境不可靠）。
 * 战报中的集锦视频不内嵌（头部提示到直播吧原文观看）。
 */
class ZhanbaoDialog(
    project: Project?,
    private val article: NewsArticle
) : DialogWrapper(project) {

    private val service = NewsService()
    private val imageResCache = ConcurrentHashMap<String, ImageRes?>()
    private val imageFileCache = ConcurrentHashMap<String, File>()
    private lateinit var articlePane: JEditorPane

    private data class ImageRes(val uri: String, val width: Int, val height: Int)

    private val CARD_BG = JBColor(0xF7F7F7, 0x2B2B2B)
    private val META_FG = JBColor.GRAY

    init {
        title = "赛后战报"
        setOKButtonText("关闭")
        init()
        // 图片两阶段：文本版先出，配图后台下载完成后整体重渲染
        Thread {
            val images = mutableMapOf<String, ImageRes?>()
            for (block in article.blocks) {
                if (block.isImage) images[block.imageUrl] = resolveImage(block.imageUrl)
            }
            SwingUtilities.invokeLater { renderHtml(images) }
        }.apply { isDaemon = true }.start()
    }

    override fun createCenterPanel(): JComponent {
        articlePane = JEditorPane().apply {
            isEditable = false
            contentType = "text/html"
            isOpaque = false
        }
        renderHtml(emptyMap())
        return JBScrollPane(articlePane).apply {
            preferredSize = Dimension(780, 580)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    /** 渲染战报 HTML（images 为空 = 文本占位版；下载完成后传尺寸重渲染） */
    private fun renderHtml(images: Map<String, ImageRes?>) {
        articlePane.text = html {
            // 头卡：标题 + 时间 + 来源
            append("<div style=\"background-color:${hex(CARD_BG)}; padding-top:12; padding-bottom:12; "
                    + "padding-left:12; padding-right:12; margin-bottom:10\">")
            append("<div style=\"font-size:16pt; font-weight:bold\">${esc(article.title)}</div>")
            append("<div style=\"color:${hex(META_FG)}; font-size:10pt; margin-top:8\">")
            append(esc(article.publishTime)).append(" · 来源 直播吧")
            append(" · 集锦视频请到直播吧原文观看</div>")
            append("</div>")
            for (block in article.blocks) {
                if (block.isImage) {
                    appendImageBlock(block, images)
                } else {
                    append("<p style=\"font-size:13pt; margin-top:10; margin-bottom:10\">${esc(block.text)}</p>")
                }
            }
        }
        articlePane.setCaretPosition(0)
    }

    /** 图片块：本地文件等比缩放内嵌（GIF 缩放后动画照播），失败/WebP 降级占位文字 */
    private fun StringBuilder.appendImageBlock(block: NewsArticle.Block, images: Map<String, ImageRes?>) {
        when {
            block.imageUrl.endsWith(".webp", ignoreCase = true) ->
                append("<p align=\"center\" style=\"margin-top:10\"><font size=\"2\" "
                        + "color=\"${hex(META_FG)}\">此处为 WebP 动图，浏览器打开可查看</font></p>")

            images.containsKey(block.imageUrl) && images[block.imageUrl] == null ->
                append("<p align=\"center\" style=\"margin-top:10\"><font size=\"2\" "
                        + "color=\"${hex(META_FG)}\">图片加载失败</font></p>")

            images[block.imageUrl] == null ->
                append("<p align=\"center\" style=\"margin-top:10\"><font size=\"2\" "
                        + "color=\"${hex(META_FG)}\">图片加载中...</font></p>")

            else -> {
                val res = images[block.imageUrl]!!
                val target = (articlePane.width - 24).coerceIn(160, 660)
                val widthAttr = if (res.width > target) " width=\"$target\"" else ""
                append("<p align=\"center\" style=\"margin-top:12; margin-bottom:12\">")
                append("<img src=\"${escAttr(res.uri)}\"$widthAttr></p>")
            }
        }
    }

    /** 图片解析：缓存命中直接用；否则下载临时文件（JVM 退出清理）并测量尺寸；WebP 不支持跳过 */
    private fun resolveImage(url: String): ImageRes? {
        if (imageResCache.containsKey(url)) return imageResCache[url]
        if (url.endsWith(".webp", ignoreCase = true)) {
            imageResCache[url] = null
            return null
        }
        val res = try {
            val file = imageFileCache[url] ?: run {
                val bytes = service.fetchImageBytes(url).getOrNull() ?: return null.also { imageResCache[url] = null }
                val ext = url.substringBefore('?').substringAfterLast('.', "jpg").take(5)
                val f = File.createTempFile("nbazhanbao", ".$ext")
                f.writeBytes(bytes)
                f.deleteOnExit()
                imageFileCache[url] = f
                f
            }
            ImageIO.read(file)?.let { ImageRes(file.toURI().toString(), it.width, it.height) }
        } catch (e: Exception) {
            null
        }
        imageResCache[url] = res
        return res
    }

    private fun html(block: StringBuilder.() -> Unit): String = StringBuilder().apply(block).toString()
    private fun StringBuilder.hex(c: java.awt.Color): String = String.format("#%02X%02X%02X", c.red, c.green, c.blue)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun escAttr(s: String) = esc(s).replace("\"", "&quot;")
}
