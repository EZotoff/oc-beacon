package dev.leonardo.ocbeacon.ui.screens.portable

import dev.leonardo.ocbeacon.data.api.voice.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PortableShowPageTest {
    @Test fun `show paging is bounded and preserves original selection offset`() {
        val frame = ShowFrame(ShowView.Known.CHOICE, "Options", "ctx-7",
            buildJsonObject { put("options", JsonArray((0..7).map { JsonPrimitive("Option $it") })) })
        val page = PortableShowPage.of(frame, 1)
        assertEquals(3, page.offset)
        assertEquals(3, page.pages)
        assertEquals("Option 3", (page.frame.payload["options"] as JsonArray).first().jsonPrimitive.content)
        assertEquals(8, (frame.payload["options"] as JsonArray).size)
        assertEquals(2, (PortableShowPage.of(frame, 99).frame.payload["options"] as JsonArray).size)
    }

    @Test fun `text and comparison sides remain compact`() {
        val frame = ShowFrame(ShowView.Known.COMPARISON, "Compare", "ctx-8", buildJsonObject {
            listOf("left", "right").forEach { side -> put(side, buildJsonObject {
                put("label", side)
                put("items", JsonArray((0..6).map { JsonPrimitive("x".repeat(300)) }))
            }) }
        })
        val page = PortableShowPage.of(frame, 1)
        assertEquals(3, page.pages)
        val items = page.frame.payload["left"]!!.jsonObject["items"]!!.jsonArray
        assertEquals(3, items.size)
        assertEquals(180, items.first().jsonPrimitive.content.length)
    }
}
