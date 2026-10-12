package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.PartIdContract
import dev.leonardo.ocbeacon.ui.screens.chat.tools.DefaultToolCardResolver
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolCardResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ============ 通过 CompositionLocal 传递的聊天设置 ============

/** 工具卡片是否默认自动展开（#202 改名自 LocalCollapseTools；true=展开，与存储值同向）。 */
val LocalAutoExpandTools = compositionLocalOf { false }

/** reasoning 块是否默认展开。 */
val LocalExpandReasoning = compositionLocalOf { false }

/** 是否在同一轮次的消息间显示分隔线。 */
val LocalShowTurnDividers = compositionLocalOf { true }

/** 是否启用触觉反馈。 */
val LocalHapticFeedbackEnabled = compositionLocalOf { true }

/**
 * 当前会话是否正在活跃流式传输（FSM activity = Streaming）。
 * reasoning 计时器的权威控制；与 per-part `time.end == null` 组合，
 * 使只有当前 reasoning part 显示计时器（方案 B）。
 */
val LocalSessionStreaming = staticCompositionLocalOf { false }

/**
 * #540 B：回合级活动信号（ChatViewModel.turnActiveState——上升沿即时、下降沿
 * 3s 防抖）。统计栏**视觉族**（计时器/流式徽标/反馈钮门）的步间防翻转判据：
 * DSH 工具宿主在 step 边界回填 completed 后，组判据在步间空窗翻非流式——
 * 视觉族并上本信号整 turn 恒稳；parse 族（asyncParse）不吃本信号（完结即
 * 终态解析是 #428/#509 的既定毕业语义）。
 */
val LocalTurnActive = staticCompositionLocalOf { false }

/** 图片保存请求回调，供图片预览 composable 使用。 */
val LocalImageSaveRequest = compositionLocalOf<(ByteArray, String, String?) -> Unit> { { _, _, _ -> } }

/**
 * 已持久化的工具卡片展开/折叠状态，以 Part.Tool.id 或 Part.Patch.id 为键。
 *
 * #429 L0：以 **StateFlow 整体**下沉（稳定身份）——原 Map 直供时，任一键
 * 翻转=新 Map 实例=local 值变化=**全部读者**重组（真机实测 toggle 一次全列表
 * 重组+GC 122MB）。读者经 [toolExpandedOrDefault] 做 per-key 派生读取，
 * 只在自己键的值翻转时重组。
 */
val LocalToolExpandedStates = compositionLocalOf<StateFlow<Map<String, Boolean>>> {
    MutableStateFlow(emptyMap())
}

/**
 * #429 L0：逐键展开态读取——collectAsState 订阅不读值（不触发本层重组），
 * derivedStateOf 只在**本键**的布尔值翻转时使读者失效。
 */
@androidx.compose.runtime.Composable
internal fun toolExpandedOrDefault(key: String, default: Boolean = false): Boolean {
    val states: State<Map<String, Boolean>> = LocalToolExpandedStates.current.collectAsState()
    return remember(key) { derivedStateOf { states.value[key] ?: default } }.value
}

/**
 * #547：展开状态稳定键——派生契约 id（`<messageId>_<kind>_ord_<n>`，V1/V2/DSH
 * 流式 text/reasoning）内嵌宿主消息 id：多消息 turn 重组换宿主时前缀级联漂移 →
 * partId 键控展开态 map 脱靶 → 静默回退收起（soak5r r13：toggle 后 79ms 无
 * toggle 日志清回）。服务器赋值 id（工具/patch/shell…）自身稳定，原样直通；
 * 仅派生 id 改按「kind + 空白归一化前缀指纹」键控：
 * - 流式 append 只增长 → 归一化长度 ≥ 窗口后前缀指纹恒定（toggle 后继续输出不丢）；
 * - 换宿主内容不变 → 跨重组稳定（本卡修复目标）；
 * - 空白折叠两套字节表示（#515 H1 勘误同源）归一后同键；
 * - 归一化前缀全同的两张卡同键（同开同关、会话内存态下罕见且无害——非错位）。
 * 键演化窗口（成长跨窗/空白期直通→首内容）：由消费点别名收养
 * （[LocalOnMigrateExpandAlias]）单向迁移旧键状态，mapHit 不闪断。
 * 空白文本退回原 id（started 未收 delta 的零内容卡无可指纹物，亦无展开价值）。
 * C4（ExpandStateStore 单一真相源）落地时本键即其键律。
 */
object ExpandStateKeys {
    private const val PREFIX_CHARS = 96

    fun forPart(partId: String, text: String): String {
        if (!PartIdContract.isDerived(partId) || text.isBlank()) return partId
        val kind = if (partId.contains(PartIdContract.REASONING_MARKER)) "r" else "t"
        val sb = StringBuilder(PREFIX_CHARS + 8)
        for (c in text) {
            if (c.isWhitespace()) continue
            sb.append(c)
            if (sb.length >= PREFIX_CHARS) break
        }
        return "x$kind:" + sb.toString().hashCode().toUInt().toString(16)
    }
}

/**
 * #547：展开态键别名收养——卡片身份键演化（流式增长跨指纹窗、空白直通→首内容）
 * 时旧键状态单向迁移到新键（一次性、幂等；旧键残留无害——map 本就 append-only）。
 * 消费点在组合期同步调用（LaunchedEffect 晚一帧=一帧假塌缩闪烁，#540 四报
 * 组合期同步注册同款先例）。
 */
val LocalOnMigrateExpandAlias = staticCompositionLocalOf<(fromKey: String, toKey: String) -> Unit> {
    { _, _ -> }
}

/** 通过 part id 切换工具卡片展开状态的回调。 */
val LocalOnToggleToolExpanded = compositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

/** 工具特定卡片 composable 的解析器。 */
val LocalToolCardResolver = compositionLocalOf<ToolCardResolver> {
    DefaultToolCardResolver()
}

/** 以 sessionId 为键的文件 diff。支撑 [dev.leonardo.ocbeacon.domain.model.Part.Patch] 行数统计。 */
val LocalSessionDiffs = compositionLocalOf<Map<String, List<FileDiff>>> { emptyMap() }

/**
 * #182（2026-08-21）：Task 工具卡片展开时的全量输出拉取器。
 * 策略（grilling Q13 定案）：part 优先（重拉父会话消息按 part id 取服务器
 * 全量 output）→ part 截断/缺失时降级子智能体会话 transcript。DB 仍存 500 字符
 * 预览（#79 体积目标不变）。返回 null = 两路均未取到（卡片用本地预览）。
 */
val LocalTaskOutputFetcher = compositionLocalOf<(suspend (partId: String, subSessionId: String?) -> String?)?> { null }
