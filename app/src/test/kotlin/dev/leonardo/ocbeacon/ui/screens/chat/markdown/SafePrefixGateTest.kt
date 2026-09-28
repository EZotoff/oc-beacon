package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #437 SafePrefixGate 判定表（2026-09-26 增量直出语义重钉）。
 *
 * 纯文字（无活动标记）未完行逐批增量直出（字面=最终）；含标记行/围栏/表格
 * 按闭合语义；单批预算 400 循环内扣减。
 */
class SafePrefixGateTest {

    private fun rel(snapshot: String, already: Int = 0): Int =
        SafePrefixGate.releaseLength(snapshot, already)

    @Test
    fun `空串快照放行0`() {
        assertEquals(0, rel(""))
    }

    @Test
    fun `已放行超过快照长度时原样返回`() {
        assertEquals(3, rel("abc", 5))
    }

    @Test
    fun `纯文字未完行增量直出`() {
        assertEquals(11, rel("word1 word2"))
    }

    @Test
    fun `纯文字多行含未完行全直出`() {
        assertEquals(11, rel("line1\nline2"))
    }

    @Test
    fun `末换行快照全量直出`() {
        assertEquals(12, rel("line1\nline2\n"))
    }

    @Test
    fun `单行加换行整段直出`() {
        assertEquals(6, rel("Title\n"))
    }

    @Test
    fun `空行毕业放行含空行`() {
        assertEquals(14, rel("para one\n\nnext"))
    }

    @Test
    fun `标记行闭合构造整行放行`() {
        // #472：*bold* 闭合即定案——文字直出到快照尾（原整行扣到空行毕业）
        assertEquals(16, rel("text\n*bold* more"))
    }

    @Test
    fun `标记段空行闭合后整段毕业加尾行直出`() {
        assertEquals(18, rel("*bold* done\n\nafter"))
    }

    @Test
    fun `有序列表项逐行放行_裁决441修订`() {
        // #441 用户裁决修订：有序列表项行级定案逐行放行（原整块扣留语义废止）
        // #472：未完尾行 "2. second" 无行内构造，扫描器直出 = 全量
        assertEquals(24, rel("intro\n1. first\n2. second"))
    }

    @Test
    fun `行中数字不触发有序列表规则且直出`() {
        assertEquals(18, rel("version 1.9 is out"))
    }

    @Test
    fun `代码围栏开标记整段扣留`() {
        assertEquals(0, rel("```kotlin\nval x = 1\n"))
    }

    @Test
    fun `围栏代码块空行后整体毕业`() {
        val s = "```kotlin\nval x = 1\n```\n\nafter"
        assertEquals(30, rel(s))
    }

    @Test
    fun `表格完整行逐行放行`() {
        assertEquals(20, rel("| a | b |\n|---|---|\n| 1 | 2 |"))
    }

    @Test
    fun `表格未完行扣住`() {
        assertEquals(20, rel("| a | b |\n|---|---|\n| 1 | 2"))
    }

    @Test
    fun `表格完整行逐行渐显含尾换行`() {
        assertEquals(40, rel("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n"))
    }

    @Test
    fun `表头分隔未齐整块扣`() {
        assertEquals(0, rel("| a | b |\ntext"))
    }

    @Test
    fun `未闭合围栏整块扣留含内容行`() {
        assertEquals(5, rel("text\n```kotlin\nval x = 1\nval y = 2"))
        assertEquals(39, rel("text\n```kotlin\nval x = 1\nval y = 2\n```\n"))
    }

    @Test
    fun `超长纯文字段按预算直出`() {
        val s = buildString { repeat(3000) { append("word") ; append(it) ; append(" ") } }
        assertEquals(400, rel(s))
    }

    @Test
    fun `多批快照放行单调不回退`() {
        var released = 0
        val batches = listOf(
            "Hello", "Hello world\nthis is", "Hello world\nthis is a **te",
            "Hello world\nthis is a **test** of\n", "Hello world\nthis is a **test** of\nstreaming.\n\nDone.",
        )
        val releases = batches.map { snap ->
            val r = rel(snap, released)
            assert(r >= released)
            released = r
            r
        }
        // #472：**te 悬空扣在 ** 前（19→22 放出 "this is a "）；**test** 闭合
        // 即放行整行（22→34）；完结空行毕业收尾（51）
        assertEquals(listOf(5, 19, 22, 34, 51), releases)
    }

    @Test
    fun `already非零时从边界继续放行`() {
        assertEquals(24, rel("para one\n\nnext line\nmore", 10))
    }

    @Test
    fun `表格粘边注入补空行`() {
        val snap = "para\n| a |\n|---|\n| 1 |\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("para\n\n| a |\n|---|\n| 1 |\n\ntail", d.delta)
        assertEquals(28, d.newReleased)
    }

    @Test
    fun `表格已有空行不注入`() {
        val snap = "para\n\n| a |\n|---|\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("para\n\n| a |\n|---|\n\ntail", d.delta)
        assertEquals(23, d.newReleased)
    }

    @Test
    fun `表格前行为表格延续不注入`() {
        val snap = "| a |\n|---|\n| 1 |\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("| a |\n|---|\n| 1 |\n\ntail", d.delta)
        assertEquals(23, d.newReleased)
    }

    @Test
    fun `tasklist字符起扣留前缀放行`() {
        // #472：☐ 前的 "- " 列表标记放行（空列表项先现），☐ 起扣留等空行
        // 毕业——完结归一化变换面不提前揭示
        assertEquals(2, SafePrefixGate.releaseLength("- ☐ task one\n", 0))
    }

    @Test
    fun `数学块双美元起扣留前缀放行`() {
        // #472：$$ 前纯文字放行，$$ 起扣留（完结数学变换面）
        assertEquals(8, SafePrefixGate.releaseLength("formula \$\$x^2\$\$ next", 0))
    }

    @Test
    fun `单美元放行不扣`() {
        val snap = "price is 5$\nnext line"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("price is 5$\nnext line", d.delta)
        assertEquals(21, d.newReleased)
    }

    @Test
    fun `CRLF空行毕业兼容`() {
        assertEquals(17, rel("para one\r\n\r\nafter"))
    }

    @Test
    fun `预算中点截断续放一致`() {
        val s = "a".repeat(1000)
        assertEquals(400, rel(s, 0))
        assertEquals(800, rel(s, 400))
        assertEquals(1000, rel(s, 800))
    }

    @Test
    fun `行续段不作表头判定`() {
        // #472：竖线前纯文字 "c " 直出（管道后扣留）；管道到达前的纯文字
        // 段落→表头闪烁为预存在类（管道未到不可知），不因本修复扩大
        assertEquals(4, rel("abc | a |\n|---|\n| 1 |\n", 3))
    }

    @Test
    fun `行续段纯文字继续直出`() {
        assertEquals(7, rel("abc def", 3))
    }

    // ============ #472：行内闭合构造即时放行（InlineSpanSafety 接线） ============

    @Test
    fun `标记行行中截断后续行继续放行`() {
        // 首行闭合构造全放；次行 "d " 放出、悬空 * 扣住
        assertEquals(10, rel("a *b* c\nd *e"))
    }

    @Test
    fun `完整引用行含闭合构造放行`() {
        assertEquals(8, rel("> *a* b\n"))
    }

    @Test
    fun `完整引用行未闭合构造帽住`() {
        // '>' 内容前缀起扫：引用标记+空格+a+空格放出，悬空 * 扣住
        assertEquals(4, rel("> a *unclosed\n"))
    }

    // ===== #441 markdown 稳态粒度：表格正文跨批逐行 / * 无序列表项逐行 =====

    @Test
    fun `表格正文行跨批仍逐行放行`() {
        // 场景：表头+分隔批已放（already= 表头+分隔长度），快照续有两条正文行
        val header = "| 名称 | 数量 |\n| --- | --- |\n"
        val body = "| 苹果 | 3 |\n| 香蕉 | 5 |\n"
        val already = header.length
        // 期望：放行第一条完整正文行（第二条同批预算内也放——断言 >= 首行）
        val got = rel(header + body, already)
        assertTrue("应至少放行第一条正文行, got=" + got, got >= already + "| 苹果 | 3 |\n".length)
    }

    @Test
    fun `表格正文行单行逐放不半行`() {
        val header = "| a | b |\n| --- | --- |\n"
        val already = header.length
        // 只有半行正文（无换行）：不放
        assertEquals(already, rel(header + "| 半行", already))
    }

    @Test
    fun `星号无序列表项完整行放行`() {
        // '* ' 后跟内容 = 无序列表项（列表语义行级定案）
        val s = "* 第一项\n* 第二项\n"
        assertEquals(s.length, rel(s))
    }

    @Test
    fun `星号列表未完行流式放行`() {
        // #472：'* ' 标记星非侧翼=字面定案，未完行按行内安全扫描直出——
        // 与 '- item' 纯文字路径粒度对齐（原整行扣到行完整）
        assertEquals(6, rel("* 未完成项"))
    }

    @Test
    fun `星号强调跨行闭合放行`() {
        // #472：软换行同段——* 开、more* 闭，配对即放行（原扣到空行毕业）
        assertEquals(15, rel("*bold 开头\nmore*\n"))
    }

    @Test
    fun `横杠与有序列表项回归保持逐行`() {
        assertEquals("- item a\n".length, rel("- item a\n"))
        assertEquals("1. 第一\n".length, rel("1. 第一\n"))
    }

    // ============ #438①（2026-09-27）：单次放行上限（含空行毕业段） ============

    @Test
    fun `maxReleaseChars 截断空行毕业大段`() {
        // 三段空行毕业的定案内容（每段 60+ 字）——原第一级不受批预算约束整段放行；
        // maxReleaseChars=100 时只放 100（截断点在行中——released 坐标续放安全）。
        val seg = "这是一段足够长的定案文字用来验证单次放行上限对空行毕业段的截断行为覆盖到索引位置。"
        val snap = (seg + "\n\n").repeat(5) + seg
        assertEquals(100, SafePrefixGate.releaseLength(snap, 0, maxReleaseChars = 100))
        // 续放：从 100 继续到下一个 100
        assertEquals(200, SafePrefixGate.releaseLength(snap, 100, maxReleaseChars = 100))
        // 尾批放满
        val full = SafePrefixGate.releaseLength(snap, 0)
        assertEquals(full, SafePrefixGate.releaseLength(snap, 200, maxReleaseChars = 100))
        // 默认参数 = 原行为（整段毕业）
        assertTrue(full > 200)
    }

    @Test
    fun `maxReleaseChars 不影响扣留语义`() {
        // 未闭合 fence：预算再大也零放行（扣留优先于上限）
        val snap = "文字\n\n" + "```" + "kotlin\nval a = 1"
        val r0 = SafePrefixGate.releaseLength(snap, 0)
        assertEquals(r0, SafePrefixGate.releaseLength(snap, 0, maxReleaseChars = 50))
    }
}
