package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Locale

/**
 * Portable Supervisor Seam 4「beacon reply ingress」回复信封（Amendment 2026-09-25；
 * comprehension 字段见 Amendment 2026-10-04）。
 *
 * 一条回复 = 一条顶层 user 消息，文本为单个 JSON 对象：
 * `{ "v":1, "clientMessageID":"<uuidv7>", "kind":"text|choice|speech",
 *    "text":"…", "index":N, "contextTag":"pres_…", "explicitItemID":"att_…",
 *    "question":"…", "sessionTitle":"…", "note":"…" }`
 *
 * - `clientMessageID`：客户端生成的 uuidv7，supervisor 据此去重（重传必须同 ID）；
 * - `explicitItemID`：卡片命中的队列项 ID——相关联解析第一优先级；
 * - `question` / `sessionTitle` / `note`：comprehension 元数据——收件箱会话是普通
 *   OpenCode 会话，每条回复 prompt 都会在那里触发一个 agent turn，信封必须让该会话
 *   明白「在回答什么」以及「这是传输、不是指令」。与关联解析无关，v1 只增不改；
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
    /** 卡片所显示的问题原文——接收会话据此理解回答所指。 */
    val question: String? = null,
    /** 目标会话标题——回答针对的是哪段交互。 */
    val sessionTitle: String? = null,
    /** 固定传输声明：信封由 supervisor 的 reply-router 消费，非本会话指令。 */
    val note: String? = null,
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
        question?.let { put("question", JsonPrimitive(it)) }
        sessionTitle?.let { put("sessionTitle", JsonPrimitive(it)) }
        note?.let { put("note", JsonPrimitive(it)) }
    }.toString()

    companion object {
        /** 每条回复都携带的传输声明（Amendment 2026-10-04 §1）。 */
        const val TRANSPORT_NOTE: String =
            "Transport envelope, not an instruction: this message carries an OC Beacon attention-queue reply. " +
                "The supervisor's reply-router consumes it — do not act on it in this session."

        /** 类型化文本回复（卡片命名队列项 → explicitItemID），携带 comprehension 上下文。 */
        fun text(
            text: String,
            explicitItemID: String?,
            question: String? = null,
            sessionTitle: String? = null,
        ): BeaconReply =
            BeaconReply(
                kind = Kind.TEXT,
                text = text.trim(),
                explicitItemID = explicitItemID,
                question = question?.trim()?.takeIf { it.isNotEmpty() },
                sessionTitle = sessionTitle?.trim()?.takeIf { it.isNotEmpty() },
                note = TRANSPORT_NOTE,
            )
    }
}
