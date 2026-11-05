package com.caro.nba

import com.caro.nba.model.NewsArticle
import com.caro.nba.model.NewsItem
import com.caro.nba.service.NewsService
import com.intellij.ide.BrowserUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

/**
 * NBA 资讯面板 - 直播吧 NBA 新闻（📰 资讯标签页），双视图：
 *
 * - 列表视图：按日期分组的推荐流（时间倒序），每条新闻一张淡色底卡片，
 *   点击标题进入阅读视图
 * - 阅读视图：插件内直接阅读正文（标题/时间/段落/配图），无需跳转浏览器；
 *   顶部提供「← 返回列表」与「🌐 浏览器打开」（看原网页互动内容）
 *
 * 渲染方案：列表与正文都用 Swing 自带 HTML 引擎（JEditorPane + HTMLEditorKit）。
 * 不用 JTextArea+BoxLayout 手排的原因：JTextArea 在未定宽时 preferred 高度按单行
 * 计算，工具窗窄幅下换行后高度被裁剪、排版错乱；HTML 引擎按容器实际宽度重排，
 * 窗口拉伸自适应。
 *
 * 配图加载：Swing 的 ImageView 直连 http 图床在 IDE 环境不可靠，改为面板自行
 * 用 OkHttp 下载（带 UA/Referer）到临时文件，正文以 file: URI 内嵌——GIF 动图
 * 正常播放、按面板宽度等比缩放；WebP 因 JVM 无原生解码器降级为占位文字。
 *
 * 线程模型与各面板一致：协程内同步 OkHttp 请求，结果经 invokeLater 回 EDT 更新 UI；
 * dispose() 取消协程（由 NBAScoreMainPanel 在工具窗口销毁时调用）。
 */
class NewsPanel : JPanel(BorderLayout(0, 10)) {

    private val service = NewsService()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val CARD_LIST = "list"
        private const val CARD_ARTICLE = "article"

        private val CARD_BG = JBColor(0xFFFFFF, 0x33363B)   // 新闻卡片底色（与工具窗背景拉开一档对比）
        private val HEAD_FG = JBColor(0x444444, 0xD0D4D8)   // 列表日期组头（比次要信息灰更醒目）
        private val META_FG = JBColor(0x787878, 0x9A9A9A)   // 次要信息灰
        private val LINK_FG = JBColor(0x0066CC, 0x4A9EFF)   // 品牌蓝
        private val ERROR_FG = JBColor(0xCC8800, 0xFFCC33)  // 错误提示橙

        /** 图片临时文件缓存：url → 本地文件（JVM 退出时删除） */
        private val imageFileCache = ConcurrentHashMap<String, File>()

        /** 已解析图片信息缓存（含解码失败的 null，避免重复下载/测量） */
        private val imageResCache = ConcurrentHashMap<String, ImageRes?>()
    }

    /** 可内嵌显示的图片（临时文件 + 尺寸） */
    private data class ImageRes(val uri: String, val width: Int, val height: Int)

    // 工具栏
    private var refreshButton: JButton? = null
    private var lastUpdateLabel: JLabel? = null

    // 中央双视图
    private val centerPanel = JPanel(CardLayout())
    private val listCard = JPanel(BorderLayout())
    private val articleCard = JPanel(BorderLayout())

    // 列表视图
    private val listPane = createHtmlPane { url -> openArticleByUrl(url) }
    private var loadingLabel: JLabel? = null
    private var infoLabel: JLabel? = null
    private var listScrollPane: JBScrollPane? = null

    // 阅读视图
    private val articlePane = createHtmlPane { url -> BrowserUtil.browse(url) }
    private var articleScrollPane: JBScrollPane? = null
    private var currentArticleUrl: String? = null

    /** 列表 url → 条目映射（点击卡片标题时定位条目） */
    private val itemsByUrl = mutableMapOf<String, NewsItem>()

    /** 防刷新并发重入 */
    private var loading = false

    init {
        border = JBUI.Borders.empty(10)
        buildUi()

        SwingUtilities.invokeLater {
            loadNews()
        }
    }

    /** HTML 面板公共配置：只读、超链接回调（点击打开 / hover 手型光标） */
    private fun createHtmlPane(onLink: (String) -> Unit): JEditorPane =
        JEditorPane("text/html", "").apply {
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            addHyperlinkListener { e ->
                when (e.eventType) {
                    HyperlinkEvent.EventType.ACTIVATED -> e.url?.let { onLink(it.toString()) }
                    HyperlinkEvent.EventType.ENTERED ->
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    HyperlinkEvent.EventType.EXITED ->
                        cursor = Cursor.getDefaultCursor()
                    else -> {}
                }
            }
        }

    private fun buildUi() {
        refreshButton = JButton("🔄 刷新").apply {
            addActionListener { loadNews() }
        }

        lastUpdateLabel = JLabel("").apply {
            font = font.deriveFont(10f)
            foreground = META_FG
        }
        val toolbar = JPanel(BorderLayout(10, 0)).apply {
            add(Box.createHorizontalBox().apply {
                add(refreshButton)
                add(Box.createHorizontalStrut(10))
                add(JLabel("来源:").apply { font = font.deriveFont(12f) })
                add(Box.createHorizontalStrut(4))
                add(JLabel("直播吧 · NBA").apply {
                    font = font.deriveFont(12f)
                    foreground = LINK_FG
                })
            }, BorderLayout.WEST)
            add(lastUpdateLabel, BorderLayout.EAST)
        }
        add(toolbar, BorderLayout.NORTH)

        // 列表视图
        loadingLabel = JLabel("正在加载资讯...", SwingConstants.CENTER).apply {
            font = font.deriveFont(15f)
        }
        listCard.add(loadingLabel, BorderLayout.CENTER)
        listScrollPane = JBScrollPane(listPane).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
            isVisible = false
        }
        listCard.add(listScrollPane, BorderLayout.CENTER)

        // 阅读视图：头部（返回/浏览器打开）+ 正文
        articleCard.add(JPanel(BorderLayout(8, 0)).apply {
            add(JButton("← 返回列表").apply {
                addActionListener { switchCard(CARD_LIST) }
            }, BorderLayout.WEST)
            add(JButton("🌐 浏览器打开").apply {
                addActionListener { currentArticleUrl?.let { BrowserUtil.browse(it) } }
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        articleScrollPane = JBScrollPane(articlePane).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
        }
        articleCard.add(articleScrollPane, BorderLayout.CENTER)

        centerPanel.add(listCard, CARD_LIST)
        centerPanel.add(articleCard, CARD_ARTICLE)
        add(centerPanel, BorderLayout.CENTER)
    }

    private fun switchCard(name: String) {
        (centerPanel.layout as CardLayout).show(centerPanel, name)
    }

    // ---------------- 列表视图 ----------------

    private fun loadNews() {
        if (loading) return
        loading = true
        refreshButton?.isEnabled = false

        switchCard(CARD_LIST)
        SwingUtilities.invokeLater {
            removeInfoLabel()
            loadingLabel?.isVisible = true
            listScrollPane?.isVisible = false
        }

        scope.launch {
            val result = service.fetchNews()
            SwingUtilities.invokeLater {
                loading = false
                refreshButton?.isEnabled = true
                result.fold(
                    onSuccess = { items ->
                        renderList(items)
                        updateLastRefreshTime()
                    },
                    onFailure = { error ->
                        showError(error.message ?: "加载失败")
                    }
                )
            }
        }
    }

    /**
     * 列表渲染：整个列表一个 HTML 文档。
     * 卡片用 div + 内联 CSS（Swing HTMLEditorKit 支持 background/padding/margin 单边属性）；
     * <a> 若不显式指定样式会渲染成"深蓝+下划线"的浏览器默认观感，故内联品牌蓝且去下划线。
     * 注意：Swing HTML 无 emoji 字形回退（渲染成缺字方框），文档内禁用 emoji。
     */
    private fun renderList(items: List<NewsItem>) {
        itemsByUrl.clear()
        items.forEach { itemsByUrl[it.url] = it }

        listPane.text = if (items.isEmpty()) {
            html {
                append("<div style=\"color:${hex(META_FG)}; font-size:11pt\">暂无资讯数据，可点击「刷新」重试</div>")
            }
        } else {
            html {
                var lastDate: String? = null
                for (item in items) {
                    if (item.date != lastDate) {
                        lastDate = item.date
                        append("<div style=\"color:${hex(HEAD_FG)}; font-size:12pt; font-weight:bold; "
                                + "margin-top:18; margin-bottom:7\">${esc(dateDisplay(item.date))}</div>")
                    }
                    append("<div style=\"background-color:${hex(CARD_BG)}; padding-top:9; padding-bottom:9; "
                            + "padding-left:12; padding-right:12; margin-bottom:6\">")
                    append("<a href=\"${escAttr(item.url)}\" style=\"color:${hex(LINK_FG)}; font-size:13pt; "
                            + "text-decoration:none\">${esc(item.title)}</a>")
                    append("</div>")
                }
            }
        }

        listScrollPane?.isVisible = true
        loadingLabel?.isVisible = false
        listScrollPane?.viewport?.viewPosition = Point(0, 0)
        revalidate()
        repaint()
    }

    /** yyyy-MM-dd → 「11月5日 · 星期四」，解析失败回退原始串 */
    private fun dateDisplay(date: String): String = try {
        val d = java.time.LocalDate.parse(date)
        val week = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CHINESE)
        "${d.monthValue}月${d.dayOfMonth}日 · $week"
    } catch (e: Exception) {
        date.ifEmpty { "近期资讯" }
    }

    // ---------------- 阅读视图 ----------------

    /** 列表点击 → 定位条目进入阅读视图（旧文档/映射缺失时按纯 url 兜底） */
    private fun openArticleByUrl(url: String) {
        openArticle(itemsByUrl[url] ?: NewsItem("", url, ""))
    }

    /** 进入阅读视图：先展示加载态，异步拉正文后渲染（不跳转浏览器） */
    private fun openArticle(item: NewsItem) {
        currentArticleUrl = item.url
        switchCard(CARD_ARTICLE)
        articlePane.text = html {
            append("<font color=\"${hex(META_FG)}\">正在加载正文...</font>")
        }
        articleScrollPane?.viewport?.viewPosition = Point(0, 0)

        scope.launch {
            val result = service.fetchArticle(item.url)
            SwingUtilities.invokeLater {
                // 防御：加载期间用户已返回列表/换文章则不渲染
                if (currentArticleUrl == item.url) {
                    result.fold(
                        onSuccess = { renderArticle(it, item.url) },
                        onFailure = { error -> showArticleError(error.message ?: "加载失败") }
                    )
                }
            }
        }
    }

    /**
     * 正文两阶段渲染：先出文本版（图片位置显示"加载中"），后台把配图下载到本地
     * 临时文件后再整体重渲染（图片以 file: URI 内嵌，Swing 直连 http 图床不可靠）。
     */
    private fun renderArticle(article: NewsArticle, articleUrl: String) {
        renderArticleHtml(article, emptyMap())

        scope.launch {
            val images = mutableMapOf<String, ImageRes?>()
            for (block in article.blocks) {
                if (block.isImage) images[block.imageUrl] = resolveImage(block.imageUrl)
            }
            SwingUtilities.invokeLater {
                // 防御：下载期间用户已换文章则不重渲染
                if (currentArticleUrl == articleUrl) renderArticleHtml(article, images)
            }
        }
    }

    /** 正文 HTML 组装：头卡（标题+时间，与列表卡片同底色）+ 正文段落与配图 */
    private fun renderArticleHtml(article: NewsArticle, images: Map<String, ImageRes?>) {
        articlePane.text = html {
            append("<div style=\"background-color:${hex(CARD_BG)}; padding-top:12; padding-bottom:12; "
                    + "padding-left:12; padding-right:12; margin-bottom:10\">")
            append("<div style=\"font-size:18pt; font-weight:bold\">${esc(article.title)}</div>")
            append("<div style=\"color:${hex(META_FG)}; font-size:10pt; margin-top:8\">")
            append(esc(article.publishTime)).append(" · 直播吧</div>")
            append("</div>")
            for (block in article.blocks) {
                if (block.isImage) {
                    appendImageBlock(block, images)
                } else {
                    // Swing 不支持行距 CSS，正文可读性靠 14pt 字号 + 大段距保证
                    append("<p style=\"font-size:14pt; margin-top:12; margin-bottom:12\">${esc(block.text)}</p>")
                }
            }
        }
        articleScrollPane?.viewport?.viewPosition = Point(0, 0)
    }

    /**
     * 图片块：有本地文件按面板宽度等比缩放（宽图缩入、小图原尺寸；GIF 缩放后动画照播），
     * 下载失败/WebP（JVM 无解码器）降级为占位文字
     */
    private fun StringBuilder.appendImageBlock(block: NewsArticle.Block, images: Map<String, ImageRes?>) {
        when {
            block.imageUrl.endsWith(".webp", ignoreCase = true) ->
                append("<p align=\"center\" style=\"margin-top:10; margin-bottom:10\"><font size=\"2\" "
                        + "color=\"${hex(META_FG)}\">此处为 WebP 动图，浏览器打开可查看</font></p>")

            images.containsKey(block.imageUrl) && images[block.imageUrl] == null ->
                append("<p align=\"center\" style=\"margin-top:10; margin-bottom:10\"><font size=\"2\" "
                        + "color=\"${hex(META_FG)}\">图片加载失败（浏览器打开可查看）</font></p>")

            images[block.imageUrl] == null ->
                append("<p align=\"center\" style=\"margin-top:10; margin-bottom:10\"><font size=\"2\" "
                        + "color=\"${hex(META_FG)}\">图片加载中...</font></p>")

            else -> {
                val res = images[block.imageUrl]!!
                val target = (articlePane.width - 24).coerceIn(120, 640)
                val widthAttr = if (res.width > target) " width=\"$target\"" else ""
                append("<p align=\"center\" style=\"margin-top:12; margin-bottom:12\">")
                append("<img src=\"${escAttr(res.uri)}\"$widthAttr></p>")
            }
        }
    }

    /** 图片解析：缓存命中直接用；否则 OkHttp 下载到临时文件（JVM 退出清理）并测量尺寸 */
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
                val f = File.createTempFile("nbanews", ".$ext")
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

    private fun showArticleError(message: String) {
        articlePane.text = html {
            append("<font color=\"${hex(ERROR_FG)}\">${esc(message)}</font><br><br>")
            append("<font size=\"2\" color=\"${hex(META_FG)}\">可点击「🌐 浏览器打开」查看原文，或返回列表重试</font>")
        }
    }

    // ---------------- 公共 ----------------

    private fun updateLastRefreshTime() {
        val now = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
        lastUpdateLabel?.text = "最后更新: $now"
    }

    /** 移除列表视图 CENTER 区域上的说明/错误提示（避免多次提示叠加） */
    private fun removeInfoLabel() {
        infoLabel?.let { listCard.remove(it) }
        infoLabel = null
    }

    private fun showError(message: String) {
        loadingLabel?.isVisible = false
        listScrollPane?.isVisible = false
        removeInfoLabel()

        infoLabel = JLabel("<html><div style='padding: 20px; text-align: center;'>"
                + message.replace("\n", "<br>")
                + "<br>请检查网络后点击「刷新」重试</div></html>").apply {
            foreground = ERROR_FG
            font = font.deriveFont(14f)
            horizontalAlignment = SwingConstants.CENTER
        }
        listCard.add(infoLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    // ---------------- HTML 工具 ----------------

    private inline fun html(block: StringBuilder.() -> Unit): String =
        StringBuilder("<html><body style=\"font-family:'Microsoft YaHei','PingFang SC',sans-serif\">")
            .apply(block).append("</body></html>").toString()

    /** 文本内容转义（正文段落已 decodeHtml 过，再入 HTML 需反向转义） */
    private fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** 属性值转义（href/src） */
    private fun escAttr(text: String): String = esc(text).replace("\"", "&quot;")

    private fun hex(c: Color): String = String.format("#%06x", c.rgb and 0xFFFFFF)

    /** 释放面板资源（取消协程）。由 NBAScoreMainPanel 在工具窗口销毁时调用 */
    fun dispose() {
        scope.cancel()
    }
}
