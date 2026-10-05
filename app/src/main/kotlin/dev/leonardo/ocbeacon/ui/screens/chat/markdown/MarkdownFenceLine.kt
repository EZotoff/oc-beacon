package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #471③ 统一围栏行判定（归一化三变换与 SafePrefixGate 共用语义）。
 *
 * 三变换（[normalizeTaskListMarkers]/[transformMathFallback]/
 * [ensureBlankLineBeforeGfmTables]）原各自的围栏跟踪有两个与 gate
 * （isFenceOpen/isFenceClose）分歧的宽松点：
 *  1. 栏内闭合不查 info——栏内「```xxx」被误判闭合（CommonMark 闭栏
 *     行不允许 info），后续内容被当栏外跑变换；
 *  2. 反引号栏开栏 info 不查反引号——「```a`b```」被 math/task 当开栏、
 *     gate 当普通行，栏状态翻转。
 * 分歧在流式下 = gate 已放行的「栏内」前缀被变换重写（非前缀破口，性质
 * 测试 random 轮实证）；在完结渲染下 = 代码块内容被误改。统一为 gate
 * 语义（CommonMark 严格版）。
 */
internal object MarkdownFenceLine {

    private val FenceRegex = Regex("^ {0,3}(`{3,}|~{3,})")

    /**
     * 开栏行（≤3 空白缩进 + ≥3 同字符 `/~；反引号栏 info 不得含反引号）
     * → (栏字符, 栏长)；非开栏行 → null。
     */
    fun open(line: String): Pair<Char, Int>? {
        val m = FenceRegex.find(line) ?: return null
        val marker = m.groupValues[1]
        if (marker[0] == '`' && line.indexOf('`', m.range.last + 1) >= 0) return null
        return marker[0] to marker.length
    }

    /**
     * [line] 是否为 [marker]/[minLen] 栏的闭合行——同字符、足够长、
     * 且 marker 之后仅空白（CommonMark 闭栏行不允许 info string）。
     */
    fun closes(line: String, marker: Char, minLen: Int): Boolean {
        val m = FenceRegex.find(line) ?: return false
        val g = m.groupValues[1]
        return g[0] == marker && g.length >= minLen &&
            line.substring(m.range.last + 1).isBlank()
    }
}
