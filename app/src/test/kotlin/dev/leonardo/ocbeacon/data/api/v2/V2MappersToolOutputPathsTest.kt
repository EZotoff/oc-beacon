package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * #539 ⑤A：V2 tool state outputPaths 映射（2.x core 超限落盘路径；
 * 现网 2.0.26 尚未携带——在场时映射、缺席时 null 走文本回退）。
 */
class V2MappersToolOutputPathsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun toolStateJson(stateBlock: String) = json.parseToJsonElement("""
        {"type":"assistant","id":"msg_t","time":{"created":1000},
         "agent":"build","model":{"id":"m","providerID":"p"},
         "content":[{"type":"tool","id":"call_1","name":"shell","state":$stateBlock}]}
    """).jsonObject

    @Test
    fun `maps outputPaths when present`() {
        val result = V2MessageMapper.toMessageWithParts(
            toolStateJson(
                """{"status":"completed","input":{"command":"seq 1 99999"},
                    "content":[{"type":"text","text":"…of 99999; full output saved to /srv/full.out"}],
                    "outputPaths":["/srv/full.out"]}""",
            ),
            "sess_1",
        )!!
        val state = (result.parts[0] as Part.Tool).state as ToolState.Completed
        assertEquals(listOf("/srv/full.out"), state.outputPaths)
    }

    @Test
    fun `outputPaths absent maps to null without breaking decode`() {
        val result = V2MessageMapper.toMessageWithParts(
            toolStateJson(
                """{"status":"completed","input":{"command":"echo hi"},
                    "content":[{"type":"text","text":"hi"}],
                    "metadata":{"status":"completed","truncated":false,"exit":0}}""",
            ),
            "sess_1",
        )!!
        val state = (result.parts[0] as Part.Tool).state as ToolState.Completed
        assertNull(state.outputPaths)
        // 现网截断旗标在 metadata（2.0.26 实测）：随 metadata 原样透传
        assertNotNull(state.metadata)
    }
}
