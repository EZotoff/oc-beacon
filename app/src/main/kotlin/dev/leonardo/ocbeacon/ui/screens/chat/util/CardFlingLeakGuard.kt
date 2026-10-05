package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlin.math.abs

/**
 * 卡内滚动→外层列表的嵌套传导守卫（#474 六轮分通道 + #476 链传导解除武装）。
 *
 * 分通道语义（2026-10-08 用户终裁）：
 *  - **拖拽（UserInput）→ 自然传导**：到边剩余位移零干预上传，外层同向滚动
 *    （方向归一由 LazyList 内部处理，协议天然正确）。
 *  - **惯性（SideEffect）→ 边界吸收**：逐帧泄漏+残速全吞没，不传导不注入。
 *
 * #476 补：嵌套 dispatch 不置外层 `isScrollInProgress`——卡内拖拽链传导
 * 移动外层时 autoScroll 不解除武装，250ms 后 GUARD 把用户刚挪的位置拉回
 * 绝对底（「挪一点被吸到底」主诉之一）。链传导发生时回调 [onUserChain]
 * （挂载点接 onExpandDeparture 同款解除）。
 *
 * **挂载**：item 级（ChatMessageList 消息 item Box），单点覆盖全部卡内
 * 滚动容器（思考内容/工具输出等 21 处）。
 */

/** 单个到边泄漏的处置策略。 */
internal enum class CardLeakDisposition { ChainNaturally, AbsorbAtEdge }

/**
 * 按泄漏来源分类：仅惯性/动画驱动（SideEffect，含 fling）到边吸收；
 * 用户输入（UserInput 拖拽/滚轮）与 Relocate（bringIntoView 程序化重定位）
 * 走自然传导。
 */
internal fun leakDisposition(source: NestedScrollSource): CardLeakDisposition =
    if (source == NestedScrollSource.SideEffect) CardLeakDisposition.AbsorbAtEdge
    else CardLeakDisposition.ChainNaturally

/**
 * 拖拽链传导是否应解除外层 autoScroll 武装——仅真实用户输入；
 * Relocate（程序化重定位）不得解除。
 */
internal fun shouldDisarmOnChain(source: NestedScrollSource): Boolean =
    source == NestedScrollSource.UserInput

/** 守卫连接（独立工厂便于单元测试）。 */
internal fun cardLeakGuardConnection(
    onUserChain: (() -> Unit)? = null,
): NestedScrollConnection =
    object : NestedScrollConnection {
        private var absorbedPx = 0f

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset = when (leakDisposition(source)) {
            // 拖拽/滚轮/重定位：零干预——剩余量按协议继续上传，
            // 外层列表自然同向滚动
            CardLeakDisposition.ChainNaturally -> {
                if (available.y != 0f && shouldDisarmOnChain(source)) {
                    onUserChain?.invoke()
                }
                Offset.Zero
            }
            // 惯性：就地吞没（返回=已消费），外层纹丝不动
            CardLeakDisposition.AbsorbAtEdge -> {
                absorbedPx += abs(available.y)
                available
            }
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            absorbedPx = 0f
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (absorbedPx > 0.5f || available.y != 0f) {
                AppLogger.d(
                    TAG,
                    "fling leak absorbed at card edge: framesPx=${absorbedPx.toInt()} residualV=${available.y.toInt()}px/s",
                )
            }
            absorbedPx = 0f
            // 残速全吞没——不上传（无惯性传导）、不注入（无人为动量）
            return available
        }
    }

private const val TAG = "FlingLeakGuard"

@Composable
fun Modifier.cardFlingLeakGuard(
    onUserChain: (() -> Unit)? = null,
): Modifier = nestedScroll(remember { cardLeakGuardConnection(onUserChain) })
