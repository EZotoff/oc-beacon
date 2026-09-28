package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #461(2026-09-29):流式 pilot 准入契约——静态文本不得走 StreamingMarkdownState。
 *
 * 根因(真机三方定罪:日志 settle H=0×3 / dump 展开区无节点 / 像素 8dp 单档间距):
 * ReasoningBlock 等静态调用点未传 asyncParse(默认 false)→ MarkdownContent pilot
 * 分支仅判 !isUser → 历史思考文本误入流式路径——StreamingMarkdownState 初始空,
 * 靠 LaunchedEffect 逐帧 append 填充;在 CardExpandReveal ε/展开窗内与 settle
 * 竞态,600ms 内 append 未落地 → H=0 僵尸态(展开集完成但 0 高,toggle 永无视觉)。
 *
 * 契约:pilot 仅服务「真流式」(asyncParse=false 且无外部覆写态且非用户消息)——
 * 静态文本一律 asyncParse=true 走 #428 分层(>2048 异步 Default/≤2048 同步内联
 * 首测即终高)。
 */
class StreamingPilotEligibilityTest {

    @Test
    fun `static text with asyncParse never enters streaming pilot`() {
        // #461 主锚:历史思考文本(ReasoningBlock 传 asyncParse=!isStreaming=true)
        assertFalse(
            streamingPilotEligible(hasOverrideState = false, asyncParse = true, isUser = false),
        )
    }

    @Test
    fun `streaming assistant text without override enters pilot`() {
        // 设计意图保持:流式正文(asyncParse=!isStreaming=false)
        assertTrue(
            streamingPilotEligible(hasOverrideState = false, asyncParse = false, isUser = false),
        )
    }

    @Test
    fun `external override state bypasses pilot`() {
        assertFalse(
            streamingPilotEligible(hasOverrideState = true, asyncParse = false, isUser = false),
        )
    }

    @Test
    fun `user messages never enter pilot`() {
        assertFalse(
            streamingPilotEligible(hasOverrideState = false, asyncParse = false, isUser = true),
        )
    }
}
