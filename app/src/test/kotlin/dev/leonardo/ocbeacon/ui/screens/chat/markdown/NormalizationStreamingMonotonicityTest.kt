package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * #471③ 核心性质测试（本批灵魂，spec §4.1）。
 *
 * 性质：对逐字符增长的快照序列 S[:k]，每个快照先经 normalizeForStreaming
 * 归一化再交 SafePrefixGate 放行——已放行前缀跨快照永不改写（非前缀
 * 计数 = 0）。这是「归一化前移到流式 ingest」正确性的函数级保证：
 * 归一化的回改点必须全部落在 gate 扣留区（spec §3.3 逐变换矩阵），
 * 任何破口在此失败。运行期兜底（#472 300ms 宽限 + resetKey 重建）是
 * 保险丝，不是本性质的替代。
 *
 * 附 seeded 随机游走（Random(42)）：token 池采样拼接，覆盖未枚举形态。
 */
class NormalizationStreamingMonotonicityTest {

    /**
     * 模拟 pilot 全流：逐快照归一化 → gate 从上次放行点续放 → 断言：
     * 1. 放行长度单调不减；
     * 2. 新快照的放行前缀以旧放行前缀为前缀（已上屏内容零改写）；
     * 3. 完结（EOF）全文归一化包含全部已放行前缀（一字不丢）。
     */
    private fun assertMonotonic(label: String, full: String, step: Int) {
        var released = 0
        var releasedText = ""
        var k = step
        while (k <= full.length) {
            val snapshot = full.substring(0, k)
            val n = normalizeForStreaming(snapshot)
            val r = SafePrefixGate.releaseLength(n, released)
            assertTrue(
                label + ": gate 放行回退 (k=" + k + " released=" + released + " r=" + r + ")",
                r >= released,
            )
            val newReleasedText = n.take(r)
            assertTrue(
                label + ": 已放行前缀被改写 (k=" + k + ")——归一化破口落在放行区",
                newReleasedText.startsWith(releasedText),
            )
            released = r
            releasedText = newReleasedText
            if (k == full.length) break
            k = (k + step).coerceAtMost(full.length)
        }
        assertTrue(
            label + ": 完结全文丢失已放行前缀（EOF flush 不守恒）",
            normalizeForStreaming(full).startsWith(releasedText),
        )
    }

    @Test
    fun `math block fixtures monotonic`() {
        val fixture = """
            引入文字说明这个公式的作用与来源背景。
            $$
            E = mc^2
            \alpha + \beta = \gamma
            $$
            后续解释文字继续展开论述。

            结束段落。
        """.trimIndent()
        assertMonotonic("math-block", fixture, step = 1)
    }

    @Test
    fun `inline math fixtures monotonic`() {
        val fixture = "行内 \\(x^2\\) 与 \\(y_i\\) 混排在普通文字里，" +
            "还有 \\[z_k\\] 形态以及后续句子继续书写下去。"
        assertMonotonic("inline-math", fixture, step = 1)
    }

    @Test
    fun `table without leading blank line monotonic`() {
        val fixture = """
            介绍文字直接贴表格
            | 名称 | 值 |
            |---|---|
            | alpha | 1 |
            | beta | 2 |
            | gamma | 3 |
            表格后总结
        """.trimIndent()
        assertMonotonic("table-no-blank", fixture, step = 1)
    }

    @Test
    fun `task markers monotonic`() {
        val fixture = """
            计划如下：
            - ☐ 第一项待办
            - ☑ 第二项完成
            - ✅ 第三项完成

            全部列完。
        """.trimIndent()
        assertMonotonic("task-markers", fixture, step = 1)
    }

    @Test
    fun `task list per item line monotonic`() {
        // #471④-a：任务项行级放行——☐/☑/✅ 归一化为 [ ]/[x] 后逐条毕业；
        // 含原生 GFM 形态、有序形态、括号内容（行内安全帽截断面）。
        val fixture = """
            计划如下：
            - ☐ 第一项：阅读需求文档
            - ☑ 第二项：分析技术方案
            - ✅ 第三项：输出最终结果
            - [ ] 原生 GFM 第四项
            - [ ] 引用 [链接] 与文字
            1. [x] 有序完成项
            2. [ ] 有序未完项

            收尾段落。
        """.trimIndent()
        assertMonotonic("task-list-per-item", fixture, step = 1)
    }

    @Test
    fun `oversized paragraph monotonic`() {
        // >3000 段落：位置制升级边界随流到达出现在新 delta 里
        val longPara = (1..400).joinToString("\n") { "长段落流式行 " + it + " 继续填充内容直到越过阈值边界" }
        assertMonotonic("oversized", longPara, step = 97)
    }

    @Test
    fun `crlf mixed content monotonic`() {
        val fixture = "段落一文字。\r\n段落二文字。\r\n\r\n| 表头 | 列 |\r\n|---|---|\r\n| 行 | 值 |\r\n" +
            "尾部文字\r\n\r\n- ☐ 待办\r\n- ✅ 完成\r\n\n$$\r\na+b\r\n$$"
        assertMonotonic("crlf-mixed", fixture, step = 7)
    }

    @Test
    fun `fenced code monotonic`() {
        val fixture = """
            ```kotlin
            fun first() {}
            fun second() {}
            fun third() {}
            ```

            围栏后正文。
        """.trimIndent()
        assertMonotonic("fence", fixture, step = 3)
    }

    @Test
    fun `fenced code volatile interior monotonic_487`() {
        // #487 开放围栏行级放行后块内内容流中即上屏：围栏内含标记/表形行/
        // $$/内层``` 的归一化不得改写已放行前缀（栏内字面 + MarkdownFenceLine
        // 栏态协调的性质级保证）。四反引号外栏 + 内层三反引号行同时覆盖
        // 开栏长度感知闭栏判定。
        val fixture = (
            "前文。\n\n" +
                "````kotlin\n" +
                "val s = \"**not bold**\"\n" +
                "| col | shaped |\n" +
                "inner ``` backticks in line\n" +
                "money ${'$'}${'$'} signs \\[ bracket\n" +
                "````\n" +
                "后文段落。\n"
            )
        assertMonotonic("fence-volatile", fixture, step = 1)
    }

    @Test
    fun `setext heading shape monotonic`() {
        // setext 升格为 accepted-gap（gate :29-30）——本测试只验证归一化
        // 不引入非前缀（===/--- 行无归一化变换）。
        val fixture = """
            标题文字行
            ===

            正文段落。
            小标题
            ---

            尾部。
        """.trimIndent()
        assertMonotonic("setext", fixture, step = 1)
    }

    @Test
    fun `mixed everything monotonic`() {
        val fence = "```"
        val mixed = buildString {
            append("## 概述\n\n开场白文字若干。\n\n")
            append("文字紧贴表格\n| a | b |\n|---|---|\n| 1 | 2 |\n\n")
            append("$$\n\\int_0^1 f(x)\\,dx\n$$\n\n")
            append("- ☐ 待办甲\n- ☑ 待办乙\n\n")
            append(fence + "\ncode inside fence\nsecond line\n" + fence + "\n\n")
            append((1..200).joinToString("\n") { "混合尾部长文行 " + it + " 继续填充" })
            append("\n\n行内 \\(e^{i\\pi}\\) 公式收尾。\n")
        }
        assertMonotonic("mixed", mixed, step = 37)
    }

    @Test
    fun `seeded random walk monotonic`() {
        val rnd = Random(42)
        val tokens = listOf(
            "普通文字", "另一段", "单词", "text", "line",
            "\n", "\n\n", "\r\n", "| a |\n|---|\n| 1 |", "- 列表项", "1. 有序",
            "- ☐ 待办", "- ✅ 完成", "$$\n公式体\n$$", "\\(x\\)",
            "```\n围栏内\n```", "# 标题", "> 引用", "**粗体**", "===", "---",
        )
        repeat(8) { trial ->
            val text = buildString {
                repeat(500) { append(tokens[rnd.nextInt(tokens.size)]) }
            }
            assertMonotonic("random-" + trial, text, step = 53)
        }
    }
}
