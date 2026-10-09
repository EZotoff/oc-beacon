package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import dev.snipme.highlights.model.SyntaxThemes
import org.junit.Test

/**
 * #488 批2 一次性测量（CAP 定参）：buildSafeHighlightedAnnotatedString 在各长度
 * 桶上的耗时——JVM 数字 ×~3 保守折算 Android 主线程预算（≤4ms/帧）选同步阈值。
 * 非时序断言（防抖动），只打印分布；定参后本文件保留作 CAP 复核探针。
 */
class HighlightedCodeSyncCapProbeTest {

    private fun kotlinCorpus(lines: Int): String = (1..lines).joinToString("\n") {
        "fun handler$it(x: Int): String = compute$it(x) + suffix-\${x * $it} // line $it tail comment padding"
    }

    @Test
    fun `长度桶耗时分布`() {
        val theme = SyntaxThemes.default(darkMode = false)
        // 预热（引擎首次构建有一次性成本）
        repeat(3) { buildSafeHighlightedAnnotatedString(kotlinCorpus(50), "kotlin", theme) }
        for (lines in listOf(5, 20, 50, 120, 300)) {
            val code = kotlinCorpus(lines)
            val n = code.length
            val samples = LongArray(8) {
                val t0 = System.nanoTime()
                val out = buildSafeHighlightedAnnotatedString(code, "kotlin", theme)
                check(out.spanStyles.isNotEmpty())
                System.nanoTime() - t0
            }
            samples.sort()
            println("PROBE len=$n lines=$lines min=${samples[0] / 1000}us p50=${samples[4] / 1000}us max=${samples[7] / 1000}us")
        }
    }
}
