package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.delay

/**
 * 显式用户操作（FAB 点击）时的即时吸附到底部。
 *
 * #256 勘误（2026-08-28 真机 ScrollDiag 取证）：snapToBottom 能瞬间到达
 * idx=0/off=0 真底部，但**视口附近 item 的延迟测量随后集中爆发**（shell 卡
 * 正文异步测量 +55、长 Markdown 异步解析 +194/+466——滚到底后新 item 进入
 * 视口才完成解析/测量）把视口推离底部 → isAtBottom=false → FAB 复现，
 * 用户感知「FAB 拉不到最低，还差一小段」。原 while(canScrollBackward) 兜底
 * 在到达底部时条件即 false，**一次强推都没执行**。
 * 修复：到底后 3×120ms 周期性强推钉底（已在底时 scrollBy 负向无空间 =
 * no-op 零副作用；RESIZE 爆发窗口 30-70ms 内被强推拉回）。
 *
 * #541 取证埋点（[DEBUG-541]）：每锤打点滚前/后 (fii,fiso) 与实际消费量——
 * 消费量≠0 ⇒ 首跳后 120ms 内视口确被推离贴底（事后回锚补偿仍承重）；
 * 恒 0 ⇒ 位移源已被后续滚动域根修（#432/#435/#437/#476）消灭，锤为死重。
 */
internal suspend fun LazyListState.snapToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    dev.leonardo.ocbeacon.ui.screens.chat.components.ViewportDispatchGateway.explicitPin(this, "SNAP", "fab-error")
    repeat(3) { i ->
        delay(120)
        val preFii = firstVisibleItemIndex
        val preFiso = firstVisibleItemScrollOffset
        var consumed = 0f
        scroll { consumed = scrollBy(-10_000f) }
        if (BuildConfig.DEBUG) {
            AppLogger.d(
                "SNAP-541",
                "[DEBUG-541] hammer i=" + i + " pre=(" + preFii + "," + preFiso + ")" +
                    " consumed=" + consumed.toInt() +
                    " post=(" + firstVisibleItemIndex + "," + firstVisibleItemScrollOffset + ")",
            )
        }
    }
}
