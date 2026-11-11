package com.caro.nba.datasource.impl

import com.caro.nba.datasource.DataSourceCommon
import com.caro.nba.model.NBABroadcast
import com.google.gson.JsonObject
import okhttp3.Request
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 腾讯体育转播数据源（合法导流：仅取官方公开赛程/比赛页链接，不解析任何流地址）
 *
 * 接口（2026-11-11 经浏览器实测 kbs.sports.qq.com/nba 页面）：
 * https://matchweb.sports.qq.com/feeds/list?columnId=pc_100000&exp=1&feedSeq=1&flag=0&qimei=...&os=web
 * - 响应 data.list[] 中含赛程卡片 feed：scheduleUrl + list[]{matchInfo...}
 * - matchInfo：startTime（北京时间）/leftName/rightName（中文队名）/quarter/mid/isGold/ifHasPlayback
 *
 * ⚠️ 合规边界（勿破坏）：本数据源只做「官方比赛页导流」，绝不解析或内嵌直播流地址（m3u8 等）。
 * 各场次是否免费以腾讯页面展示为准，插件不做承诺。
 */
class TencentBroadcastDataSource {
    private val client = DataSourceCommon.client
    private val gson = DataSourceCommon.gson

    companion object {
        /**
         * 腾讯 NBA 频道 feed 接口。qimei 为腾讯设备指纹，实测不校验（任意非空值可用）。
         * columnId=pc_100000 为 NBA 频道。
         */
        private const val FEEDS_URL =
            "https://matchweb.sports.qq.com/feeds/list?columnId=pc_100000&exp=1&feedSeq=1&flag=0" +
                "&qimei=nba-live-score-plugin&sessionId=&appvid=7.5.00&times=1&os=web"
    }

    /** 拉取腾讯近两天赛程卡（当天 ±1 天窗口，官方页面展示口径） */
    fun recentBroadcasts(): Result<List<NBABroadcast>> = runCatching {
        val request = Request.Builder()
            .url(FEEDS_URL)
            .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://kbs.sports.qq.com/nba/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("腾讯体育接口失败: ${response.code}")
            val body = response.body?.string() ?: throw Exception("腾讯体育响应为空")
            parseFeeds(gson.fromJson(body, JsonObject::class.java))
        }
    }

    private fun parseFeeds(root: JsonObject): List<NBABroadcast> {
        val result = mutableListOf<NBABroadcast>()
        val feeds = root.getAsJsonObject("data")?.getAsJsonArray("list") ?: return result
        for (feed in feeds) {
            val feedObj = feed?.asJsonObject ?: continue
            val data = feedObj.getAsJsonObject("data") ?: continue
            val scheduleList = data.getAsJsonArray("list") ?: continue
            for (item in scheduleList) {
                val mi = item?.asJsonObject?.getAsJsonObject("matchInfo") ?: continue
                val mid = mi.get("mid")?.asString ?: continue
                val broadcast = NBABroadcast(
                    mid = mid,
                    homeName = mi.get("rightName")?.asString ?: "",   // 腾讯 left=客队展示位，主客以队名配对即可
                    awayName = mi.get("leftName")?.asString ?: "",
                    startTimeBeijing = mi.get("startTime")?.asString ?: "",
                    matchDesc = mi.get("matchDesc")?.asString ?: "",
                    statusText = mi.get("quarter")?.asString ?: "",
                    isGold = mi.get("isGold")?.asString == "1",
                    hasPlayback = mi.get("ifHasPlayback")?.asString == "1"
                )
                if (broadcast.homeName.isNotEmpty() && broadcast.awayName.isNotEmpty()) {
                    result += broadcast
                }
            }
        }
        // 去重（同一 mid 可能在多个 feed 出现）
        return result.distinctBy { it.mid }
    }

    /**
     * 查询单场比赛是否免费直播（官方 isPay 标注）。
     * multiUpdate 详情接口返回比赛级 isPay（"0"=免费，"1"=会员场）；失败返回 null。
     */
    fun fetchIsPay(mid: String): Boolean? = runCatching {
        val request = Request.Builder()
            .url("https://app.sports.qq.com/match/multiUpdate?mids=$mid&qimei=nba-live-score-plugin")
            .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://kbs.sports.qq.com/nba/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = gson.fromJson(body, JsonObject::class.java)
            val match = root.getAsJsonObject("data")?.getAsJsonObject(mid) ?: return null
            match.get("isPay")?.takeIf { it.isJsonPrimitive }?.asString?.let { it == "1" }
        }
    }.getOrNull()

    // ---------------- 直播吧首页渠道标注（CCTV5/腾讯等转播方） ----------------

    /**
     * 直播吧首页「今日直播」区的转播渠道标注：每场比赛的链接文字即渠道串
     * （如 "CCTV5 腾讯视频 全美直播"）。仅取转播方**事实信息**用于官方页导流，不取任何流地址。
     */
    fun homepageChannels(): Result<List<NBABroadcast>> = runCatching {
        val request = Request.Builder()
            .url("https://www.zhibo8.com/")
            .header("User-Agent", DataSourceCommon.DEFAULT_USER_AGENT)
            .header("Referer", "https://www.zhibo8.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("直播吧首页请求失败: ${response.code}")
            val html = response.body?.string() ?: throw Exception("直播吧首页响应为空")
            parseHomepageChannels(html)
        }
    }

    /** 解析首页 saishi 条目：label（队名）+ 渠道串（第一个 /zhibo/ 链接的文字）。
     *  注意 label 属性可能在 id 之前（<li label="..." id="saishi...">），正则须从 <li 开始覆盖整个开标签 */
    private fun parseHomepageChannels(html: String): List<NBABroadcast> {
        val entryRegex = Regex(
            """<li[^>]*id="saishi(\d+)"[^>]*>.*?<a href="/zhibo/[^"]*"[^>]*>([^<]+)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        // 队名：label="NBA,篮球,客队,主队,NBA常规赛"（label 前 2 位为队名）
        val labelRegex = Regex("""label="[^",]+,[^",]+,([^,]+),([^,]+),""")
        return entryRegex.findAll(html).mapNotNull { m ->
            val id = m.groupValues[1]
            val channelText = m.groupValues[2].trim()
            val lm = labelRegex.find(m.value) ?: return@mapNotNull null
            val away = lm.groupValues[1].trim()
            val home = lm.groupValues[2].trim()
            if (home.isEmpty() || away.isEmpty()) return@mapNotNull null
            NBABroadcast(
                mid = id,
                homeName = home,
                awayName = away,
                startTimeBeijing = "",
                matchDesc = "",
                statusText = "",
                isGold = false,
                hasPlayback = false,
                channelText = channelText
            )
        }.toList()
    }

    /** 在转播列表中查找指定两队的场次；找不到返回 null（调用方展示通用渠道） */
    fun findBroadcast(broadcasts: List<NBABroadcast>, teamA: String, teamB: String): NBABroadcast? =
        broadcasts.firstOrNull { it.matches(teamA, teamB) }

    /** 今日（本地日期）开球的场次（startTime 北京日期 == 今天） */
    fun todayBroadcasts(broadcasts: List<NBABroadcast>): List<NBABroadcast> {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        return broadcasts.filter { it.startTimeBeijing.startsWith(today) }
    }
}
