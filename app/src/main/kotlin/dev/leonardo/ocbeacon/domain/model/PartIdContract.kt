package dev.leonardo.ocbeacon.domain.model

/**
 * 流式 part 派生 id 契约——生产/消费单一权威（#234 战役二收编）。
 *
 * V2 SSE/REST 契约不带 part id（text/reasoning 以 ordinal 定位，tool 以
 * call_id）——客户端为流式 part 派生稳定 id：`<msg>_<kind>_ord_<ordinal>`。
 * id 含 kind（#109 碰撞修复：服务器 ordinal 按类型独立计数，同消息
 * reasoning[0] 与 text[0] 并存，id 不含 type 会碰撞）。
 *
 * 收编前知情者：V2SseMapper.derivePartId（生产侧）、V2Mappers/SseClientV2
 * （经其委托）、MessageEventHandler.isNewPartId + delta kind 推断（消费侧）、
 * MessageDao SQL 清扫（一次性历史迁移，LIKE 字面量保留）。修改格式必须
 * 同步评估 Room 历史数据兼容（#109/#223/#228/#229/#230 的修复都建立在本契约上）。
 */
object PartIdContract {

    const val TEXT_MARKER = "_text_ord_"
    const val REASONING_MARKER = "_reasoning_ord_"

    /** 派生流式 part 稳定 id（kind = "text" | "reasoning"，与服务器事件域对应）。 */
    fun derive(messageId: String, kind: String, ordinal: Long): String =
        "${messageId}_${kind}_ord_${ordinal}"

    /** 是否为本契约派生 id（Room 旧数据 id=""、legacy `msg_ord_N` 均非）。 */
    fun isDerived(id: String): Boolean =
        id.contains(TEXT_MARKER) || id.contains(REASONING_MARKER)

    /** 未注册 part 到达 delta 时的 kind 推断（#230）。返回 "reasoning" | "text"。 */
    fun kindOf(id: String): String =
        if (id.contains(REASONING_MARKER)) "reasoning" else "text"

    //（#507 swapStableKey 已随 #509 方案B 退役（2026-10-03 用户裁决）：DSH 权威
    // part id 改用流式宿主前缀派生后，part id 自首帧起跨毕业永久稳定，换装稳定
    // 键在 DSH/V2/V1 全协议上恒等；残余防御位（无 turn/step 畸形事件回落 seq
    // 前缀）经裁决放弃——历史档案见 docs/journal/2026-10-01-442 §31。）
}
