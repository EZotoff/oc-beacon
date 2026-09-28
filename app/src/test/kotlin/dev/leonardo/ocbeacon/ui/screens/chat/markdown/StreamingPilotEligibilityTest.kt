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

    // ============ #472 完结换装无缝:pilot 终帧保持判定 ============
    //
    // 根因(真机定罪):流式完结(asyncParse 翻转)pilot 整树 dispose,>2048 字符
    // 正文切异步解析首帧 State.Loading≈0 高→Success 弹回全高——RESIZE
    // 1105→865→1580(41ms 两连跳)。修复:pilot 曾渲染且 async 终态未就绪时
    // 保持 pilot 终帧(async 并行预热),Success 后无缝切换。

    @Test
    fun `pilot ever rendered with async pending holds terminal frame`() {
        assertTrue(pilotTerminalHold(pilotEverRendered = true, asyncTerminalPending = true))
    }

    @Test
    fun `async ready or no terminal releases hold`() {
        // #472 验收轮回归定罪(2026-09-28 真机):≤2048 完结无 async 终态,
        // 旧语义 (ever && !ready) 的 ready 恒 false → pilot 永保持 → 完结
        // part 重组(sync/MessagePartUpdated)的非前缀砸进 pilot 静默重建
        // (resetKey++)→ 内容清空+限速回灌 = 「闪烁清空再恢复」。hold 只桥接
        // async Loading 间隙;≤2048 完结即走同步解析路径(首帧全高,无闪)。
        assertFalse(pilotTerminalHold(pilotEverRendered = true, asyncTerminalPending = false))
    }

    @Test
    fun `never rendered never holds`() {
        assertFalse(pilotTerminalHold(pilotEverRendered = false, asyncTerminalPending = true))
        assertFalse(pilotTerminalHold(pilotEverRendered = false, asyncTerminalPending = false))
    }

    // ============ #472 验收轮回归:非前缀宽限冻结(数据层摆动不重建) ============

    @Test
    fun `nonPrefix within grace freezes not rebuilds`() {
        // 宽限窗内:保树保进度,等旧串回来无缝续播
        assertFalse(nonPrefixRebuildDue(nonPrefixSinceMs = 1000L, nowMs = 1200L))
    }

    @Test
    fun `nonPrefix past grace rebuilds`() {
        assertTrue(nonPrefixRebuildDue(nonPrefixSinceMs = 1000L, nowMs = 1400L))
    }

    @Test
    fun `nonPrefix unarmed never rebuilds`() {
        assertFalse(nonPrefixRebuildDue(nonPrefixSinceMs = -1L, nowMs = 99999L))
    }

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
