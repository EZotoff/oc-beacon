package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * 卡内滚动 fling 泄漏守卫（#474 五轮，2026-09-29 两度真机定罪）。
 *
 * **问题**：消息流 item 内的卡内滚动容器（思考内容/工具输出/Read/Bash/
 * Question 等 21 处 verticalScroll）的 fling 走嵌套滚动协议——内容到顶/底
 * 后**每帧剩余量经 onPostScroll 泄漏给外层 LazyList**（reverseLayout 下符号
 * =朝底方向），且触摸打断 fling 时**剩余速度经 onPostFling 转移**（实测
 * -1480px/s 一帧 LEAP 7→0 拉到对话流最底）=用户「卡内 fling 中触碰卡片，
 * 视口被拉到最底部」主诉。
 *
 * **机制**：[onPreFling] 标记 fling 窗口（子 fling 启动前父侧连接先知）→
 * 窗口内 [onPostScroll] 全额吞没（逐帧泄漏通道）+ [onPostFling] 吞残速并
 * 清窗。**drag 泄漏放行**——手指按住滑到底继续滑=滚视口，是期望的滚动链
 * 嵌套行为（用户此前对该行为无异议）。
 *
 * **挂载**：item 级（ChatMessageList 的消息 item Box）——一个拦截点覆盖
 * 该 item 内**全部**卡内滚动容器（含未来新增卡型），无需逐卡改造。
 */
@Composable
fun Modifier.cardFlingLeakGuard(): Modifier {
    val connection = remember {
        object : NestedScrollConnection {
            private var flingActive = false

            override suspend fun onPreFling(available: Velocity): Velocity {
                flingActive = true
                return Velocity.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): Offset = if (flingActive) available else Offset.Zero

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                flingActive = false
                return available
            }
        }
    }
    return this.nestedScroll(connection)
}
