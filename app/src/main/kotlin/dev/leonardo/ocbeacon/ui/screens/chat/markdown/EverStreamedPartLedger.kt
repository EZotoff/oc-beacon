package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #486 根修（2026-10-09 用户裁决 A 案）：msgId 级「曾流式渲染」进程内账本。
 *
 * 缺口背景（仪器腿判死活实证，journal 2026-10-09 b2 批）：pilot 槽位被销毁后
 * （StepGroup 视口窗口化回收 / v1 message.updated 全量重发换 partId / 跳转回退）
 * 冷重建走 asyncTerminal 路径，首帧 State.Loading ≈0 高——已可见内容出现
 * 一帧空窗（换装闪烁族）。#472 时代的 pilotTerminalHold 补偿族已随 #509「pilot
 * 即终态」退役，幸存槽位不再有该问题；本账本把「幸存」语义延伸到进程级：
 * **曾以 pilot 渲染过的消息，其组合位销毁后的冷重入直接再入 pilot 分支**
 * （[MarkdownContent] 消费），配合 cold 单帧揭示消除 Loading 空窗帧。
 *
 * 键=msgId（对齐 2026-09-30 方案 a「msgId 级」；partId 变化的重建（v1 语义）
 * 同 msgId 命中）。LRU 上限防长跑进程无界增长；越界逐出后该消息降级回
 * asyncTerminal 现状行为（优雅退化，非错误）。
 */
internal object EverStreamedPartLedger {
    private const val MAX_ENTRIES = 512
    private const val MAX_STATES = 32

    private val entries = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > MAX_ENTRIES
    }

    /**
     * #486 根修二期：pilot 终态 state 对象缓存（槽位销毁时经 onDispose 捕获）。
     * 已解析的 [com.mikepenz.markdown.model.StreamingMarkdownState] 其
     * snapshot 流持有最终解析结果——冷重建直接复用渲染（首帧即有内容，零解析
     * 零空窗）。消费侧必须做内容等值守卫（流式中途销毁缓存的是部分内容）；
     * 守卫失败降级 pilot coldReveal。上限 32（AST 级驻留，容量远小于标记账本）。
     */
    private val terminalStates = object : LinkedHashMap<String, com.mikepenz.markdown.model.StreamingMarkdownState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, com.mikepenz.markdown.model.StreamingMarkdownState>?): Boolean = size > MAX_STATES
    }

    @Synchronized
    fun mark(msgId: String) {
        entries[msgId] = true
    }

    @Synchronized
    fun wasStreamed(msgId: String): Boolean = entries[msgId] == true

    @Synchronized
    fun cacheTerminal(msgId: String, state: com.mikepenz.markdown.model.StreamingMarkdownState) {
        terminalStates[msgId] = state
    }

    @Synchronized
    fun cachedTerminal(msgId: String): com.mikepenz.markdown.model.StreamingMarkdownState? = terminalStates[msgId]

    @Synchronized
    fun clear() {
        entries.clear()
        terminalStates.clear()
    }
}
