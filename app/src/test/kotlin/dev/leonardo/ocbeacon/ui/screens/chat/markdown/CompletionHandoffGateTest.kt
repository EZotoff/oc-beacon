package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #504（2026-10-02 真机定罪）：DSH 完结换装闪塌——合成 part.id→权威 seq id
 * 换代经 key(part.id) 销毁 pilot 子树，#472 的本地 pilotEverRendered 保持记忆
 * 丢失 → 异步终态 Loading 占位（8754px→200px 塌缩 260ms）。
 *
 * 修复=换代 seed 桥（CompletionHandoff 单槽交接所 + 内容相等门）：dispose 侧
 * 存归一化终态，新组合侧内容相等才取用。本类钉**内容门**语义——相等命中
 * （含归一化等价形态）、不等/空槽不命中（宁缺勿错配=自校验安全）。
 */
class CompletionHandoffGateTest {

    private fun motive(msg: String) = println("[MOTIVE] $msg")

    @Test
    fun `归一化等价文本命中（换装两侧同变换）`() {
        motive("#504 主断言：stash 存的是归一化形态、取用侧对原文归一化后比对——DSH 换装「流式终帧原文 vs 权威 seq 原文」若字面相同或仅归一化等价，必命中（换装视觉恒等前提）")
        val normalized = normalizeForStreaming("# Title\n\nbody text")
        // 取用侧传原文（归一化后与 stash 相等）
        assertTrue(completionHandoffMatches(normalized, "# Title\n\nbody text"))
    }

    @Test
    fun `尾差容错 前缀相等且缺口小 命中`() {
        motive("#504 首验定罪修正：pilot 终帧落后终态 2 字符（末批 delta/扣留尾）致严格相等恒 miss——前缀相等+缺口≤512=同文档命中，hold 渲染旧帧、终态原子补齐（2 字符差替代 10330px 塌缩）")
        val stashKey = normalizeForStreaming("# T\n\n" + "body ".repeat(100))
        val incoming = "# T\n\n" + "body ".repeat(100) + "尾批两字"
        assertTrue(completionHandoffMatches(stashKey, incoming))
    }

    @Test
    fun `尾部区域改写命中（真机 19h58m 定罪形态）`() {
        motive("真机取证：gap=16 但 startsWith=false——完结内容对尾部区域改写（围栏闭合/末段修正），非纯追加。公共前缀分叉点落在两串末 256 字符内=同文档命中")
        val body = "paragraph line ".repeat(300)
        val stashKey = normalizeForStreaming("# T\n\n$body```py\nprint(")
        val incoming = "# T\n\n$body```py\nprint(x)\n```"
        assertTrue(completionHandoffMatches(stashKey, incoming))
    }

    @Test
    fun `长文中段分叉不命中（宁缺勿错配）`() {
        motive("安全门：中段分叉=不同文档——分叉点远早于尾部松弛区，公共前缀远短于两串，恒 miss")
        val head = "shared prefix ".repeat(80)
        val a = head + "document A unique middle ".repeat(60)
        val b = head + "document B DIFFERENT middle ".repeat(60)
        assertFalse(completionHandoffMatches(normalizeForStreaming(a), b))
        // 短串（<2048）生产上不进此门（同步解析路径）——松弛门对短串无区分力，不测
    }

    @Test
    fun `缺口超容错上限不命中`() {
        motive("大缺口=不同文档（非换装尾差量级）——512 字符上限防长前缀误配")
        val stashKey = "body ".repeat(100)
        val incoming = "body ".repeat(100) + "tail ".repeat(200)
        assertTrue(incoming.length - stashKey.length > COMPLETION_HANDOFF_TAIL_TOLERANCE_CH)
        assertFalse(completionHandoffMatches(stashKey, incoming))
    }

    @Test
    fun `空槽不命中`() {
        motive("无 stash（首跑/进程冷启）恒 miss——不制造幻影 seed")
        assertFalse(completionHandoffMatches(null, "any content"))
    }

    @Test
    fun `归一化重写形态两侧等价命中`() {
        motive("归一化闭合重写（如任务列表预览/tex 围栏类变换）使原文不等但归一化相等——两侧同变换保证坐标一致，此为换装恒等命中的鲁棒性来源")
        val rawA = "# T\n\n- [ ] item"
        val rawB = "# T\n\n- [ ] item"
        val stashKey = normalizeForStreaming(rawA)
        assertTrue(stashKey == normalizeForStreaming(rawB))
        assertTrue(completionHandoffMatches(stashKey, rawB))
    }
}
