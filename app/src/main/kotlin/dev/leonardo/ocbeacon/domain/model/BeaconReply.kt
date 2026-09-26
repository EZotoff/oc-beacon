package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Locale

/**
 * Portable Supervisor Seam 4「beacon reply ingress」回复信封（Amendment 2026-09-25）。
 *
 * 一条回复 = 一条顶层 user 消息，文本为单个 JSON 对象：
 * `{ "v":1, "clientMessageID":"<uuidv7>", "kind":"text|choice|speech",
 *    "text":"…", "index":N, "contextTag":"pres_…", "explicitItemID":"att_…" }`
 *
 * - `clientMessageID`：客户端生成的 uuidv7，supervisor 据此去重（重传必须同 ID）；
 * - `explicitItemID`：卡片命中的队列项 ID——相关联解析第一优先级；
 * - `contextTag` / spoken `speech` 本期不产出，字段保留以符合 schema。
 */
data class BeaconReply(
    val kind: Kind,
    /** text / speech 的文本或转写；choice 时为 null（用 [index]）。 */
    val text: String? = null,
    /** choice 专用：卡片选项 0-based 下标。 */
    val index: Int? = null,
    val contextTag: String? = null,
    val explicitItemID: String? = null,
) {
    enum class Kind { TEXT, CHOICE, SPEECH }

    init {
        require(kind == Kind.CHOICE || !text.isNullOrBlank()) { "text required for kind=$kind" }
        require(kind != Kind.CHOICE || index != null) { "index required for kind=choice" }
    }

    /** 序列化为信封 JSON（整条消息文本）。null 字段省略；`v` 恒为 1。 */
    fun envelopeJson(clientMessageID: String): String = buildJsonObject {
        put("v", JsonPrimitive(1))
        put("clientMessageID", JsonPrimitive(clientMessageID))
        put("kind", JsonPrimitive(kind.name.lowercase(Locale.ROOT)))
        text?.let { put("text", JsonPrimitive(it)) }
        index?.let { put("index", JsonPrimitive(it)) }
        contextTag?.let { put("contextTag", JsonPrimitive(it)) }
        explicitItemID?.let { put("explicitItemID", JsonPrimitive(it)) }
    }.toString()

    companion object {
        /** 类型化文本回复（卡片命名队列项 → explicitItemID）。 */
        fun text(text: String, explicitItemID: String?): BeaconReply =
            BeaconReply(kind = Kind.TEXT, text = text.trim(), explicitItemID = explicitItemID)
    }
}
