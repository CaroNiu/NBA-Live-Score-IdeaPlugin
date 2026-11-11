package com.caro.nba

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 匿名统计上报（opt-in，默认关闭）
 *
 * 用户在设置页勾选「匿名统计」后，IDE 打开项目时异步 GET 一次
 * `{pingUrl}?v={插件版本}`，仅此一个请求，失败静默。
 *
 * 隐私边界（设计承诺，勿破坏）：
 * - 只上报插件版本号（URL query 参数），不采集机器信息、项目信息、使用行为
 * - 端点为开发者官方 Worker（NBASettingsState.DEFAULT_PING_URL），开关默认关闭，勾选即视为同意
 * - 老用户存量空地址由启动兜底补默认；用户自定义的非空地址优先于默认值
 */
class PingStartupActivity : ProjectActivity {

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        /** plugin.xml 中声明的插件 id */
        private const val PLUGIN_ID = "com.caro.nbascore"
    }

    override suspend fun execute(project: Project) {
        val settings = NBASettingsState.getInstance()

        // 首次启动记录安装日期（评分引导计时用；与匿名统计开关无关，均需记录）
        if (settings.installDate.isEmpty()) {
            settings.installDate = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        }

        // 老用户存量空地址兜底：自动补官方端点（用户自定义的非空地址优先，不覆盖）
        if (settings.pingUrl.isBlank()) {
            settings.pingUrl = NBASettingsState.DEFAULT_PING_URL
        }

        if (!settings.anonymousPing) return
        val url = settings.pingUrl.trim()
        if (url.isEmpty()) return

        // findEnabledPlugin 为公开 API（PluginManagerCore.getPlugin 是 internal，Marketplace 验证器会告警）
        val version = PluginManager.getInstance().findEnabledPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "unknown"
        val separator = if (url.contains('?')) "&" else "?"
        val request = Request.Builder().url("$url${separator}v=$version").build()

        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request).execute().use { /* 只关心请求到达，不读响应体 */ }
            }.onFailure {
                com.caro.nba.datasource.DataSourceCommon.debugLog("匿名统计上报失败（静默忽略）: ${it.message}")
            }
        }
    }
}
