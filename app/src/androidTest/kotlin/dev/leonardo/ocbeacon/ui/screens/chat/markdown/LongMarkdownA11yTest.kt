package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #477 定罪实验：长 markdown（>2048 全量渲染）的 a11y 语义树是否暴露文本。
 * 对照组：短 markdown（<2048）同路径。
 * 注意：不用 waitForIdle（app 层周期 ticker 令 idle 同步挂死），全部走轮询。
 * 真机跑法：adb shell am instrument -w -e class dev.leonardo.ocbeacon.ui.screens.chat.markdown.LongMarkdownA11yTest dev.leonardo.ocbeacon.dev.test/dev.leonardo.ocbeacon.HiltTestRunner
 */
@RunWith(AndroidJUnit4::class)
class LongMarkdownA11yTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun para(i: Int): String =
        "Paragraph $i about the Yangtze River geography: it stretches about 6300 kilometers " +
            "from the Tuotuo headwaters on the Qinghai-Tibet Plateau through the Three Gorges " +
            "to the East China Sea near Shanghai, the busiest container port. "

    private fun build(n: Int): String = (1..n).joinToString("\n\n") { para(it) }

    private fun awaitText(needle: String) {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText(needle, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun shortMarkdown_exposesText() {
        val short = build(3) // ~630 chars
        rule.setContent {
            MarkdownContent(markdown = short, textColor = Color.Black, isUser = false, asyncParse = false)
        }
        awaitText("Paragraph 1 about the Yangtze")
    }

    @Test
    fun longMarkdown_async_exposesText() {
        val long = build(18) // ~3800 chars > 2048 → async 路径
        rule.setContent {
            MarkdownContent(markdown = long, textColor = Color.Black, isUser = false, asyncParse = true)
        }
        awaitText("Paragraph 12 about the Yangtze")
    }

    @Test
    fun longMarkdown_preParsed_exposesText() {
        val long = build(18)
        val parsed = com.mikepenz.markdown.model.parseMarkdown(long)
        rule.setContent {
            MarkdownContent(markdown = long, textColor = Color.Black, isUser = false, preParsedState = parsed)
        }
        awaitText("Paragraph 9 about the Yangtze")
    }
}
