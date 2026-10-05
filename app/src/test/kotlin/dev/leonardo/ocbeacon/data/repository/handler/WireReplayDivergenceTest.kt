package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 坍缩重建根修 RED 锚（2026-09-30 14:40 真机 wire 抓取，r0lMbSMFbd 流）：
 * 11 个真实 session.text.delta 经 48ms 批切分喂入 handler——真机上同一序列
 * 使 pilot 在 divergeAt=163 观察到非前缀（"  ```

**列表中包含引用：**"
 * 的空行在组装侧消失）。若组装文本 == wire 逐字拼接（含空行），则数据层
 * 组装无罪、分歧在显示装配层；若不等，本测试即 RED 定罪。
 */
private fun delta(msgId: String, d: String) =
    SseEvent.MessagePartDelta(sessionId = "s1", messageId = msgId, partId = msgId + "_text_ord_0", field = "text", delta = d)

class WireReplayDivergenceTest {

    private val handler = MessageEventHandler()

    private fun feed(batches: List<List<SseEvent.MessagePartDelta>>) {
        for (b in batches) {
            for (e in b) handler.handleMessagePartDelta(e)
            handler.forceFlushDeltas()
        }
    }

    private fun partText(): String {
        val parts = handler.parts.value[MSG].orEmpty()
        return (parts.firstOrNull { it.id == PID } as? Part.Text)?.text ?: ""
    }

    @Test
    fun `wire delta batches assemble byte-identical to wire truth 2026-09-30 14-40 r0 stream`() {
        val truth = TRUTH
        feed(BATCHES)
        assertEquals(truth.length, partText().length)
        assertEquals(truth, partText())
    }

    companion object {
        private const val MSG = "msg_0f10b403a001eaoDr0lMbSMFbd"
        private const val PID = "msg_0f10b403a001eaoDr0lMbSMFbd_text_ord_0"
        private val BATCHES = listOf(
            listOf(delta(MSG, "**多层级嵌套无序列表：**\n- 水果\n  - ")),
            listOf(delta(MSG, "苹果\n    - 红富士\n    - 嘎")),
            listOf(delta(MSG, "啦\n  - 香蕉\n- 蔬菜\n  - 叶菜类\n  - 根茎类\n\n")),
            listOf(delta(MSG, "**列表中包含代码：**\n- Python 示例：\n")),
            listOf(delta(MSG, "  ```python\n  def hello():\n      print(\"Hello, World!\")\n  ```\n\n**列表中包含")),
            listOf(delta(MSG, "引用：**\n- 注意事项\n  > 缩进")),
            listOf(delta(MSG, "必须对齐\n  > 层级最多建议三层\n\n**列表中包含表格：")),
            listOf(delta(MSG, "**\n- 项目状态\n  | 项目 | 状态 |\n  |")),
            listOf(delta(MSG, "------|------|\n")),
            listOf(delta(MSG, "  | 设计 | 完成 |\n  | 开发 | 进行中 |")),
        )
        private val TRUTH = "**多层级嵌套无序列表：**\n- 水果\n  - 苹果\n    - 红富士\n    - 嘎啦\n  - 香蕉\n- 蔬菜\n  - 叶菜类\n  - 根茎类\n\n**列表中包含代码：**\n- Python 示例：\n  ```python\n  def hello():\n      print(\"Hello, World!\")\n  ```\n\n**列表中包含引用：**\n- 注意事项\n  > 缩进必须对齐\n  > 层级最多建议三层\n\n**列表中包含表格：**\n- 项目状态\n  | 项目 | 状态 |\n  |------|------|\n  | 设计 | 完成 |\n  | 开发 | 进行中 |"
    }
}
