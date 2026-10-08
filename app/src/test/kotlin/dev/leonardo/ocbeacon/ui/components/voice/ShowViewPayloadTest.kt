package dev.leonardo.ocbeacon.ui.components.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShowViewPayloadTest {
    private fun payload(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun `entryText prefers label text name title then raw string`() {
        assertEquals("L", entryText(payload("""{"a":{"label":"L"}}""")["a"]))
        assertEquals("T", entryText(payload("""{"a":{"text":"T"}}""")["a"]))
        assertEquals("N", entryText(payload("""{"a":{"name":"N"}}""")["a"]))
        assertEquals("Ti", entryText(payload("""{"a":{"title":"Ti"}}""")["a"]))
        assertEquals("raw", entryText(payload("""{"a":"raw"}""")["a"]))
        assertEquals("", entryText(null))
        assertEquals("", entryText(JsonNull))
        // non-string label fields are ignored, falls back to nothing
        assertEquals("", entryText(payload("""{"a":{"label":42}}""")["a"]))
    }

    @Test fun `entryStatus extracts string status only`() {
        assertEquals("running", entryStatus(payload("""{"a":{"status":"running"}}""")["a"]))
        assertNull(entryStatus(payload("""{"a":{"status":1}}""")["a"]))
        assertNull(entryStatus(payload("""{"a":"plain"}""")["a"]))
    }

    @Test fun `tableColumns unions keys in first-appearance order`() {
        val rows = payload("""{"r":[{"name":"a","state":"run"},{"state":"ok","extra":1}]}""")["r"]
        @Suppress("UNCHECKED_CAST")
        val list = rows as List<kotlinx.serialization.json.JsonElement>
        assertEquals(listOf("name", "state", "extra"), tableColumns(list))
        assertEquals(emptyList<String>(), tableColumns(emptyList()))
    }

    @Test fun `cellText reads sub-object or falls back to entry text`() {
        val row = payload("""{"row":{"task":"Ship","state":"run"}}""")["row"]
        assertEquals("Ship", cellText(row, "task"))
        assertEquals("run", cellText(row, "state"))
        assertEquals("", cellText(row, "missing"))
        assertEquals("plain", cellText(payload("""{"row":"plain"}""")["row"], "col"))
    }

    @Test fun `progress clamps done and percent`() {
        val p = payload("""{"done":9,"percent":150}""")
        assertEquals(0, progressDone(p, 0))
        val p2 = payload("""{"steps":["a","b"],"done":5}""")
        assertEquals(2, progressDone(p2, 2))
        assertEquals(100, progressPercent(p))
        assertNull(progressPercent(payload("""{}""")))
    }

    @Test fun `comparison sides read label and items with fallback`() {
        val p = payload("""{"left":{"label":"Mine","items":["a","b"]}}""")
        val left = comparisonSide(p, "left", "left")
        assertEquals("Mine", left.label)
        assertEquals(listOf("a", "b"), left.items)
        val right = comparisonSide(p, "right", "right")
        assertEquals("right", right.label)
        assertEquals(emptyList<String>(), right.items)
    }

    @Test fun `diff lines read kind and text with raw string fallback`() {
        val p = payload("""{"lines":[{"kind":"add","text":"+new"},{"kind":"del","text":"-old"},"plain"]}""")
        val lines = diffLines(p)
        assertEquals("add", lines[0].kind)
        assertEquals("+new", lines[0].text)
        assertEquals("del", lines[1].kind)
        assertEquals(DiffLine(null, "plain"), lines[2])
    }
}
