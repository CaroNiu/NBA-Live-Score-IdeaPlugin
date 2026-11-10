# NBA Live Score - IntelliJ IDEA 插件

<p align="center">
  <img src="src/main/resources/icons/nba.svg" width="100" height="100" alt="NBA Logo">
</p>

<p align="center">
  <b>在 IDEA 里看 NBA：实时比分 · 关注球队通知 · 赛程 · 数据王 · 一键分享图</b><br>
  <a href="https://plugins.jetbrains.com/plugin/30496">📥 JetBrains Marketplace 安装</a> ·
  <a href="#-功能特性">功能</a> ·
  <a href="#english">English</a>
</p>

---

## 📥 一键安装

直接从 JetBrains Marketplace 安装（推荐）：

**Settings → Plugins → Marketplace → 搜索 "NBA Live Score" → Install**

或访问 [plugins.jetbrains.com/plugin/30496](https://plugins.jetbrains.com/plugin/30496) 下载安装。

## ✨ 功能特性

- 🏀 **实时比分** —— 当天全部比赛实时比分，多数据源自动降级（直播吧 / 新浪 / ESPN），中文队名与球员统计
- 📌 **状态栏常驻** —— IDE 底部轮播关注球队比分（完整队名 / 缩写 / 仅比分三档隐蔽模式），点击唤起主面板
- 🔔 **比赛通知** —— 关注球队开赛 / 进入新一节 / 关键分（分差≤5）/ 完赛气泡提醒
- 🌅 **晨间提醒** —— 今日关注赛程（含本地开球时刻）+ 每日战报（昨日赛果摘要），每天一次不骚扰
- 🔥 **连胜里程碑** —— 关注球队 5 连胜 / 5 连败跨入时提醒，追踪赛季叙事
- 📺 **文字直播** —— 固定 Tab 实时观看中文文字直播（直播吧 / 新浪 / ESPN 三源切换），15 秒自动刷新
- 📊 **东西部排名** —— 常规赛排名、季后赛锁定标记
- 🏆 **数据王榜** —— 今日单场数据王（得分/篮板/助攻 Top10）+ 赛季场均榜（ESPN）
- 📅 **球队赛程** —— 比分卡点队名，查看近 5 场战绩（W/L 徽章）与未来 5 场赛程
- 🖼️ **比分分享图** —— 比赛卡右键一键生成精美深色卡片（队徽/比分/状态），自动复制剪贴板，支持三套主题与自定义水印
- 📰 **NBA 资讯** —— 直播吧热点资讯，插件内阅读图文正文

## 🎯 快速上手

1. 安装后右侧工具窗口打开 **NBA Score**
2. 首次打开按引导**选择关注球队**（状态栏与通知的基础）
3. 比赛日：状态栏自动轮播比分，开赛/关键分/完赛气泡提醒
4. 比赛卡右键 → **生成分享图**，粘贴到微信/QQ 群里聊球

更多细节见 [Custom Pages 使用指南](https://plugins.jetbrains.com/plugin/30496)。

## 🛠️ 技术栈

Kotlin 1.9 · IntelliJ Platform Gradle Plugin 2.x（IC 2024.3，兼容 2024.2+）· OkHttp · Gson · Coroutines · Swing

## 🔌 数据来源

[直播吧](https://www.zhibo8.cc/) · [新浪 NBA](https://sports.sina.com.cn/nba/) · [ESPN](https://www.espn.com/nba/)（多源自动降级，国内网络环境优先中文源）

## 📄 License

MIT

---

<a name="english"></a>

## English

**NBA Live Score** — watch NBA games right inside your IDE.

- 🏀 **Live scores** with multi-source fallback (Zhibo8 / Sina / ESPN), Chinese team names & box scores
- 📌 **Status bar widget** — carousel of followed teams' scores with stealth mode
- 🔔 **Game notifications** — tip-off, quarter change, clutch moment (≤5 pts gap), final buzzer
- 🌅 **Morning briefing** — today's followed schedule with local tip-off times + daily recap, once a day
- 🔥 **Win-streak milestones** — get notified when a followed team reaches a 5+ game streak
- 📺 **Play-by-play** — fixed tab with auto-refresh (15s), 3 text sources
- 🏆 **Stat leaders** — today's single-game leaders (PTS/REB/AST top 10) + season averages
- 📅 **Team schedule** — click a team name on a score card for last 5 results & next 5 games
- 🖼️ **Score share card** — right-click a game card to render a dark-themed image and copy it to clipboard
- 📰 **NBA news** feed inside the plugin

**Install:** [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30496) — compatible with IntelliJ IDEA 2024.2+.

**Build from source:**

```bash
./gradlew buildPlugin   # → build/distributions/nba-score-plugin-<version>.zip
./gradlew runIde        # sandbox IDE for local testing
```

Tech stack: Kotlin 1.9, IntelliJ Platform Gradle Plugin 2.x, OkHttp, Gson, Coroutines, Swing. Data from Zhibo8 / Sina / ESPN public APIs. MIT License.
