package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
 * if(showHeader) 分支在包内。静态链与像素矛盾 → 仪器化断言定罪。
 * （后续勘误：像素"无顶栏"系视口顶裁剪伪影——块钉在视口顶时 uiautomator
 * 把 bounds 裁到视口、顶栏整棵不出现；微滚后 PYTHON 标签现形。）
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

    /**
     * #488 批2 初值缓存/同步着色布线回归：produceState 跳过守卫
     * （spans 非空且文本==code 才免作业）误冻会表现为内容更新后旧文本残留。
     * 同消息 id + 同 part id 追加式改写（流式 SSE 语义——组合位保留、
     * produceState 跨键不重置初值），断言终态文本推进无冻结。
     * parts 手工构造：PartListBuilder 的 part id 全局自增，复用它会销毁重建
     * 组合位，测不到同槽 re-key 路径。
     */
    @Test
    fun codeFence_contentUpdate_noStaleFreeze() {
        val stablePartId = "part-fence-stable"
        fun seed(code: String) {
            val mwp = anAssistantMessage(id = FROZEN_TEST_MSG_ID, sessionId = TEST_SESSION)
            val part = dev.leonardo.ocbeacon.domain.model.Part.Text(
                id = stablePartId, sessionId = TEST_SESSION,
                messageId = FROZEN_TEST_MSG_ID, text = code,
            )
            fakeChat.messagesState.value = listOf(mwp.info)
            fakeChat.allPartsMapState.value = mapOf(mwp.info.id to listOf(part))
        }
        seed("```python\nfor i in range(1, 10):\n    print(\"a\")\n```\n")
        renderChatScreen() // setContent 仅一次；后续状态更新经 flow 直达既有组合
        composeRule.onNodeWithText("for i in range(1, 10):", substring = true).assertIsDisplayed()
        seed(
            "```python\nfor i in range(1, 10):\n    print(\"a\")\n" +
                "    for j in range(2, 20):\n        print(\"b\")\n```\n",
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("for j in range(2, 20):", substring = true).assertIsDisplayed()
    }

    private companion object {
        const val FROZEN_TEST_MSG_ID = "msg-fence-freeze"
    }
}
