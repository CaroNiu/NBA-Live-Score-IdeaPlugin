package com.caro.nba.service

import com.caro.nba.model.NewsArticle
import com.caro.nba.model.NewsItem
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 直播吧 NBA 资讯服务
 *
 * 数据源：移动版资讯页 https://m.zhibo8.cc/news/web/nba/（2026-11-05 实测）
 * - 服务端直出 HTML，约 24KB，为按时间倒序的推荐列表（PC 版 news.zhibo8.com/nba/ 同为
 *   服务端渲染且重 306KB；两端均无新闻列表 JSON 接口，2026-11-05 抓包确认）
 * - 条目结构固定：<li class="lite"><h2><a href="//m.zhibo8.cc/news/web/nba/{日期}/{id}native.htm">标题</a></h2></li>
 * - 链接即正文页，用系统浏览器打开即可阅读；列表为纯文本提取，不走多源降级（资讯仅直播吧提供）
 */
class NewsService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val NEWS_URL = "https://m.zhibo8.cc/news/web/nba/"

        /** 移动版页面按 iPhone UA 渲染 */
        private val DEFAULT_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

        /** 资讯条目：li.lite > h2 > a；href 可能是协议相对（//m.zhibo8.cc/...）或完整 https */
        private val ITEM_REGEX = Regex(
            """<li class="lite"><h2><a href="((?:https?:)?//m\.zhibo8\.cc/news/web/nba/[^"]+)"[^>]*>\s*(.*?)\s*</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        /** 发布日期编码在链接路径里 */
        private val DATE_REGEX = Regex("""/news/web/nba/(\d{4}-\d{2}-\d{2})/""")

        /** 文章页标题 / 发布时间 */
        private val ARTICLE_TITLE_REGEX = Regex("""<h1 class="title">(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
        private val MEDIA_TIME_REGEX = Regex("""media-time">([^<]+)<""")

        /** 正文块：段落 <p>...</p> 或图片容器 img_wrap...data="URL"，交替出现需按序捕获 */
        private val BLOCK_REGEX = Regex(
            """<p[^>]*>(.*?)</p>|img_wrap[^>]*?data="([^"]+)"""",
            RegexOption.DOT_MATCHES_ALL
        )

        /** 段落内嵌标签（strong/em/vote 等）剥离 */
        private val TAG_REGEX = Regex("""<[^>]+>""")
    }

    /**
     * 拉取并解析资讯正文（插件内阅读视图用）
     *
     * 文章页结构（2026-11-05 实测 m.zhibo8.com 移动版，列表页 .cc 域名会 301 到此）：
     * - 标题：<h1 class="title">...</h1>
     * - 发布时间：<div class="media-time">2026-11-05 14:29:43</div>
     * - 正文：<p>段落</p> 与 <div class="img_wrap" data="图片URL"> 按序交替，
     *   以 <div id="app"> 收尾（其后是页面脚本，不属正文）
     * - 图片仅提取 URL（协议相对 // 补全 https），由面板异步下载内嵌渲染
     */
    fun fetchArticle(url: String): Result<NewsArticle> {
        return try {
            val html = fetchHtml(url)

            val title = ARTICLE_TITLE_REGEX.find(html)?.groupValues?.get(1)
                ?.let { decodeHtml(it) }?.trim().takeUnless { it.isNullOrEmpty() } ?: "详情"
            val publishTime = MEDIA_TIME_REGEX.find(html)?.groupValues?.get(1)?.trim() ?: ""

            // 正文区域：media-time 之后、<div id="app">（页面脚本起点）之前
            val start = html.indexOf("media-time").coerceAtLeast(0)
            val end = html.indexOf("<div id=\"app\">").let { if (it < 0) html.length else it }
            val region = if (start < end) html.substring(start, end) else html

            val blocks = mutableListOf<NewsArticle.Block>()
            for (match in BLOCK_REGEX.findAll(region)) {
                val paragraph = match.groupValues[1]
                val image = match.groupValues[2]
                when {
                    image.isNotEmpty() -> blocks += NewsArticle.Block(
                        isImage = true,
                        text = "",
                        imageUrl = if (image.startsWith("//")) "https:$image" else image
                    )
                    paragraph.isNotEmpty() -> {
                        val clean = decodeHtml(paragraph.replace(TAG_REGEX, "")).trim()
                        if (clean.isNotEmpty()) blocks += NewsArticle.Block(isImage = false, text = clean)
                    }
                }
            }

            if (blocks.isEmpty()) {
                Result.failure(Exception("正文解析为空（页面结构可能变更），可点「浏览器打开」查看原文"))
            } else {
                Result.success(NewsArticle(title, publishTime, blocks))
            }
        } catch (e: Exception) {
            Result.failure(Exception("正文获取失败：${e.message}", e))
        }
    }

    /**
     * 下载资讯配图字节（面板落临时文件后以 file: URI 内嵌显示）。
     * Swing 的 ImageView 直连图床在 IDE 环境不可靠，统一走本客户端（带 UA/Referer）。
     */
    fun fetchImageBytes(url: String): Result<ByteArray> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_UA)
                // 部分图床校验 Referer 防盗链，带上文章域以免 403（实测多余则无副作用）
                .header("Referer", "https://m.zhibo8.com/")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                Result.success(resp.body?.bytes() ?: ByteArray(0))
            }
        } catch (e: Exception) {
            Result.failure(Exception("图片获取失败：${e.message}", e))
        }
    }

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA)
            .build()
        // .cc 列表页域名跳 .com 文章页（301），OkHttp 默认跟随重定向
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    // ---------------- 赛后战报（直播吧比赛页「战报」tab） ----------------

    /**
     * 拉取赛后战报文章。链路（2026-11-13 实测）：
     * 1. 元数据 s.qiumibao.com/json/match/{gameId}.htm 的 `zhanbao_url` 字段
     *    （如 /nba/2026-11-13/match2143927date2026vnative.htm）
     * 2. 战报文章页：https://news.zhibo8.com{zhanbao_url}（www/m 域名 404，仅 news 域可达）
     *
     * 战报页结构与资讯文章页**不同构**（无 media-time/<div id="app"> 锚点）：
     * <div class="tzhanbao"> 内 h1 标题 + 时间 + <p> 段落 + <img> 配图（含视频占位）。
     * 队徽/logo 图（static4style/lq_ 前缀）过滤不入正文。
     */
    fun fetchZhanbao(gameId: String): Result<NewsArticle> {
        return try {
            // 1. 元数据提取 zhanbao_url（indexOf 分步，避免 raw string 正则的引号歧义）
            val meta = fetchHtml("https://s.qiumibao.com/json/match/$gameId.htm")
            com.caro.nba.datasource.DataSourceCommon.debugLog("战报: 元数据大小=${meta.length}")
            val zKey = "\"zhanbao_url\""
            val zi = meta.indexOf(zKey)
            if (zi < 0) return Result.failure(Exception("该比赛暂无战报"))
            val colon = meta.indexOf(':', zi)
            val q1 = meta.indexOf('"', colon)
            val q2 = meta.indexOf('"', q1 + 1)
            // JSON 里 "/" 转义为 "\/"（python json.load 自动解转义掩盖了这一点），需手动还原
            val zhanbaoPath = meta.substring(q1 + 1, q2).replace("\\/", "/")
            com.caro.nba.datasource.DataSourceCommon.debugLog("战报: zhanbao_url=$zhanbaoPath")
            if (!zhanbaoPath.startsWith("/")) return Result.failure(Exception("该比赛暂无战报"))

            // 2. 战报文章页（仅 news 域可达）
            val html = fetchHtml("https://news.zhibo8.com$zhanbaoPath")
            com.caro.nba.datasource.DataSourceCommon.debugLog("战报: 文章大小=${html.length} tzhanbao@${html.indexOf("tzhanbao")} p数=${Regex("<p").findAll(html).count()}")

            val title = Regex("""<h1[^>]*>([\s\S]*?)</h1>""").find(html)
                ?.groupValues?.get(1)
                ?.let { TAG_REGEX.replace(it, "").trim() }
                .takeUnless { it.isNullOrEmpty() } ?: "战报"

            val publishTime = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2})""").find(html)
                ?.groupValues?.get(1) ?: ""

            // 正文块：<p> 段落 与 <img> 配图按序捕获（过滤队徽/logo 图）
            val region = html.substring(html.indexOf("tzhanbao").coerceAtLeast(0))
            val blocks = mutableListOf<NewsArticle.Block>()
            for (match in Regex("""<p[^>]*>([\s\S]*?)</p>|<img[^>]*src="([^"]+)""").findAll(region)) {
                val paragraph = match.groupValues[1]
                val image = match.groupValues[2]
                when {
                    image.isNotEmpty() -> {
                        val isJunk = image.contains("static4style") || image.contains("/lq_") ||
                            image.contains("logo") || image.contains("duihui")
                        if (!isJunk) blocks += NewsArticle.Block(isImage = true, text = "", imageUrl = normalizeImageUrl(image))
                    }
                    else -> {
                        val text = TAG_REGEX.replace(paragraph, "").trim()
                        if (text.isNotEmpty()) blocks += NewsArticle.Block(isImage = false, text = text)
                    }
                }
            }
            com.caro.nba.datasource.DataSourceCommon.debugLog("战报: 解析块数=${blocks.size} 段落=${blocks.count { !it.isImage }} 图=${blocks.count { it.isImage }}")
            if (blocks.none { !it.isImage }) {
                return Result.failure(Exception("该比赛暂无战报"))
            }
            Result.success(NewsArticle(title = title, publishTime = publishTime, blocks = blocks))
        } catch (e: Exception) {
            Result.failure(Exception("战报获取失败：${e.message}", e))
        }
    }

    /** 协议相对 // 补全 https */
    private fun normalizeImageUrl(url: String): String =
        if (url.startsWith("//")) "https:$url" else url

    /**
     * 拉取资讯列表（时间倒序，链接去重）
     */
    fun fetchNews(): Result<List<NewsItem>> {
        return try {
            val html = fetchHtml(NEWS_URL)

            val items = linkedMapOf<String, NewsItem>()
            for (match in ITEM_REGEX.findAll(html)) {
                var url = match.groupValues[1]
                if (url.startsWith("//")) url = "https:$url"
                val title = decodeHtml(match.groupValues[2]).trim()
                if (title.isEmpty() || items.containsKey(url)) continue
                val date = DATE_REGEX.find(url)?.groupValues?.get(1) ?: ""
                items[url] = NewsItem(title, url, date)
            }

            if (items.isEmpty()) {
                Result.failure(Exception("直播吧：资讯列表解析为空（页面结构可能变更）"))
            } else {
                Result.success(items.values.toList())
            }
        } catch (e: Exception) {
            Result.failure(Exception("资讯获取失败：${e.message}", e))
        }
    }

    /** 标题轻量还原：去内嵌标签 + 常用 HTML 实体 */
    private fun decodeHtml(raw: String): String =
        raw.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
}
