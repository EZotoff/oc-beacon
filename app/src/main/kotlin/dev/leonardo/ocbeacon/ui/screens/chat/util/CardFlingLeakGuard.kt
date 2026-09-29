package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch

/**
 * 卡内滚动→外层列表的嵌套泄漏**符号适配传导**（#474 五轮终修）。
 *
 * **根因**：卡内滚动容器（正常布局）与外层 LazyList（reverseLayout）的
 * delta 语义相反——卡内到边泄漏量被外层反向消费（上滑内容到底想继续看
 * 更早内容，外层却朝对话流底部滚；制动残速 -1484px/s 一帧拉到底=用户
 * 「触碰卡片视口即跳底」主诉）。
 *
 * **修复（协议内反转）**：onPostScroll/onPostFling **全额吞没原始泄漏**
 * （返回 available=已消费，向上传零）+ **同帧驱动外层反转位移**
 * （animateScrollBy(-y)）——滚动链保留、方向按手势意图传导：上滑到底→
 * 视口朝更早内容；下拉到顶→朝最新内容。残速经速度→距离换算后同路注入。
 *
 * **挂载**：item 级（ChatMessageList 消息 item Box），单点覆盖全部卡内
 * 滚动容器。
 */
@Composable
fun Modifier.cardFlingLeakGuard(listState: androidx.compose.foundation.lazy.LazyListState): Modifier {
    val scope = rememberCoroutineScope()
    val connection = remember(listState, scope) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): Offset {
                if (available.y == 0f) return Offset.Zero
                // 吞原始泄漏 + 反转注入外层(下一帧执行——注入经 scroll 通道
                // 走 SafeFling/锚定全协议,不绕过任何机制)
                scope.launch { listState.scrollBy(-available.y) }
                return available
            }

            override suspend fun onPreFling(available: Velocity): Velocity =
                Velocity.Zero

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                if (available.y != 0f) {
                    // 残速→距离(SafeFling 同款指数摩擦量级换算)反转注入
                    val distance = available.y * 0.12f
                    scope.launch { listState.scrollBy(-distance) }
                }
                return available
            }
        }
    }
    return this.nestedScroll(connection)
}
