package com.caro.nba

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.awt.*
import java.util.concurrent.TimeUnit
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * AI 数据问答助手面板
 */
class AIAssistantPanel : JPanel(BorderLayout()) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // UI 组件
    private val inputField = JTextField().apply {
        font = font.deriveFont(14f)
    }
    private val sendButton = JButton("提问")
    private val clearButton = JButton("清空")
    private val settingsButton = JButton("⚙️ 设置")
    private val outputArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = font.deriveFont(13f)
        margin = Insets(10, 10, 10, 10)
    }
    private val statusLabel = JLabel("准备就绪")
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        background = JBColor(0xF5F5F5, 0x1E1E1E)
        border = JBUI.Borders.empty(15)
        
        // 顶部：预设问题按钮
        val presetPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            isOpaque = false
            border = EmptyBorder(0, 0, 10, 0)
            
            add(JLabel("快捷问题：").apply { font = font.deriveFont(Font.BOLD, 12f) })
            add(createPresetButton("今天有哪些比赛？"))
            add(createPresetButton("雷霆战绩如何？"))
            add(createPresetButton("东西部排名"))
            add(createPresetButton("季后赛对阵"))
        }
        
        // 输入区域
        val inputPanel = JPanel(BorderLayout(5, 0)).apply {
            isOpaque = false
            border = EmptyBorder(0, 0, 10, 0)
            
            add(JLabel("💬 ").apply { font = font.deriveFont(16f) }, BorderLayout.WEST)
            add(inputField, BorderLayout.CENTER)
            
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                isOpaque = false
                add(sendButton)
                add(clearButton)
                add(settingsButton)
            }
            add(buttonPanel, BorderLayout.EAST)
        }
        
        // 输出区域
        val scrollPane = JScrollPane(outputArea).apply {
            preferredSize = Dimension(600, 350)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        
        // 底部状态栏
        val statusPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(10, 0, 0, 0)
            add(statusLabel, BorderLayout.WEST)
        }
        
        // 布局
        val mainPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(presetPanel, BorderLayout.NORTH)
            add(inputPanel, BorderLayout.SOUTH)
        }
        
        add(mainPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusPanel, BorderLayout.SOUTH)
        
        // 事件监听
        sendButton.addActionListener { sendQuestion() }
        clearButton.addActionListener { 
            outputArea.text = ""
            statusLabel.text = "已清空"
        }
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(null, NBASettingsConfigurable::class.java)
        }
        
        inputField.addActionListener { sendQuestion() }
    }
    
    private fun createPresetButton(text: String): JButton {
        return JButton(text).apply {
            font = font.deriveFont(11f)
            addActionListener {
                inputField.text = text
                sendQuestion()
            }
        }
    }
    
    private fun sendQuestion() {
        val question = inputField.text.trim()
        if (question.isEmpty()) {
            statusLabel.text = "请输入问题"
            return
        }
        
        sendButton.isEnabled = false
        statusLabel.text = "🤔 思考中..."
        
        scope.launch {
            val result = callAI(question)
            ApplicationManager.getApplication().invokeLater {
                sendButton.isEnabled = true
                result.fold(
                    onSuccess = { response ->
                        appendOutput("👤 你：$question\n\n🤖 AI：$response\n\n${"─".repeat(40)}\n\n")
                        statusLabel.text = "✅ 回答完成"
                        inputField.text = ""
                    },
                    onFailure = { error ->
                        appendOutput("❌ 错误：${error.message}\n\n")
                        statusLabel.text = "❌ 请求失败"
                    }
                )
            }
        }
    }
    
    private fun callAI(question: String): Result<String> {
        return try {
            val settings = NBASettingsState.getInstance()
            
            // 强制要求用户配置 API Key
            if (settings.apiKey.isBlank()) {
                return Result.failure(Exception("请先配置 API Key：点击「设置」按钮进行配置"))
            }
            
            val apiUrl = settings.apiUrl.ifEmpty { DEFAULT_API_URL }
            val apiKey = settings.apiKey
            val model = settings.model.ifEmpty { DEFAULT_MODEL }
            val maxTokens = settings.maxTokens.toIntOrNull() ?: 65536
            val temperature = settings.temperature.toDoubleOrNull() ?: 1.0
            val stream = settings.stream
            
            // 构造请求体
            val systemPrompt = buildSystemPrompt()
            
            val requestBody = JsonObject().apply {
                addProperty("model", model)
                add("messages", gson.toJsonTree(listOf(
                    mapOf("role" to "user", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to question)
                )))
                add("thinking", gson.toJsonTree(mapOf("type" to "enabled")))
                addProperty("stream", stream)
                addProperty("max_tokens", maxTokens)
                addProperty("temperature", temperature)
            }
            
            val request = Request.Builder()
                .url(apiUrl)  // 直接使用用户配置的完整 URL
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
            
            if (stream) {
                // 流式输出处理
                handleStreamResponse(response)
            } else {
                // 非流式输出处理
                handleNonStreamResponse(response)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 处理流式响应
     */
    private fun handleStreamResponse(response: okhttp3.Response): Result<String> {
        val body = response.body ?: return Result.failure(Exception("Empty response"))
        val reader = body.charStream()
        val buffer = CharArray(1024)
        val resultBuilder = StringBuilder()
        
        return try {
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) break
                
                val chunk = String(buffer, 0, read)
                // 解析 SSE 格式: data: {...}
                chunk.lines().filter { it.startsWith("data: ") }.forEach { line ->
                    val jsonStr = line.removePrefix("data: ").trim()
                    if (jsonStr == "[DONE]") return@forEach
                    
                    try {
                        val json = gson.fromJson(jsonStr, JsonObject::class.java)
                        val delta = json.getAsJsonArray("choices")
                            ?.get(0)?.asJsonObject
                            ?.getAsJsonObject("delta")
                            ?.get("content")?.asString
                        if (delta != null) {
                            resultBuilder.append(delta)
                            // 实时更新 UI
                            ApplicationManager.getApplication().invokeLater {
                                appendOutput(delta)
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略解析错误
                    }
                }
            }
            
            reader.close()
            
            if (resultBuilder.isEmpty()) {
                return Result.failure(Exception("No content received"))
            }
            
            Result.success(resultBuilder.toString())
        } catch (e: Exception) {
            reader.close()
            return Result.failure(e)
        }
    }
    
    /**
     * 处理非流式响应
     */
    private fun handleNonStreamResponse(response: okhttp3.Response): Result<String> {
        val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))
        
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
            
            val content = json.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: return Result.failure(Exception("Invalid response format"))
            
            return Result.success(content)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to parse response: ${e.message}"))
        }
    }
    
    private fun buildSystemPrompt(): String {
        return """你是一个NBA数据分析助手，可以回答用户关于NBA的问题。
请用中文回答，简洁专业。

你可以基于你的知识回答以下类型的问题：
- NBA历史数据和记录
- 球员和球队统计数据
- 比赛分析和预测
- 季后赛对阵和晋级情况
- NBA规则和赛制说明

请注意：你的知识有时间截止点，无法获取实时数据。"""
    }
    
    private fun appendOutput(text: String) {
        outputArea.append(text)
        outputArea.caretPosition = outputArea.document.length
    }
    
    fun dispose() {
        scope.cancel()
    }
    
    companion object {
        // 智谱 GLM-4.7-Flash 默认配置
        const val DEFAULT_API_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL = "glm-4.7-flash"
        
        // 配置参考：https://docs.bigmodel.cn/cn/guide/models/free/glm-4.7-flash
    }
}

/**
 * 设置状态持久化
 *
 * 包含两部分配置：
 * 1. 数据源配置（比分、排名、季后赛等数据来源）
 * 2. AI 助手配置（API Key、模型等）
 */
@Service(Service.Level.APP)
@State(name = "NBASettings", storages = [Storage("nba_settings.xml")])
class NBASettingsState : com.intellij.openapi.components.PersistentStateComponent<NBASettingsState> {
    // ===== 数据源配置 =====
    /** 主数据源 ID（espn / nba_com / ball_dont_lie / sports_db / tencent） */
    var dataSource: String = "espn"
    /** Ball Don't Lie API Key（可选） */
    var ballDontLieApiKey: String = ""
    /** TheSportsDB API Key（可选，默认使用公共 Key "3"） */
    var sportsDbApiKey: String = ""
    /** 文字转播来源偏好（auto / zhibo8 / sina / espn），由文字转播对话框的下拉维护 */
    var playByPlaySource: String = "auto"

    // ===== AI 助手配置 =====
    var apiUrl: String = ""
    var apiKey: String = ""
    var model: String = ""
    var maxTokens: String = ""
    var temperature: String = ""
    var stream: Boolean = true  // 流式输出开关

    companion object {
        fun getInstance(): NBASettingsState {
            return ApplicationManager.getApplication().getService(NBASettingsState::class.java)
        }
    }

    override fun getState(): NBASettingsState? = this

    override fun loadState(state: NBASettingsState) {
        this.dataSource = state.dataSource
        this.ballDontLieApiKey = state.ballDontLieApiKey
        this.sportsDbApiKey = state.sportsDbApiKey
        this.playByPlaySource = state.playByPlaySource
        this.apiUrl = state.apiUrl
        this.apiKey = state.apiKey
        this.model = state.model
        this.maxTokens = state.maxTokens
        this.temperature = state.temperature
        this.stream = state.stream
    }
}

/**
 * 设置页面
 *
 * 包含两个 Tab：
 * 1. 数据源设置 - 选择比分、排名等数据的来源
 * 2. AI 助手设置 - 配置 API Key、模型等
 */
class NBASettingsConfigurable : com.intellij.openapi.options.Configurable {
    // 数据源设置组件
    private var dataSourceCombo: JComboBox<String>? = null
    private var ballDontLieKeyField: JTextField? = null
    private var sportsDbKeyField: JTextField? = null

    // AI 助手设置组件
    private var apiurlField: JTextField? = null
    private var apikeyField: JTextField? = null
    private var modelField: JTextField? = null
    private var maxTokensField: JTextField? = null
    private var temperatureField: JTextField? = null
    private var streamCheckBox: JCheckBox? = null

    // 可用数据源列表（与 DataSource 枚举保持一致，按推荐优先级排序）
    private val dataSourceOptions = listOf(
        "ESPN API" to "espn",
        "NBA.com 官方" to "nba_com",
        "Ball Don't Lie" to "ball_dont_lie",
        "TheSportsDB" to "sports_db",
        "腾讯NBA" to "tencent",
        "直播吧" to "zhibo8",
        "新浪NBA" to "sina"
    )

    override fun getDisplayName(): String = "NBA 插件设置"

    override fun createComponent(): JComponent {
        val settings = NBASettingsState.getInstance()

        val tabbedPane = JTabbedPane().apply {
            border = JBUI.Borders.empty(5)
        }

        // ===== Tab 1: 数据源设置 =====
        tabbedPane.addTab("数据源", createDataSourcePanel(settings))

        // ===== Tab 2: AI 助手设置 =====
        tabbedPane.addTab("AI 助手", createAIPanel(settings))

        return tabbedPane
    }

    /**
     * 创建数据源设置面板
     */
    private fun createDataSourcePanel(settings: NBASettingsState): JComponent {
        val panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(10)
        }

        val gbc = GridBagConstraints().apply {
            insets = Insets(5, 5, 5, 5)
            anchor = GridBagConstraints.WEST
        }

        // 主数据源选择
        gbc.gridx = 0; gbc.gridy = 0
        panel.add(JLabel("主数据源:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        val sourceNames = dataSourceOptions.map { it.first }.toTypedArray()
        val selectedIndex = dataSourceOptions.indexOfFirst { it.second == settings.dataSource }
            .coerceAtLeast(0)
        dataSourceCombo = JComboBox(sourceNames).also {
            it.selectedIndex = selectedIndex
            it.toolTipText = "获取 NBA 数据的主要来源"
            panel.add(it, gbc)
        }

        // Ball Don't Lie API Key
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JLabel("Ball Don't Lie Key:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        val maskedBdlKey = maskApiKey(settings.ballDontLieApiKey)
        ballDontLieKeyField = JTextField(maskedBdlKey, 40).also {
            it.toolTipText = "Ball Don't Lie API Key（免费注册获取，用作备用数据源）"
            panel.add(it, gbc)
        }

        // TheSportsDB API Key
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JLabel("TheSportsDB Key:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        val maskedSportsDbKey = maskApiKey(settings.sportsDbApiKey)
        sportsDbKeyField = JTextField(maskedSportsDbKey, 40).also {
            it.toolTipText = "TheSportsDB API Key（可选，留空使用免费公共 Key）"
            panel.add(it, gbc)
        }

        // 说明
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.insets = Insets(15, 5, 5, 5)
        panel.add(JLabel("""
            <html><body style='width:520px'>
            <b>数据源说明（按推荐优先级排序）：</b><br>
            • <b>ESPN API</b>: 免费公开接口，数据准确稳定，国际访问快（无需 Key）<br>
            • <b>NBA.com 官方</b>: stats.nba.com 官方统计 API，数据最权威（无需 Key，国内较慢）<br>
            • <b>Ball Don't Lie</b>: 开源社区免费 API，需注册 Key，balldontlie.io<br>
            • <b>TheSportsDB</b>: 全品类体育免费 API（无需 Key，留空用公共 Key 3）<br>
            • <b>腾讯NBA</b>: 国内访问快，中文队名，含季后赛对阵图（无需 Key）<br>
            • <b>直播吧</b>: zhibo8.com 中文比分数据，国内访问快（无需 Key，仅比分赛程）<br>
            • <b>新浪NBA</b>: 新浪体育中文数据，NBA 专属接口，含每节比分（无需 Key，仅比分赛程）<br><br>
            <b>自动降级机制</b>：当主数据源请求失败时，会自动按优先级尝试其他备用数据源。<br>
            配置越多数据源，容错能力越强。<br><br>
            <b>注册地址：</b><br>
            • Ball Don't Lie: <a href="https://app.balldontlie.io/">app.balldontlie.io</a><br>
            • TheSportsDB: <a href="https://www.thesportsdb.com/">www.thesportsdb.com</a>
            </body></html>
        """.trimIndent()), gbc)

        return panel
    }

    /**
     * 创建 AI 助手设置面板
     */
    private fun createAIPanel(settings: NBASettingsState): JComponent {
        val panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(10)
        }

        val gbc = GridBagConstraints().apply {
            insets = Insets(5, 5, 5, 5)
            anchor = GridBagConstraints.WEST
        }

        // API URL
        gbc.gridx = 0; gbc.gridy = 0
        panel.add(JLabel("API URL:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        apiurlField = JTextField(settings.apiUrl, 40).also {
            it.toolTipText = "完整请求地址"
            panel.add(it, gbc)
        }

        // API Key（脱敏显示）
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JLabel("API Key:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        val maskedKey = maskApiKey(settings.apiKey)
        apikeyField = JTextField(maskedKey, 40).also {
            it.toolTipText = "API 密钥（脱敏显示）"
            panel.add(it, gbc)
        }

        // Model
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JLabel("模型:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        modelField = JTextField(settings.model, 40).also {
            it.toolTipText = "模型名称"
            panel.add(it, gbc)
        }

        // Max Tokens
        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JLabel("Max Tokens:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        maxTokensField = JTextField(settings.maxTokens, 40).also {
            it.toolTipText = "最大输出 token 数"
            panel.add(it, gbc)
        }

        // Temperature
        gbc.gridx = 0; gbc.gridy = 4; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JLabel("Temperature:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        temperatureField = JTextField(settings.temperature, 40).also {
            it.toolTipText = "温度参数 0-2"
            panel.add(it, gbc)
        }

        // Stream
        gbc.gridx = 0; gbc.gridy = 5; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JLabel("流式输出:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        streamCheckBox = JCheckBox("启用流式输出", settings.stream).also {
            panel.add(it, gbc)
        }

        // 说明
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2; gbc.insets = Insets(15, 5, 5, 5)
        panel.add(JLabel("""
            <html><body style='width:500px'>
            <b>AI 助手参数说明：</b><br>
            • <b>API URL</b>: 完整请求地址（含路径）<br>
            • <b>API Key</b>: 认证密钥<br>
            • <b>模型</b>: 模型名称（如 glm-4.7-flash）<br>
            • <b>Max Tokens</b>: 最大输出长度<br>
            • <b>Temperature</b>: 随机性控制（0-2）<br>
            • <b>流式输出</b>: 实时显示回复（部分模型不支持）<br><br>
            <b>参考文档：</b> <a href="https://docs.bigmodel.cn/cn/guide/models/free/glm-4.7-flash">智谱 GLM-4.7-Flash</a>
            </body></html>
        """.trimIndent()), gbc)

        return panel
    }

    /**
     * API Key 脱敏：保留前3位和后3位，中间用 *** 替代
     */
    private fun maskApiKey(key: String): String {
        if (key.isBlank() || key.length <= 6) return key
        return key.take(3) + "***" + key.takeLast(3)
    }

    override fun isModified(): Boolean {
        val settings = NBASettingsState.getInstance()

        // 数据源设置检查
        val selectedDataSource = getSelectedDataSourceId()
        val dataSourceChanged = selectedDataSource != settings.dataSource

        val currentBdlKey = ballDontLieKeyField?.text ?: ""
        val bdlKeyChanged = if (currentBdlKey.contains("***")) {
            false
        } else {
            currentBdlKey != settings.ballDontLieApiKey
        }

        val currentSportsDbKey = sportsDbKeyField?.text ?: ""
        val sportsDbKeyChanged = if (currentSportsDbKey.contains("***")) {
            false
        } else {
            currentSportsDbKey != settings.sportsDbApiKey
        }

        // AI 助手设置检查
        val currentKey = apikeyField?.text ?: ""
        val keyChanged = if (currentKey.contains("***")) {
            false
        } else {
            currentKey != settings.apiKey
        }

        return dataSourceChanged || bdlKeyChanged || sportsDbKeyChanged ||
               apiurlField?.text != settings.apiUrl ||
               keyChanged ||
               modelField?.text != settings.model ||
               maxTokensField?.text != settings.maxTokens ||
               temperatureField?.text != settings.temperature ||
               streamCheckBox?.isSelected != settings.stream
    }

    /**
     * 获取当前选中的数据源 ID
     */
    private fun getSelectedDataSourceId(): String {
        val index = dataSourceCombo?.selectedIndex ?: 0
        return dataSourceOptions.getOrNull(index)?.second ?: "espn"
    }

    override fun apply() {
        val settings = NBASettingsState.getInstance()

        // 数据源设置
        settings.dataSource = getSelectedDataSourceId()

        val currentBdlKey = ballDontLieKeyField?.text ?: ""
        settings.ballDontLieApiKey = if (currentBdlKey.contains("***")) {
            settings.ballDontLieApiKey
        } else {
            currentBdlKey
        }

        val currentSportsDbKey = sportsDbKeyField?.text ?: ""
        settings.sportsDbApiKey = if (currentSportsDbKey.contains("***")) {
            settings.sportsDbApiKey
        } else {
            currentSportsDbKey
        }

        // AI 助手设置
        settings.apiUrl = apiurlField?.text ?: ""
        val currentKey = apikeyField?.text ?: ""
        settings.apiKey = if (currentKey.contains("***")) {
            settings.apiKey
        } else {
            currentKey
        }
        settings.model = modelField?.text ?: ""
        settings.maxTokens = maxTokensField?.text ?: ""
        settings.temperature = temperatureField?.text ?: ""
        settings.stream = streamCheckBox?.isSelected ?: true
    }

    override fun reset() {
        val settings = NBASettingsState.getInstance()

        // 数据源设置
        val selectedIndex = dataSourceOptions.indexOfFirst { it.second == settings.dataSource }
            .coerceAtLeast(0)
        dataSourceCombo?.selectedIndex = selectedIndex
        ballDontLieKeyField?.text = maskApiKey(settings.ballDontLieApiKey)
        sportsDbKeyField?.text = maskApiKey(settings.sportsDbApiKey)

        // AI 助手设置
        apiurlField?.text = settings.apiUrl
        apikeyField?.text = maskApiKey(settings.apiKey)
        modelField?.text = settings.model
        maxTokensField?.text = settings.maxTokens
        temperatureField?.text = settings.temperature
        streamCheckBox?.isSelected = settings.stream
    }
}
