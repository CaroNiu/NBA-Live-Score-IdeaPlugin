package com.caro.nba.datasource

import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 数据源公共资源与工厂
 *
 * 集中管理 OkHttpClient、Gson、队名映射等共享资源，
 * 避免在每个 Service 中重复定义。
 */
object DataSourceCommon {
    /** 共享 OkHttp 客户端 */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 共享 Gson 实例 */
    val gson: Gson by lazy { Gson() }

    /** 英文队名到中文映射（唯一真相源） */
    val teamNameMap: Map<String, String> by lazy {
        mapOf(
            "Hawks" to "老鹰", "Celtics" to "凯尔特人", "Nets" to "篮网", "Hornets" to "黄蜂",
            "Bulls" to "公牛", "Cavaliers" to "骑士", "Mavericks" to "独行侠", "Nuggets" to "掘金",
            "Pistons" to "活塞", "Warriors" to "勇士", "Rockets" to "火箭", "Pacers" to "步行者",
            "Clippers" to "快船", "Lakers" to "湖人", "Grizzlies" to "灰熊", "Heat" to "热火",
            "Bucks" to "雄鹿", "Timberwolves" to "森林狼", "Pelicans" to "鹈鹕", "Knicks" to "尼克斯",
            "Thunder" to "雷霆", "Magic" to "魔术", "76ers" to "76人", "Suns" to "太阳",
            "Trail Blazers" to "开拓者", "Kings" to "国王", "Spurs" to "马刺", "Raptors" to "猛龙",
            "Jazz" to "爵士", "Wizards" to "奇才"
        )
    }

    /** 默认 User-Agent（模拟 Chrome 浏览器） */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** ESPN API 专用请求头（模拟浏览器请求，避免 403） */
    val espnHeaders: Map<String, String> by lazy {
        mapOf(
            "User-Agent" to DEFAULT_USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "https://www.espn.com",
            "Referer" to "https://www.espn.com/",
            "Accept-Language" to "en-US,en;q=0.9"
        )
    }

    /** NBA 球队中文名 -> 缩写（中文数据源公共映射，用于补全缩写和队徽） */
    val chineseNameToAbbr: Map<String, String> by lazy {
        mapOf(
            "老鹰" to "ATL", "凯尔特人" to "BOS", "篮网" to "BKN", "黄蜂" to "CHA",
            "公牛" to "CHI", "骑士" to "CLE", "独行侠" to "DAL", "掘金" to "DEN",
            "活塞" to "DET", "勇士" to "GSW", "火箭" to "HOU", "步行者" to "IND",
            "快船" to "LAC", "湖人" to "LAL", "灰熊" to "MEM", "热火" to "MIA",
            "雄鹿" to "MIL", "森林狼" to "MIN", "鹈鹕" to "NOP", "尼克斯" to "NYK",
            "雷霆" to "OKC", "魔术" to "ORL", "76人" to "PHI", "太阳" to "PHX",
            "开拓者" to "POR", "国王" to "SAC", "马刺" to "SAS", "猛龙" to "TOR",
            "爵士" to "UTA", "奇才" to "WAS"
        )
    }

    /** NBA 球队缩写 -> 中文名（chineseNameToAbbr 的反向视图，供跨源反查按队名匹配） */
    val abbrToChineseName: Map<String, String> by lazy {
        chineseNameToAbbr.entries.associate { (cn, abbr) -> abbr to cn }
    }

    /** 中文队名别名 -> 标准中文名（不同中文数据源叫法差异） */
    val chineseTeamAliases: Map<String, String> by lazy {
        mapOf(
            "快艇" to "快船",
            "小牛" to "独行侠",
            "遛马" to "步行者",
            "灰狼" to "森林狼",
            "木狼" to "森林狼",
            "暴龙" to "猛龙",
            "拓荒者" to "开拓者",
            "巫师" to "奇才"
        )
    }

    /** 标准化中文队名（别名归一） */
    fun normalizeChineseTeamName(name: String): String = chineseTeamAliases[name] ?: name

    /** ESPN CDN 队徽 URL（中文数据源不提供队徽时兜底） */
    fun espnTeamLogo(abbr: String): String =
        if (abbr.isNotEmpty()) "https://a.espncdn.com/i/teamlogos/nba/500/$abbr.png" else ""

    /**
     * 调试日志：输出到控制台（runIde 控制台可直接看到），统一前缀便于过滤
     * 排查数据问题时在控制台搜索 "[NBA]" 即可跟踪整条调用链
     */
    fun debugLog(message: String) {
        println("[NBA] $message")
    }
}
