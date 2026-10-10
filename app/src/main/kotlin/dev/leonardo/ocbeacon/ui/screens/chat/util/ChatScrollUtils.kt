package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.foundation.lazy.LazyListState

/**
 * 显式用户操作（FAB 点击/错误 snackbar）时的即时吸附到底部——单次原子锚定。
 *
 * 历史：#256（2026-08-28）曾以「到底后 3×120ms 周期性强推钉底」补偿滚底后
 * 新入视口 item 的延迟测量长高（shell +55 / Markdown +194/+466，30-70ms
 * 爆发窗把视口推离底部 → FAB 复现「拉不到最低」）。
 *
 * #541 根修（2026-10-10 取证退役锤）：reverseLayout 贴底锚 (fii=0,fiso=0) 下
 * item 增长向上扩展、不移动锚点——#256 时代的推动者已被后续滚动域根修消灭
 * （#432 贴底免派发/#435 引擎配对/#437 帽/#476 GUARD 死区）。埋点实测锤
 * 15/15 击零消费（延迟测量爆发真实复现但零位移），按消除源哲学删除周期性强推；
 * 真实离底（横幅插入 index 位移等）由 MSGEFFECT/GUARD 跟随族按用户语义重锚
 * （尊重滚动让位），不再由无视手势的定时强拉兜底。复发时勿恢复锤——先查
 * index 位移推动者，走跟随族域。
 *
 * 使用 reverseLayout=true 时，"底部" = 第 0 项。
 */
internal fun LazyListState.snapToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    dev.leonardo.ocbeacon.ui.screens.chat.components.ViewportDispatchGateway.explicitPin(this, "SNAP", "fab-error")
}
