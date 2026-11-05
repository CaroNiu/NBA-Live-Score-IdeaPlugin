package com.caro.nba.model

/**
 * 直播吧资讯正文（阅读视图）
 *
 * 正文按原文顺序组织为块序列：文字段落直接渲染；图片块异步下载后按面板宽度
 * 等比缩放内嵌显示（视频无法内嵌，占位提示改用浏览器观看）。
 *
 * @param title 文章标题（取自 h1.title）
 * @param publishTime 发布时间字符串（取自 media-time，如 "2026-11-05 14:29:43"）
 * @param blocks 正文块（段落与图片按原文顺序排列）
 */
data class NewsArticle(
    val title: String,
    val publishTime: String,
    val blocks: List<Block>
) {
    /** 正文块：isImage=true 时 imageUrl 为图片地址（text 为空）；否则 text 为段落文本 */
    data class Block(
        val isImage: Boolean,
        val text: String,
        val imageUrl: String = ""
    )
}
