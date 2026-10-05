package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Test

/**
 * 归一化流式前缀单调性回归（2026-09-30 坍缩重建根修定罪锚）。
 *
 * 真机三案同源（13:55 divergeAt=396 / 14:13 / 14:40 divergeAt=163）：
 * ensureBlankLineBeforeGfmTables 的 run 累加器以 run.isNotEmpty() 作
 * 「已消费行」哨兵——闭合围栏后的空行作为 run 首行时（run 追加空串后仍为
 * ""），下一行 append 跳过分隔 \n → 空行被静默吞噬。流式中该吞噬在文本
 * 首次出现 |（表格行迟到）时经 | 快速路径退出才首次发生 → 已放行前缀中段
 * 非前缀改写 → pilot nonPrefix → RESETKEY 重建 → 卡片坍缩 + 限速重铺
 * （用户主诉「整个回答坍缩并重建」）。transformMathFallback 同款哨兵。
 * 本测试用真实 wire 语料全前缀扫描钉死两级修复。
 */
class NormalizeDivergenceProbeTest {

    private val fence = String(intArrayOf(96, 96, 96), 0, 3) // 三连反引号

    private val acc = buildString {
        append("**多层级嵌套无序列表：**\n")
        append("- 水果\n  - 苹果\n    - 红富士\n    - 嘎啦\n  - 香蕉\n")
        append("- 蔬菜\n  - 叶菜类\n  - 根茎类\n\n")
        append("**列表中包含代码：**\n- Python 示例：\n")
        append("  ").append(fence).append("python\n  def hello():\n      print(\"Hello, World!\")\n  ").append(fence).append("\n\n")
        append("**列表中包含引用：**\n- 注意事项\n  > 缩进必须对齐\n  > 层级最多建议三层\n\n")
        append("**列表中包含表格：**\n- 项目状态\n  | 项目 | 状态 |\n  |------|------|\n")
        append("  | 设计 | 完成 |\n  | 开发 | 进行中 |")
    }

    private fun esc(s: String) = s.replace("\n", "\\n")

    private fun firstDiverge(a: String, b: String): Int {
        var i = 0
        val lim = minOf(a.length, b.length)
        while (i < lim && a[i] == b[i]) i++
        return i
    }

    @Test
    fun probe() {
        println("accLen=" + acc.length)
        var prev: String? = null
        for (k in 1..acc.length) {
            val n = normalizeForStreaming(acc.take(k))
            val p = prev
            if (p != null && !n.startsWith(p)) {
                val d = firstDiverge(p, n)
                println("DIVERGE at k=$k prevK=" + (k - 1) + " divergeAt=$d")
                println("  prevCtx=[" + esc(p.substring(d, (d + 26).coerceAtMost(p.length))) + "]")
                println("  newCtx =[" + esc(n.substring(d, (d + 26).coerceAtMost(n.length))) + "]")
            }
            prev = n
        }
        val n209 = normalizeForStreaming(acc.take(209))
        val n245 = normalizeForStreaming(acc.take(245))
        println("n209 len=" + n209.length + " n245 len=" + n245.length + " startsWith=" + n245.startsWith(n209))
        // 分级定位：k=231 vs 232 各级变换输出 diff
        val a = acc.take(231)
        val b = acc.take(232)
        val stages = listOf<Pair<String, (String) -> String>>(
            "crlf" to { s -> if (s.indexOf('\r') < 0) s else s.replace("\r\n", "\n").replace("\r", "\n") },
            "tableBlank" to { s -> ensureBlankLineBeforeGfmTables(s) },
            "math" to { s -> transformMathFallback(s) },
            "task" to { s -> normalizeTaskListMarkers(s) },
            "split" to { s -> splitOversizedParagraphsByPosition(s) },
        )
        var pa = a
        var pb = b
        for ((name, fn) in stages) {
            val na = fn(pa)
            val nb = fn(pb)
            val eq = nb.startsWith(na)
            println("STAGE $name: prevLen=" + na.length + " newLen=" + nb.length + " prefixStable=" + eq)
            if (!eq) {
                var i = 0
                val lim = minOf(na.length, nb.length)
                while (i < lim && na[i] == nb[i]) i++
                println("  stageDivergeAt=" + i + " prevCtx=[" + esc(na.substring(i, (i + 30).coerceAtMost(na.length))) + "] newCtx=[" + esc(nb.substring(i, (i + 30).coerceAtMost(nb.length))) + "]")
            }
            pa = na
            pb = nb
        }
    }
}
