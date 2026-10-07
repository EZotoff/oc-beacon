package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #527：extractToolInput snake_case 键别名（DSH wire file_path/old_string/new_string
 * → 卡片层 filePath/oldString/newString）。journal 实证（session-7300b09e）DSH
 * read/write/edit 实参恒 snake_case，失配时标题/路径/打开按钮全灭。
 */
class ToolCardInputAlias527Test {

    private fun toolWithInput(input: Map<String, kotlinx.serialization.json.JsonElement>): Part.Tool =
        Part.Tool(
            id = "call_1",
            sessionId = "s",
            messageId = "dsh-call-call_1",
            callId = "call_1",
            tool = "read",
            state = ToolState.Pending(
                input = input,
                raw = null,
                time = ToolState.Pending.Time(start = 1L),
            ),
        )

    @Test
    fun `snake_case keys aliased to camelCase`() {
        val input = extractToolInput(
            toolWithInput(
                mapOf(
                    "file_path" to JsonPrimitive("/e2e/essay.txt"),
                    "old_string" to JsonPrimitive("a"),
                    "new_string" to JsonPrimitive("b"),
                ),
            ),
        )
        assertEquals("/e2e/essay.txt", input["filePath"]?.jsonPrimitive?.contentOrNull)
        assertEquals("a", input["oldString"]?.jsonPrimitive?.contentOrNull)
        assertEquals("b", input["newString"]?.jsonPrimitive?.contentOrNull)
        // 原键保留（不破坏可能读原键的调用方）
        assertEquals("/e2e/essay.txt", input["file_path"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `camelCase originals not overwritten by alias`() {
        val input = extractToolInput(
            toolWithInput(
                mapOf(
                    "filePath" to JsonPrimitive("/v1/file.txt"),
                    "file_path" to JsonPrimitive("/dsh/file.txt"),
                ),
            ),
        )
        assertEquals("/v1/file.txt", input["filePath"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(extractToolInput(toolWithInput(emptyMap())).isEmpty())
    }
}
