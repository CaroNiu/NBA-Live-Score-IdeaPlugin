package com.caro.nba.model

/**
 * 直播吧 NBA 资讯条目
 *
 * @param title 新闻标题（已去内嵌标签与 HTML 实体）
 * @param url 正文页完整链接（https://m.zhibo8.cc/news/web/nba/{日期}/{id}native.htm）
 * @param date 发布日期（取自链接路径 yyyy-MM-dd；解析失败为空串）
 */
data class NewsItem(
    val title: String,
    val url: String,
    val date: String
)
