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
 * 卡内滚动→外层列表的嵌套传导守卫（#474 六轮，2026-10-08 用户语义终裁）。
 *
 * 用户裁决的分通道语义：
 *  - **拖拽（drag / UserInput）→ 自然传导**：手指触摸拖动到卡片内容边缘后，
 *    剩余位移沿嵌套滚动协议零干预上传，外层列表同向滚动（内容跟手）。
 *    协议坐标系统一为屏幕语义（正 y=内容上移），reverseLayout 的方向归一
 *    由 LazyList 内部处理——自然链方向天然正确，无需也不应人为反转。
 *  - **惯性（fling / SideEffect）→ 边界吸收**：卡片内容惯性滚动到边后，
 *    剩余动量（逐帧 onPostScroll 泄漏 + onPostFling 残速）在本连接就地
 *    吞没，不上传、不注入——外层列表不因卡内惯性而移动。
 *
 * 历史教训（五轮「反转注入」已废弃）：对泄漏量取反注入把拖拽通道一并反转
 * （卡底下滑视口反向上移，用户实测否决），且给惯性通道人为造出对向传导。
 * 原始 bug（触碰即跳底）只出在惯性通道：泄漏残速被外层 SafeFling 消费
 * 朝底猛拉——惯性全吸收后该路径不复存在。
 *
 * **挂载**：item 级（ChatMessageList 消息 item Box），单点覆盖全部卡内
 * 滚动容器（思考内容/工具输出等 21 处）。
 */

/** 单个到边泄漏的处置策略。 */
internal enum class CardLeakDisposition { ChainNaturally, AbsorbAtEdge }

/**
 * 按泄漏来源分类：仅惯性/动画驱动（SideEffect，含 fling）到边吸收；
 * 用户输入（UserInput 拖拽、Wheel 滚轮）与 Relocate（bringIntoView 等
 * 程序化重定位）走自然传导。
 */
internal fun leakDisposition(source: NestedScrollSource): CardLeakDisposition =
    if (source == NestedScrollSource.SideEffect) CardLeakDisposition.AbsorbAtEdge
    else CardLeakDisposition.ChainNaturally

/** 守卫连接（独立工厂便于单元测试）。 */
internal fun cardLeakGuardConnection(): NestedScrollConnection =
    object : NestedScrollConnection {
        private var absorbedPx = 0f

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset = when (leakDisposition(source)) {
            // 拖拽/滚轮/重定位：零干预——剩余量按协议继续上传，
            // 外层列表自然同向滚动
            CardLeakDisposition.ChainNaturally -> Offset.Zero
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
fun Modifier.cardFlingLeakGuard(): Modifier =
    nestedScroll(remember { cardLeakGuardConnection() })
