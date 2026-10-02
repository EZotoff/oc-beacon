package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.leonardo.ocbeacon.BuildConfig

/**
 * #442 R2 分片唤醒（批次 A2）：流式 shard 中转——pilot（深组合内）发布 →
 * buildChatEntries/ChatMessageList（列表层）消费。
 *
 * 单例（ScrollQuiescence 同款先例——单活 ChatMessageList 进程内单真源）：
 * - **注册表**（非快照，主线程专用）：装配层 Turn 分支登记资格（partId →
 *   turnKey + Fire 钩子）；PartContent 按 part.id 查 [controllerFor] 取控制器，
 *   未注册（非流式/不满足资格/开关关）= null → pilot 零改造路径。
 * - **发布表**（快照态）：Fire 后的冻结块集；驱动 chatEntries 重算（毕业只在
 *   滚动静止时发生——JankHoldGate 冻结窗与发布不冲突）。
 *
 * 资格约束（A2，spec §1/§4）：**流式大文本 part 是 turn 的首个且唯一的文本
 * renderItem**（text-leading turn）。多步 turn（reasoning/工具卡在文本前）的
 * 文档序与 shard 全 turn 粒度插入不兼容（shard 会排到先行内容之上）——拒绝
 * 分片降级为现行单容器（correctness 不破）。
 */
internal object StreamingShardPilot {
    val enabled: Boolean = BuildConfig.STREAM_SHARD_PILOT
}

/** 已发布冻结块（文档序；from/to 为归一化全坐标——machine 冷启续账用）。 */
internal data class ShardDoc(
    val index: Int,
    val from: Int,
    val to: Int,
    val text: String,
)

/** 某 part 的当前发布态（不可变快照；generation 递增=帽 reset 挂钩消重）。 */
internal data class PublishedShards(
    val turnKey: String,
    val partId: String,
    val shards: List<ShardDoc>,
    val tailFrom: Int,
    val generation: Int,
)

/**
 * pilot 侧消费面（经 [StreamingShardBroker.controllerFor] 取得；身份稳定=注册
 * 生命周期；生命周期=Turn item 组合生命周期）。
 */
internal interface ShardController {
    val turnKey: String

    /** 已有发布（完结换装保持 pilot——MarkdownContent 终态切换规避）。 */
    fun hasPublished(): Boolean

    /** 冷启（item 回收重组合）切片原点：已发布 tailFrom，未发布 0。 */
    fun coldStartOrigin(): Int

    /** 冷启播种毕业计划（冻结 append-only 续账——防重复毕业已发布区间）。 */
    fun coldStartPlan(): StreamingGraduation?

    /** Fire：先 [ShardRegistration.onFire]（帽 hardReset），后原子发布。
     *  #503 R2：返回发布成败——未注册（回收竞态）false，接线层据此不落账
     *  （machine.confirmFire 不调用=下批重试），账本与发布态不脱钩。 */
    fun fire(chunks: List<FrozenChunk>, texts: List<String>, tailFrom: Int): Boolean

    /** 非前缀重建（resetKey 路径）：冻结文本已陈旧——清本 part 发布。 */
    fun onRebuild()
}

internal object StreamingShardBroker {
    /** partId → 注册（非快照；主线程专用）。controller 随注册创建（身份稳定——
     *  pilot 的 remember(shard) 播种依赖跨重组同一实例）。 */
    private class Reg(
        val turnKey: String,
        val partId: String,
        val onFire: () -> Unit,
        val controller: ControllerImpl,
    )

    private val registrations = HashMap<String, Reg>()

    /** partId → 发布（快照态：写=chatEntries 重算）。 */
    var shards: Map<String, PublishedShards> by mutableStateOf(emptyMap())
        private set

    fun register(turnKey: String, partId: String, onFire: () -> Unit) {
        registrations[partId] = Reg(turnKey, partId, onFire, ControllerImpl(turnKey, partId))
    }

    fun unregister(partId: String) {
        registrations.remove(partId)
    }

    /** PartContent 组合期查控制器：注册在案（流式）**或已有发布**（完结/回收
     *  重组合——shardHold 渲染持续性需要；fire 对未注册 no-op）可得；否则
     *  null（pilot 走原路径）。 */
    fun controllerFor(partId: String): ShardController? {
        if (!StreamingShardPilot.enabled) return null
        registrations[partId]?.let { return it.controller }
        shards[partId]?.let { return ControllerImpl(it.turnKey, partId) }
        return null
    }

    private class ControllerImpl(
        override val turnKey: String,
        private val partId: String,
    ) : ShardController {

        override fun hasPublished(): Boolean = shards.containsKey(partId)

        override fun coldStartOrigin(): Int = shards[partId]?.tailFrom ?: 0

        override fun coldStartPlan(): StreamingGraduation? {
            val p = shards[partId] ?: return null
            return StreamingGraduation(
                p.shards.map { FrozenChunk(it.from, it.to) },
                p.tailFrom,
            )
        }

        override fun fire(chunks: List<FrozenChunk>, texts: List<String>, tailFrom: Int): Boolean {
            // 帽 hardReset 先行（同协程步）：换装帧 measure 见 reserved<0 直通真高
            // ——沿用旧 reserved 会因「帽不回改」使尾块永久虚高冻结区高度
            val reg = registrations[partId] ?: return false
            reg.onFire()
            val prev = shards[partId]
            val docs = chunks.mapIndexed { i, c ->
                ShardDoc(i, c.from, c.to, texts[i])
            }
            shards = shards + (partId to PublishedShards(
                turnKey = reg.turnKey,
                partId = partId,
                shards = docs,
                tailFrom = tailFrom,
                generation = (prev?.generation ?: 0) + 1,
            ))
            return true
        }

        override fun onRebuild() {
            if (shards.containsKey(partId)) {
                shards = shards - partId
            }
        }
    }

    /** 列表离树清空（会话切换防跨会话陈旧堆积；导航过渡双列表重叠窗口的
     *  旧侧清理由新侧 pilot 重新武装/发布自愈——接受该罕见重分片窗）。 */
    fun clearAll() {
        registrations.clear()
        shards = emptyMap()
    }

    /** 测试缝：全清（单例跨用例隔离）。 */
    fun resetForTest() {
        registrations.clear()
        shards = emptyMap()
    }
}
