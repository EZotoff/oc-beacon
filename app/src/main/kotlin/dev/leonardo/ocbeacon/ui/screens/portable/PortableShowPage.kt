package dev.leonardo.ocbeacon.ui.screens.portable

import dev.leonardo.ocbeacon.data.api.voice.ShowFrame
import dev.leonardo.ocbeacon.data.api.voice.ShowView
import kotlinx.serialization.json.*

data class PortableShowPage(val frame: ShowFrame, val offset: Int, val pages: Int) {
    companion object {
        private const val PAGE_SIZE = 3

        fun of(frame: ShowFrame, page: Int): PortableShowPage {
            val key = when (frame.view) {
                ShowView.Known.CARD, ShowView.Known.LIST -> "items"
                ShowView.Known.CHOICE -> "options"
                ShowView.Known.TABLE -> "rows"
                ShowView.Known.PROGRESS -> "steps"
                ShowView.Known.DIFF -> "lines"
                else -> null
            }
            val entries = key?.let { frame.payload[it] as? JsonArray }
            val comparisons = listOf("left", "right").map { side ->
                ((frame.payload[side] as? JsonObject)?.get("items") as? JsonArray)?.size ?: 0
            }
            val size = entries?.size ?: if (frame.view == ShowView.Known.COMPARISON) comparisons.max() else 0
            val pages = ((size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
            val offset = page.coerceIn(0, pages - 1) * PAGE_SIZE
            val payload = frame.payload.toMutableMap()
            val columns = entries.orEmpty().filterIsInstance<JsonObject>().flatMap { it.keys }.distinct().take(3)
            if (key != null && entries != null) {
                payload[key] = JsonArray(entries.drop(offset).take(PAGE_SIZE).map { entry ->
                    if (frame.view == ShowView.Known.TABLE && entry is JsonObject)
                        JsonObject(entry.filterKeys { it in columns }) else entry
                })
            }
            if (frame.view == ShowView.Known.PROGRESS) {
                val done = (payload["done"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                payload["done"] = JsonPrimitive((done - offset).coerceIn(0, PAGE_SIZE))
            }
            if (frame.view == ShowView.Known.COMPARISON) {
                listOf("left", "right").forEach { side ->
                    val obj = payload[side] as? JsonObject ?: return@forEach
                    val items = obj["items"] as? JsonArray ?: return@forEach
                    payload[side] = JsonObject(obj + ("items" to JsonArray(items.drop(offset).take(PAGE_SIZE))))
                }
            }
            return PortableShowPage(frame.copy(title = frame.title.take(80), payload = bound(JsonObject(payload)) as JsonObject), offset, pages)
        }

        private fun bound(value: JsonElement): JsonElement = when (value) {
            is JsonPrimitive -> if (value.isString) JsonPrimitive(value.content.take(180)) else value
            is JsonArray -> JsonArray(value.take(PAGE_SIZE).map(::bound))
            is JsonObject -> JsonObject(value.entries.take(8).associate { it.key to bound(it.value) })
        }
    }
}
