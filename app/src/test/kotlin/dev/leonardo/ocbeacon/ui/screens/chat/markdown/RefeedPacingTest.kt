package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重建快速重灌节奏（纯函数锚，2026-09-30 坍缩重建根修 H4）。
 *
 * 真机 13:55 定罪：RESETKEY 重建后的再铺开沿用首跑限速（200ch + 200ms 壁钟）
 * ——4108ch 内容以 200ch/210ms 重灌 4.4 秒（MDResize 每 210ms +988px 恰等于
 * 200 CJK 字符排版高度），用户主诉「整个回答坍缩并重建」的可感知重建段。
 * 重建内容是用户刚看过的材料：走逐帧 4× 批量、免壁钟限速（~6 帧≈100ms
 * 完成换装，高度引擎帽配对吸收）；限速仅保留给首跑铺开（其设计场景：
 * 多消息 turn 后续段全量到达的单帧 GC/解析压力分散）。
 */
class RefeedPacingTest {

    @Test
    fun `first-run keeps throttled pacing`() {
        val p = refeedPacing(rebuild = false)
        assertEquals(BIG_RELEASE_CH, p.chunkCh)
        assertEquals(BIG_RELEASE_MIN_INTERVAL_MS, p.minIntervalMs)
    }

    @Test
    fun `rebuild uses fast refeed pacing - frame-chunked, no wall-clock throttle`() {
        val p = refeedPacing(rebuild = true)
        assertEquals(BIG_RELEASE_CH * 4, p.chunkCh)
        assertEquals(0L, p.minIntervalMs)
    }
}
