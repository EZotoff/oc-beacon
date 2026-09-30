package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * markdown 稳态粒度扩展（用户裁决 P3）：引用块/ATX 标题/嵌套列表的行级放行。
 */
class SafePrefixGateBlockGranularityTest {

    private fun rel(snapshot: String, already: Int = 0): Int =
        SafePrefixGate.releaseLength(snapshot, already)

    @Test
    fun `引用块行逐行放行`() {
        val s = "> 第一段引用\n> 第二段引用\n"
        assertEquals(s.length, rel(s))
    }

    @Test
    fun `引用块带未完尾行只放完整行`() {
        // 未完行（无\n）扣留
        val got = rel("> a\n> 未完")
        assertEquals("> a\n".length, got)
    }

    @Test
    fun `ATX标题行放行`() {
        assertEquals("## 标题\n".length, rel("## 标题\n"))
        assertEquals("# T\n".length, rel("# T\n"))
    }

    @Test
    fun `标题未完行扣留`() {
        assertEquals(0, rel("# 未完标题"))
    }

    @Test
    fun `嵌套列表项行放行`() {
        assertEquals("- a\n  - b\n".length, rel("- a\n  - b\n"))
        assertEquals("* a\n  * b\n".length, rel("* a\n  * b\n"))
    }

    @Test
    fun `四级缩进代码块保持扣留`() {
        // 4+ 空格缩进=缩进代码块——不能当嵌套列表放（半行语义不同）
        assertEquals(0, rel("    code line\n"))
    }

    // ===== #471④-a：GFM 任务列表项行级放行（验收发现「4 条一次性吐出」根修） =====

    @Test
    fun `任务项行整行放行`() {
        assertEquals("- [ ] a\n".length, rel("- [ ] a\n"))
        assertEquals("- [x] done\n".length, rel("- [x] done\n"))
        assertEquals("1. [ ] a\n".length, rel("1. [ ] a\n"))
        assertEquals("  - [ ] a\n".length, rel("  - [ ] a\n"))
        assertEquals("* [ ] a\n".length, rel("* [ ] a\n"))
        assertEquals("+ [ ] a\n".length, rel("+ [ ] a\n"))
        assertEquals("- [ ]\n".length, rel("- [ ]\n")) // 裸复选框
    }

    @Test
    fun `多条任务项按批预算逐条续放`() {
        val s = "- [ ] one\n- [ ] two\n- [ ] three\n"
        assertEquals(s.length, rel(s))
        // 批预算与行边界对齐：逐条整行放行
        val r1 = SafePrefixGate.releaseLength(s, 0, 10)
        assertEquals("- [ ] one\n".length, r1)
        val r2 = SafePrefixGate.releaseLength(s, r1, 10)
        assertEquals("- [ ] one\n- [ ] two\n".length, r2)
        // 批预算任意截断（#438① 行中截断既有语义）：截点落行中 "- " 处
        //（[ 硬停前的纯文字前缀，安全）；该项剩余部分续批经兜底分支推进
        assertEquals("- [ ] one\n- ".length, SafePrefixGate.releaseLength(s, 0, 12))
    }

    @Test
    fun `未完任务项条目文字渐进放行`() {
        // v2：复选框+空白前缀齐备即定案——未完行的条目尾部纯文字渐进放行（字面=最终）
        assertEquals("- [ ] 未完".length, rel("- [ ] 未完"))
        // 条目内未闭合括号：渐进止于 [ 硬停前
        assertEquals("- [ ] see ".length, rel("- [ ] see [link"))
        // 次行未完：条目文字同样渐进（含未完标记行整体）
        assertEquals("- [ ] a\n- [ ] b".length, rel("- [ ] a\n- [ ] b"))
    }

    @Test
    fun `行中截断后完整任务行到达仍能整行毕业`() {
        // E2E 回归："- [" 相位复选框未闭合只放 "- "（截在 [ 前）——v1 在此后
        // 冻结到空行毕业；完整行到达时锚定真实行首恢复，整行毕业
        val full = "- [ ] Wake up at the same time every single day\n- [ ] Drink water\n"
        assertEquals("- ".length, SafePrefixGate.releaseLength("- [", 0))
        // 首行(0-47)整行毕业 + 次行未完文字渐进直出（v1 冻结时此处得 2）
        val r1 = SafePrefixGate.releaseLength(full.substring(0, 60), 2)
        assertEquals(60, r1)
        // 次行完整后同样逐条毕业
        val r2 = SafePrefixGate.releaseLength(full, r1)
        assertEquals(full.length, r2)
    }

    @Test
    fun `复选框后无空白不进任务分支`() {
        // GFM 任务项要求复选框后空白——回落兜底扣留
        assertEquals("- ".length, rel("- [ ]text\nnext"))
    }

    @Test
    fun `任务项含未定案数学开定界符保持扣留`() {
        assertEquals("- ".length, rel("- [ ] has $$ inline\nnext"))
        assertEquals("- ".length, rel("- [ ] has \\[ open\nnext"))
    }

    @Test
    fun `任务项内容含未闭合括号行内安全帽截断`() {
        // 行内安全帽：未闭合 [link → 只放 "- [ ] see " 前缀
        assertEquals("- [ ] see ".length, rel("- [ ] see [link text\nmore"))
    }
}
