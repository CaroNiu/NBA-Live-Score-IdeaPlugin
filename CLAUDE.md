# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

NBA Live Score — IntelliJ IDEA 插件（id: `com.caro.nbascore`），在右侧工具窗口中查看 NBA 实时比分、排名、季后赛对阵图，并内置一个调用 OpenAI 兼容接口的 AI 问答助手。

- Kotlin 1.9.22 + IntelliJ Platform Gradle Plugin 2.1.0，基于 IC 2024.3 编译，JDK 17 toolchain，Gradle 8.5 wrapper
- UI 全部为 Swing（非 Kotlin UI DSL），季后赛对阵图为 `paintComponent` 手绘 2D 图形
- 代码注释、UI 文案、commit message 均为中文；commit 遵循 conventional commits + 中文描述（如 `refactor(nba): ...`、`fix(playoffs): ...`）

## 常用命令

```bash
./gradlew buildPlugin    # 构建插件 zip → build/distributions/nba-score-plugin-<version>.zip
./gradlew runIde         # 启动沙箱 IDEA 实例调试插件（已配置 -Xmx2G）
./gradlew verifyPlugin   # 运行插件兼容性验证
```

- 无测试源码（无 `src/test`），没有 lint 配置；CI workflow（`.github/workflows/build-and-publish.yml`）整体被注释掉，未生效
- ⚠️ 当前 `buildPlugin` 会**编译失败**，原因见下方「已知问题」

## 版本发布约定

发布新版本时需同步修改三处（缺一不可）：

1. `build.gradle.kts` 中的 `version = "..."`（项目版本）
2. `build.gradle.kts` 中 `intellijPlatform.pluginConfiguration.version`（插件版本）
3. `src/main/resources/META-INF/plugin.xml` 的 `<change-notes>` 顶部新增对应版本条目

兼容性范围：`sinceBuild = "242"`（IDEA 2024.2），`untilBuild` 无上限。

## 架构

### 入口与标签页结构

`plugin.xml` 注册三样东西：右侧工具窗口 "NBA Score"（`NBAScoreToolWindowFactory`）、主工具栏刷新按钮（`RefreshAction`）、应用级设置页（"NBA AI 助手"，`NBASettingsState` / `NBASettingsConfigurable`）。

`NBAScoreToolWindowFactory.kt` 中的 `NBAScoreMainPanel` 是 4 个标签页的容器（JBTabbedPane），**懒加载**：先放空的 `JPanel()` 占位，首次点击某标签才实例化真实面板（`initializeTab`），并记录在 `initializedTabs` 中。新增标签页时注意维护这个索引约定（当前：0=比分、1=排名、2=直播、3=资讯）。

### 面板 + 服务的固定模式

每个标签页都是「一个 JPanel + 一个纯 Kotlin 服务类」的组合，服务类直接 new（非 IntelliJ Service 注入）：

| 面板 | 服务 | 数据源 |
|---|---|---|
| `NBAScorePanel` | `service.NBADataService` | ESPN scoreboard API |
| `StandingsPanel` | `service.StandingsService` | ESPN standings API |
| `PlayByPlayPanel` | `service.GameDetailService` | 直播吧/新浪/ESPN 文字直播流 |
| `GameDetailDialog` | `service.GameDetailService` | 直播吧详情（中文统计）→ 新浪 → ESPN 降级 |
| `NewsPanel` | `service.NewsService` | 直播吧移动版资讯页（HTML 直出，正则提取） |

**线程模型（所有面板统一遵循）**：每个面板持有 `CoroutineScope(Dispatchers.Default + SupervisorJob())`，在协程里执行**同步** OkHttp `execute()` 网络请求，结果通过 `ApplicationManager.getApplication().invokeLater` 或 `SwingUtilities.invokeLater` 回到 EDT 更新 UI；`dispose()` 取消 scope。不要在 EDT 上发起网络请求，也不要在后台线程直接碰 Swing 组件。

**JSON 解析**：统一用 Gson 的 `JsonObject` 手工逐字段解析到 `model/` 下的 data class（非反射映射），ESPN 字段缺失的容错写法（`?.get(...)?.asString ?: 默认值`）是既有风格。

### 时区约定

ESPN API 按**美国东部日期**查询。`NBADataService.getGames(date)` 要求传入的日期已是美东时间；本地 → 美东的转换用 `NBAScorePanel.toEasternDate()`（companion object）。用户界面上显示的是用户本地日期。

### AI 助手

`AIAssistantPanel.kt` 一个文件包含面板、设置持久化（`NBASettingsState`，存储于 `nba_settings.xml`）、设置页（`NBASettingsConfigurable`）三部分。要点：

- 请求体是 OpenAI 兼容格式，但额外带 `thinking` 参数（智谱特有）；API URL 由用户配置**完整地址**，代码不做任何拼接
- 支持流式（SSE，手动解析 `data: ` 行）和非流式两种响应模式，由设置中的 `stream` 开关决定
- API Key 必须用户自行配置，为空时直接报错

### 已知遗留：未挂载的面板类（勿删依赖）

5.0.0 移除了季后赛对阵图与 AI 助手**标签页**，但 `PlayoffBracketPanel.kt`、`AIAssistantPanel.kt` 及其服务类（`PlayoffService` 等）仍保留在源码中（未注册到 plugin.xml、不在标签索引里）。编译不受影响；如需彻底移除，先确认无其他引用再删整个链路。

### 其他易踩的坑

- **英中队名映射重复三份**：`NBADataService`、`StandingsService`、`GameDetailService` 各自持有独立的 `teamNameMap`，修改时需三处同步
- 所有颜色用 `JBColor(亮色, 暗色)` 双值写法以同时支持明暗主题
- `NBAScoreService.kt` 与 `RefreshAction` 只是激活工具窗口的简单入口，窗口激活后由面板自身触发数据加载
