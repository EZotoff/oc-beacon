package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.SubagentCatalogEntry

/**
 * #310① 子会话 composer 门控（纯函数，单测 SubagentComposerGateTest）。
 *
 * - 主会话（parentSessionId == null）恒开——既有路径零语义变化；
 * - 仅子智能体能力位在场时子会话可续聊（mode=continuable 开；one-shot web 禁 composer 同款）；
 * - 无该能力的类型子会话维持既有只读镜像（恒关）；
 * - 加载中/失败/目录无本行（mode == null）保守关——防 one-shot 误发。
 */
internal object SubagentComposerGate {

    fun composerVisible(parentSessionId: String?, subagentsSupported: Boolean, mode: String?): Boolean =
        parentSessionId == null ||
            (subagentsSupported && mode == SubagentCatalogEntry.MODE_CONTINUABLE)

    /**
     * 只读提示行：one-shot（明确不可续聊；加载中/失败不显示——静默保守隐藏）。
     *
     * #497 D3：无 subagents 能力位的服务器（V1/V2）子智能体会话恒只读镜像——同样给
     * 提示行（此前整块空白，连 DSH one-shot 的说明都没有）；mode 值仅在
     * 能力位在场时解析，此分支与 mode 无关。
     */
    fun readOnlyHintVisible(parentSessionId: String?, subagentsSupported: Boolean, mode: String?): Boolean =
        parentSessionId != null &&
            (
                !subagentsSupported ||
                    mode == SubagentCatalogEntry.MODE_ONE_SHOT
                )

    /**
     * #497 D4：DSH 子智能体会话 mode 异步解析期（mode=null）composer 骨架占位——
     * 防底部栏布局跳变 + 提前承接点击（挂载后 FocusRequester 接力聚焦，
     * 键盘不中断）。主会话恒关（composer 即时挂载）；能力位缺席（V1/V2）
     * 恒关（D3 提示行即时渲染，无解析期）。
     */
    fun composerPlaceholderVisible(parentSessionId: String?, subagentsSupported: Boolean, mode: String?): Boolean =
        parentSessionId != null &&
            subagentsSupported &&
            mode == null
}
