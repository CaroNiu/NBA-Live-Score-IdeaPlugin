# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

NBA Live Score — IntelliJ IDEA 插件（id: `com.caro.nbascore`，Marketplace pluginId 30496），在右侧工具窗口中提供 NBA 观赛一站式体验：实时比分、排名、文字直播 + 直播吧弹幕、资讯、数据王（五类榜单/赛程/球员资料卡）、球队赛程、转播渠道导流（CCTV5/腾讯免费场判定）、比分分享图。

- Kotlin 1.9.22 + IntelliJ Platform Gradle Plugin 2.1.0，基于 IC 2024.3 编译（sinceBuild=242 无上限），JDK 17 toolchain，Gradle 8.5 wrapper
- UI 全部为 Swing；图表（季后赛对阵图、分享图二维码、比分走势折线）均为手绘 Graphics2D / ZXing，零重型依赖
- 代码注释、UI 文案、commit message 均为中文；commit 遵循 conventional commits + 中文描述（如 `feat(broadcast): ...`）
- 版本发布：tag `vX.Y.Z` 触发 CI 全自动发 Marketplace + GitHub Release（见「版本发布约定」），commit author 用 `Caro_Niu <43942296+caroniu@users.noreply.github.com>`

## 常用命令

```bash
./gradlew buildPlugin    # 构建插件 zip → build/distributions/nba-score-plugin-<version>.zip
./gradlew runIde         # 启动沙箱 IDEA 实例调试插件（已配置 -Xmx2G）
./gradlew verifyPlugin   # 运行插件兼容性验证（未配 pluginVerification.ides，会报"无 IDE"）
```

- 无测试源码（无 `src/test`），无 lint 配置
- ⚠️ 本机 Windows 开发注意：`runIde` 的沙箱 IDEA 进程会锁 `build/idea-sandbox` 下的 jar，**重新 buildPlugin 前必须先停沙箱**（TaskStop 不一定杀掉 IDEA JVM，残留时用 PowerShell 按 CommandLine 匹配 `idea-sandbox` 找 PID 强杀）；`gradlew` 脚本在 Git Bash 乱码不可用，用 `D:/jdk17/bin/java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain` 方式（配 `GRADLE_USER_HOME=D:/install/gradle/repository` 与 `MSYS2_ARG_CONV_EXCL=*`）
- 匿名统计端点（Cloudflare Worker + KV）代码与部署指南在 `workspace/game-plugin/worker/`

## 版本发布约定

发布新版本时需同步修改三处（缺一不可）：

1. `build.gradle.kts` 中的 `version = "..."`（项目版本）
2. `build.gradle.kts` 中 `intellijPlatform.pluginConfiguration.version`（插件版本）
3. `src/main/resources/META-INF/plugin.xml` 的 `<change-notes>` 顶部新增对应版本条目

兼容性范围：`sinceBuild = "242"`（IDEA 2024.2），无上限。

## 架构

### 入口与标签页结构

`plugin.xml` 注册：右侧工具窗口（`NBAScoreToolWindowFactory`）、主工具栏刷新按钮（`RefreshAction`）、设置页（`NBASettingsState`/`NBASettingsConfigurable`）、通知组（`NBA Score Notifications`）、两个状态栏 widget、三个 postStartupActivity（`PingStartupActivity` 匿名统计+安装日期、`DailyBriefingActivity` 晨间提醒、`RatingPromptActivity` 评分引导）。

`NBAScoreMainPanel` 为 5 标签页容器（JBTabbedPane，懒加载 + `initializedTabs` 索引）：**0=比分、1=排名、2=直播、3=资讯、4=数据王**。新增标签页需维护索引数组与 `dispose()` 联动释放。

### 数据源抽象层（`datasource/`）

- 接口：`ScoreDataSource`/`StandingsDataSource`/`GameDetailDataSource`/`PlayByPlayDataSource`/`PlayoffDataSource`；`DataSource` 枚举 + `DataSourceManager` 管多源降级链（中文源优先：直播吧/新浪 → ESPN），源切换持久化于设置
- `impl/` 下实现：`EspnDataSource`（比分/排名/详情）、`Zhibo8DataSource`（比分/文字直播/详情）、`SinaDataSource`、`NbaComDataSource`、`TencentDataSource`、`BallDontLieDataSource`、`TheSportsDbDataSource`
- **独立数据源（不走降级链，来源单一）**：`EspnStatsDataSource`（今日榜聚合/赛季榜 core API/球队赛程 schedule）、`Zhibo8LeaderDataSource`（赛季数据王五榜/当前赛季赛程/球员资料卡 `nba_v2_player&playerId=`）、`Zhibo8DanmuDataSource`（弹幕，`dan.zhibo8.cc` 静态 JSON 分页轮询，per_page=5，order_id 增量游标）、`TencentBroadcastDataSource`（腾讯赛程 feed + 官方 isPay 免费判定 + 直播吧首页渠道标注解析）
- `DataSourceCommon`：共享 OkHttp client/gson/中英文队名映射（`teamNameMap`/`chineseNameToAbbr`）/UA/调试日志——**队名映射统一在此，勿再分散**

### 服务层（`service/`）

- `FollowScoreWatcher`：应用级单例，轮询关注球队比赛（15s/60s），状态迁移通知（开赛/节次/关键分/完赛）
- `DailyBriefingWatcher`：晨间提醒（今日赛程+每日战报，`lastBriefingDate` 每天一次，失败不置位下轮重试）+ 连胜/连败里程碑（跨入 ≥5 场提醒一次，快照 `teamStreakSnapshot`）
- `LeagueLeadersService`：数据王/球队赛程门面（直播吧优先、ESPN 降级）
- `GameDetailService`/`NewsService`/`StandingsService`/`PlayoffService`：详情（跨源反查 GameRef）/资讯/排名/季后赛

### 关键 UI 组件

- `PlayByPlayPanel`：文字直播（多源切换/自动刷新）+ **弹幕侧栏**（JSplitPane 左右分栏，「💬 弹幕」按钮切换，30s 增量轮询 order_id 游标，回填 60 条，上限 200 条，自动刷新 checkbox）+ 字号调节 A-/A+（`contentFontSize` 持久化，遍历 JTextArea/弹幕行即时应用）
- `GameDetailDialog`：比分区（每节得分走势折线 `ScoreTrendChart` 手绘 + 每节比分条）+ 球员统计表（点球员名 → `PlayerCardDialog` 内嵌资料卡，直播吧 playerId 直查；失败兜底浏览器资料页）+ 状态行（转播渠道标注 + 官方直播链接，检测结果异步回填）
- `ShareImageGenerator`：比分分享图（手绘 Graphics2D + ZXing 二维码，三主题 + 自定义水印），剪贴板复制
- `LeagueLeadersPanel`：数据王面板（今日榜/赛季榜/赛程 三子 tab，赛程支持「只看关注球队」过滤 `scheduleFollowOnly`）
- `WhereToWatchDialog`：转播渠道官方导流弹窗（**合规红线：只链持权方官方页面，绝不解析/内嵌任何直播流地址**）

### 时区约定

比分/赛程按**美国东部日期**查询（`NBAScorePanel.toEasternDate()` companion）；弹幕/赛程展示与晨间提醒按**北京时间**口径（直播吧按北京日期组织数据）；赛季年计算：7 月起算新赛季（`EspnStatsDataSource.currentSeasonYear()`）。

### AI 助手遗留（未挂载，勿删依赖）

5.0.0 移除了 AI 助手/季后赛对阵图标签页，但 `AIAssistantPanel.kt`、`PlayoffBracketPanel.kt` 及其服务保留在源码中（季后赛图计划 2027-04 复活）。编译不受影响。

### 其他易踩的坑

- 排名页/比分页懒加载索引约定（见 `NBAScoreMainPanel`）；数据王面板 `LeagueLeadersPanel` 首次加入显示树才拉数据（`addNotify`）
- 所有颜色用 `JBColor(亮, 暗)` 双值写法支持明暗主题；图表手绘组件在 `paintComponent` 内完成
- `NBAScoreService.kt` 与 `RefreshAction` 只是激活工具窗口的简单入口，窗口激活后由面板自身触发数据加载
- 匿名统计（opt-in 默认关）：仅上报版本号至开发者 Worker 端点（`NBASettingsState.DEFAULT_PING_URL`），老用户空地址启动兜底补默认——隐私承诺勿破坏
