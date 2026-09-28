package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #472 行内闭合构造安全扫描器判定表。
 *
 * 语义：已闭合 emphasis/strong/strikethrough/code-span + 其后纯文字 → 安全增量放行；
 * 未闭合开标记 / 硬停字符（[ ] ! | # > \ $$ ☐☑✅）/ 行尾未知侧翼 → 扣留点。
 *
 * 判定镜像 markdown-jvm 0.7.9（DelimiterParser.canOpenClose + EmphasisLikeParser
 * .balanceDelimiters + BacktickParser 顺序等长匹配），保守方向 = 宁多扣不少放。
 */
class InlineSpanSafetyTest {

    private fun cut(s: String, from: Int = 0): Int = InlineSpanSafety.safeCut(s, from)

    // ===== emphasis 家族：闭合放行 / 未闭合扣留 =====

    @Test
    fun `斜体闭合即放行至快照尾`() {
        assertEquals("前文 *核心* 后续文字".length, cut("前文 *核心* 后续文字"))
    }

    @Test
    fun `未闭合斜体扣留在开标记前`() {
        assertEquals(3, cut("前文 *未闭合"))
    }

    @Test
    fun `行尾星号EOF侧翼未知保守扣留`() {
        assertEquals(4, cut("foo *"))
    }

    @Test
    fun `粗体嵌套全闭合放行`() {
        assertEquals("**a *b* c** tail".length, cut("**a *b* c** tail"))
    }

    @Test
    fun `跨行闭合放行`() {
        assertEquals("*foo\nbar* tail".length, cut("*foo\nbar* tail"))
    }

    @Test
    fun `中文邻接斜体闭合放行`() {
        assertEquals("的*核心*是".length, cut("的*核心*是"))
    }

    @Test
    fun `星号词内成对放行`() {
        assertEquals("2*3*4 好的".length, cut("2*3*4 好的"))
    }

    @Test
    fun `下划线空格侧翼闭合放行`() {
        assertEquals("normal _em_ tail".length, cut("normal _em_ tail"))
    }

    @Test
    fun `下划线词内不成对直通`() {
        // _ 不可词内：snake_case 的下划线 canOpen/canClose 双 false = 字面定案
        assertEquals("snake_case_name here".length, cut("snake_case_name here"))
    }

    @Test
    fun `删除线闭合放行`() {
        assertEquals("删除 ~~this~~ 尾巴".length, cut("删除 ~~this~~ 尾巴"))
    }

    @Test
    fun `未匹配纯闭侧星号直通`() {
        assertEquals("a* b".length, cut("a* b"))
    }

    @Test
    fun `两段构造间文字流式恢复`() {
        // 第一段闭合即放；第二段未闭合 → 扣在第二个开标记
        assertEquals(9, cut("*a* 中间文字 *b"))
    }

    // ===== rule of 3 / 混合长度（镜像库 balanceDelimiters） =====

    @Test
    fun `ruleOf3交叉保守扣留在最早未闭合`() {
        // *a 的 * 后被 **b* 的闭侧跳过（rule 3）配对 **，首个 * 永远悬空 → 扣留@2
        assertEquals(2, cut("x *a **b* c"))
    }

    @Test
    fun `混合长度配对后词内星悬空扣留`() {
        // **a*b** 的 ** 配对 **，但词内 * 悬空于已配对 span 内部——库 balance
        // 不弹栈中间 delimiter，未来闭符可回配内部星 → 跨切点修正环把外层
        // 开标记一并扣（2），防 span 行中截断的字面→重排闪烁
        assertEquals(2, cut("x **a*b** y"))
    }

    // ===== code span（顺序等长匹配，镜像 BacktickParser） =====

    @Test
    fun `反引号闭合放行且内部硬停字符免疫`() {
        assertEquals("看 `a|b` 尾巴".length, cut("看 `a|b` 尾巴"))
    }

    @Test
    fun `反引号未闭合扣留`() {
        assertEquals(2, cut("看 `abc"))
    }

    @Test
    fun `反引号等长不匹配扣留`() {
        // 双反引号开、只有单反引号可闭 → 扣在双反引号处
        assertEquals(2, cut("a `` b ` c"))
    }

    @Test
    fun `反引号等长后续闭合放行`() {
        assertEquals("a ``b`` c".length, cut("a ``b`` c"))
    }

    // ===== 硬停字符 =====

    @Test
    fun `反斜杠硬停`() {
        assertEquals(3, cut("文字 \\*斜体\\* 继续"))
    }

    @Test
    fun `方括号硬停`() {
        assertEquals(3, cut("文字 [链接](x)"))
    }

    @Test
    fun `感叹号硬停`() {
        assertEquals(3, cut("图片 ![alt](u)"))
    }

    @Test
    fun `双美元硬停`() {
        assertEquals(3, cut("式子 \$\$x\$\$ 后"))
    }

    @Test
    fun `单美元文本直通`() {
        assertEquals("价格 5$ 到".length, cut("价格 5$ 到"))
    }

    @Test
    fun `行尾单美元保守扣留`() {
        assertEquals(3, cut("价格 $"))
    }

    @Test
    fun `竖线硬停保护表格回溯`() {
        assertEquals(2, cut("a | b"))
    }

    @Test
    fun `井号硬停`() {
        assertEquals(1, cut("C# 和 .NET"))
    }

    @Test
    fun `右尖括号硬停`() {
        assertEquals(2, cut("a > b"))
    }

    // ===== 列表标记前缀（惰性通过） =====

    @Test
    fun `列表标记星惰性通过未闭合扣内容`() {
        // '* ' 的 * 后随空白 = 非侧翼 = 字面定案；行内第二个 * 未闭合 → 扣@7
        assertEquals(7, cut("* item *unclosed more"))
    }

    @Test
    fun `from非零续扫已闭合段`() {
        // 已放行前缀后接闭合粗体 + 尾巴 → 续扫直通
        val s = "already **bold** tail"
        assertEquals(s.length, cut(s, 8))
    }
}
