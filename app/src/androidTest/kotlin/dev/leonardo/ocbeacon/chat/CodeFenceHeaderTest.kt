package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.assertIsDisplayed

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocbeacon.builder.anAssistantMessage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #488 批3① 定罪测试（B2 模拟器夜批 2026-10-10）：showHeader 代码块顶栏。
 *
 * 背景链：模拟器真屏像素取证（004547_b3_codeblock_found.png 逐层裁剪判读）——
 * python 围栏代码块内部顶缘到首行代码之间只有均匀灰padding，无语言标签
 * （PYTHON）无复制键；但安装包反汇编（apkanalyzer dex code）三层全绿：
 * 围栏回调 const 0x1 → SafeHighlightedCode(showHeader) 直传 → 库 0.45.0
 * if(showHeader) 分支存在。静态链与像素矛盾 → 仪器化断言定罪。
 *
 * 断言三件套（真实 ChatScreen 渲染路径）：
 * 1. 语言标签 "PYTHON" 存在且显示（MarkdownCodeTopBar 顶栏独有）；
 * 2. contentDescription "Code block, python" 存在（库 MarkdownCodeBackground
 *    语义，证明库壳在跑——非 header 专属，作对照锚）；
 * 3. 几何：代码文本节点顶边必须低于代码盒（"Code block, python" 语义节点）
 *    顶边 20px 以上——顶栏占高则首行代码必然下移。
 */
@HiltAndroidTest
class CodeFenceHeaderTest : BaseChatTest() {

    private fun seedAssistantCodeMessage(code: String) {
        val mwp = anAssistantMessage(sessionId = TEST_SESSION) { text(code) }
        fakeChat.messagesState.value = listOf(mwp.info)
        fakeChat.allPartsMapState.value = mapOf(mwp.info.id to mwp.parts)
        renderChatScreen()
    }

    @Test
    fun codeFence_semanticsAnchor_present() {
        seedAssistantCodeMessage("```python\nfor i in range(1, 10):\n    print(f\"- {i}\")\n```\n")
        composeRule.onAllNodesWithContentDescription("Code block, python")[0]
            .assertIsDisplayed()
    }

    @Test
    fun codeFence_rendersLanguageHeaderLabel() {
        seedAssistantCodeMessage("```python\nfor i in range(1, 10):\n    print(f\"- {i}\")\n```\n")
        composeRule.onAllNodesWithText("PYTHON", substring = true)[0]
            .assertIsDisplayed()
    }

    @Test
    fun codeFence_headerAddsHeightAboveCodeText() {
        seedAssistantCodeMessage("```python\nfor i in range(1, 10):\n    print(f\"- {i}\")\n```\n")
        val blockBox = composeRule.onAllNodesWithContentDescription("Code block, python")[0]
            .fetchSemanticsNode().positionInRoot.y
        val codeText = composeRule.onAllNodesWithText("for i in range(1, 10):", substring = true)[0]
            .fetchSemanticsNode().positionInRoot.y
        // 顶栏存在 ⇒ 文本起点必须在盒顶之下至少 20px（标签行+分隔线高度）
        assertTrue(
            "code text y=$codeText should sit >20px below box y=$blockBox",
            codeText - blockBox > 20f
        )
    }
}
