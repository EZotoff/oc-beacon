package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Velocity

/**
 * 卡内滚动→外层列表的嵌套泄漏**符号适配**（#474 五轮终修，2026-09-29）。
 *
 * **根因**：消息流 item 内的卡内滚动容器（思考内容/工具输出等 21 处
 * verticalScroll，正常布局）与外层 LazyList（reverseLayout）的滚动 delta
 * 语义**相反**——卡内到边后的泄漏量（drag 剩余与 fling 惯性）被外层按
 * **反向**消费：用户上滑内容到底（想继续看更早内容）外层却朝对话流底部
 * 猛滚（实测残速 -1480px/s 一帧 LEAP 7→0 拉到底=用户「触碰卡片视口即
 * 跳底」主诉）。
 *
 * **修复**：泄漏量（onPostScroll 的 available 与 onPostFling 的残速）
 * **取反传导**——滚动链不断，方向纠正：卡内 fling 到边后惯性按用户手势
 * 意图继续传导外层（上滑到底→视口朝更早内容；下拉到顶→朝最新内容）。
 *
 * **挂载**：item 级（ChatMessageList 消息 item Box）——单点覆盖该 item
 * 内全部卡内滚动容器。吞没式旧方案（五轮首修）已被用户否决（打补丁）。
 */
@Composable
fun Modifier.cardFlingLeakGuard(): Modifier {
    val connection = remember {
        object : NestedScrollConnection {
            // 反转泄漏量符号:正常布局子→reverseLayout 父的 delta 语义适配
            private fun Offset.inverted(): Offset =
                if (x == 0f && y == 0f) this else Offset(-x, -y)

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): Offset = available.inverted()

            override suspend fun onPreFling(available: Velocity): Velocity =
                // 父不预取(泄漏全量经 onPostScroll/onPostFling 反转传导)
                Velocity.Zero

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity =
                if (available.x == 0f && available.y == 0f) available
                else Velocity(-available.x, -available.y)
        }
    }
    return this.nestedScroll(connection)
}
