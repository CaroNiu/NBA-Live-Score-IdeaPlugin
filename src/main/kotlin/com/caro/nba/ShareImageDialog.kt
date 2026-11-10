package com.caro.nba

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Image
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 分享图预览对话框：展示生成的比分卡片，并提示图片已复制到剪贴板。
 */
class ShareImageDialog(project: Project?, private val image: Image) : DialogWrapper(project) {

    init {
        title = "分享图已生成"
        setOKButtonText("完成")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        // 等比缩放到预览宽度（原始 1000 宽，面板内显示 480）
        val previewWidth = 480
        val previewHeight = (image.getHeight(null) * previewWidth / image.getWidth(null).coerceAtLeast(1))
        val icon = JBLabel(javax.swing.ImageIcon(image.getScaledInstance(previewWidth, previewHeight, Image.SCALE_SMOOTH)))
        icon.horizontalAlignment = javax.swing.SwingConstants.CENTER
        panel.add(icon, BorderLayout.CENTER)
        panel.add(
            JBLabel("已复制到剪贴板，可直接粘贴到微信 / QQ / 微博"),
            BorderLayout.SOUTH
        )
        return panel
    }
}
