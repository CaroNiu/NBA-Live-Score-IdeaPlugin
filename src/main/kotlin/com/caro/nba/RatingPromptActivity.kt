package com.caro.nba

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Marketplace 评分引导（项目级 postStartupActivity，见 plugin.xml）
 *
 * 首次启动满 7 天后的某次启动温和提示一次「去 Marketplace 评分」，只提示一次：
 * - 通知出现即置 ratingPromptShown（用户忽略也算提示过，避免纠缠）
 * - installDate 为空说明是老版本升级上来的用户，先补记为今天（从升级当天重新计时）
 * - Marketplace 地址：plugin id 30496（reviews 直达评分页）
 */
class RatingPromptActivity : ProjectActivity {

    companion object {
        /** 使用满 N 天后提示（天数门槛） */
        private const val PROMPT_AFTER_DAYS = 7L

        /** Marketplace 评分页（plugin id 30496） */
        private const val MARKETPLACE_REVIEWS_URL = "https://plugins.jetbrains.com/plugin/30496/reviews"
    }

    override suspend fun execute(project: Project) {
        val settings = NBASettingsState.getInstance()
        val today = LocalDate.now()

        // 老用户升级：补记安装日为今天，从升级当天起算
        if (settings.installDate.isEmpty()) {
            settings.installDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            return
        }
        if (settings.ratingPromptShown) return

        val installed = runCatching {
            LocalDate.parse(settings.installDate, DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrNull() ?: return
        if (ChronoUnit.DAYS.between(installed, today) < PROMPT_AFTER_DAYS) return

        settings.ratingPromptShown = true
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("NBA Score Notifications")
                .createNotification(
                    "觉得 NBA Live Score 好用吗？",
                    "你已使用本插件 ${PROMPT_AFTER_DAYS} 天以上，欢迎去 Marketplace 打个分（⭐ 评分 + 一句话评价），帮助更多球迷发现它～",
                    NotificationType.INFORMATION
                )
                .addAction(object : AnAction("⭐ 去 Marketplace 评分") {
                    override fun actionPerformed(e: AnActionEvent) {
                        BrowserUtil.browse(MARKETPLACE_REVIEWS_URL)
                    }
                })
                .notify(null)
        }
    }
}
