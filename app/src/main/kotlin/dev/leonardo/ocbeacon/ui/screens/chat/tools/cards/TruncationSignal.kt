package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * #539 ⑤A：工具输出截断提示的信号源（纯函数，单测覆盖）。
 *
 * 三层优先级（2026-10-10 现网 2.0.26 实测契约）：
 * 1. 结构化 outputPaths（2.x core tool-output-store 超限落盘路径；现网尚未
 *    携带，未来字段）——在场即截断且路径已知；
 * 2. 结构化旗标 state.metadata.truncated==true（现网 shell 工具在席——
 *    大输出尾部截头后 metadata 携带 truncated:true；路径需从正文提取）；
 * 3. 文本约定回退：旧前缀 "…output truncated…\n\nFull output saved to: <path>"
 *    （dev HEAD shell.ts 文档拼写）与现网尾行 "… of N; full output saved to <path>"。
 *
 * 现网实证（2026-10-10 seq 12000 行）：正文既无 "output truncated" 字样也不以
 * "..." 开头——只走第 3 层旧拼写时提示条从不触发，截断对用户不可见。
 * spill 通知在席时整体抑制（#536 互斥：DSH 横幅已覆盖截断语义）。
 */
internal object TruncationSignal {

    /** 旧文档前缀拼写：output truncated … saved to|Full output saved to: <path> */
    private val LEGACY_PREFIX_PATH = Regex("""output truncated[\s\S]*?(?:saved to|Full output saved to:)\s*([^\s\]\)]+)""")

    /** 现网 2.0.26 尾行拼写：… of 12000; full output saved to <path>] ——整段尾注
     * 包在方括号里（2026-10-10 真机实证 %5D 尾巴致 viewer 404），捕获组排除右括号。 */
    private val LIVE_TAIL_PATH = Regex("""full output saved to[:\s]+([^\s\]\)]+)""")

    /** 截断事实判定：outputPaths 在席 / metadata.truncated==true / 文本约定。 */
    fun isTruncated(
        outputPaths: List<String>?,
        metadata: Map<String, JsonElement>?,
        displayText: String,
        spillPresent: Boolean,
    ): Boolean {
        if (spillPresent) return false
        if (!outputPaths.isNullOrEmpty()) return true
        if (metadata?.get("truncated")?.jsonPrimitive?.booleanOrNull == true) return true
        return displayText.contains("output truncated") || displayText.startsWith("...")
    }

    /** 路径提取：outputPaths 优先，其次两种文本拼写（先旧前缀后现网尾行）。 */
    fun path(outputPaths: List<String>?, displayText: String): String? {
        outputPaths?.firstOrNull()?.let { return it }
        LEGACY_PREFIX_PATH.find(displayText)?.groupValues?.get(1)?.let { return it }
        return LIVE_TAIL_PATH.find(displayText)?.groupValues?.get(1)
    }
}
