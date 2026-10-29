import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import java.io.File

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.caro"
version = "5.2.0"

repositories {
    mavenCentral()
    // 显式添加 JetBrains 仓库
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://www.jetbrains.com/intellij-repository/snapshots")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 使用 IDEA 2024.3 编译
        intellijIdeaCommunity("2024.3")
        pluginVerifier()
        instrumentationTools()
    }
    
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

intellijPlatform {
    pluginConfiguration {
        name = "NBA Live Score"
        version = "5.2.0"
        
        // 适配 IDEA 2024.2 到 2025.3+
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }  // 不设置上限
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks {
    // 禁用 searchable options 索引：本插件无需全文搜索支持，且该任务启动 headless IDE 时，
    // 沙箱 disabled_plugins.txt 中被禁用的 Gradle 插件会导致 4 个依赖插件加载失败而退出
    named("buildSearchableOptions") {
        enabled = false
    }

    withType<RunIdeTask> {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf("-Xmx2G")
        }
        // 沙箱 IDE 禁用 Gradle 集成插件：IC-2024.3 解析 JetBrains 下发的最新
        // Gradle-JDK 兼容表（含 JDK 25 条目）会抛 IllegalArgumentException: 25，
        // 与本插件无关；沙箱调试用不到 Gradle，直接禁用以消除该 SEVERE 日志
        doFirst {
            val sandboxRoot = rootProject.layout.projectDirectory.dir("build/idea-sandbox").asFile
            sandboxRoot.listFiles()?.filter { it.isDirectory }?.forEach { ideDir ->
                val configDir = File(ideDir, "config")
                if (configDir.isDirectory) {
                    val disabledFile = File(configDir, "disabled_plugins.txt")
                    val content = if (disabledFile.exists()) disabledFile.readText() else ""
                    if (!content.contains("com.intellij.gradle")) {
                        disabledFile.appendText("com.intellij.gradle\norg.jetbrains.plugins.gradle\n")
                    }
                }
            }
        }
    }
}