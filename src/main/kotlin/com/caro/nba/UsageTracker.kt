package com.caro.nba

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 匿名使用统计（与「匿名统计」同一 opt-in 开关，默认关闭）
 *
 * - [track]：核心功能首次使用时上报 `func=功能名`（会话内去重，每功能每次启动最多一次）
 * - [reportError]：业务异常摘要上报 `error=类型标签`（同 tag+detail 哈希会话内去重）
 *
 * 隐私边界（与 PingStartupActivity 同一承诺，勿破坏）：
 * 只上报版本号/功能名/错误类型标签（白名单字符），不采集机器信息、项目信息、用户内容。
 * 开关关闭或上报地址为空时全部静默跳过。
 */
object UsageTracker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private const val PLUGIN_ID = "com.caro.nbascore"

    /** 会话内去重集合 */
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** 功能首用埋点（func 用英文短标签，如 danmu/leaders/schedule/share/playercard） */
    fun track(func: String) {
        report("func", sanitize(func))
    }

    /** 业务错误上报（tag 用英文类型标签，如 zhibo8_parse/espn_network） */
    fun reportError(tag: String) {
        report("error", sanitize(tag))
    }

    private fun report(paramName: String, value: String) {
        val dedupeKey = "$paramName=$value"
        if (!reported.add(dedupeKey)) return  // 会话内重复直接跳过

        val settings = NBASettingsState.getInstance()
        if (!settings.anonymousPing) {  // 开关关闭：仅标记去重，不发请求
            return
        }
        val baseUrl = settings.pingUrl.trim()
        if (baseUrl.isEmpty()) return

        val version = PluginManager.getInstance()
            .findEnabledPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "unknown"
        val separator = if (baseUrl.contains('?')) "&" else "?"
        val url = "$baseUrl${separator}v=$version&$paramName=$value"

        Thread {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { /* 到达即可 */ }
            }.onFailure {
                com.caro.nba.datasource.DataSourceCommon.debugLog("使用统计上报失败（静默忽略）: ${it.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    /** 白名单字符消毒（防 KV 键注入） */
    private fun sanitize(v: String, maxLen: Int = 24): String =
        v.replace(Regex("[^a-zA-Z0-9\\-_]"), "_").take(maxLen).trimEnd('_')
}
