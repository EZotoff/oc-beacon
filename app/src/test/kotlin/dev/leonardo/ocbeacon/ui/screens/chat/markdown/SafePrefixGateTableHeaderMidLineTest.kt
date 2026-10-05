package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坍缩重建根修补口（2026-09-30 14:58 真机定罪）：批预算把放行截点落在
 * 表头行**行中**（已放行前缀越过未来插空行点）时，既有「表头行待定三行
 * 结构回退」以截点收在行尾为前提漏检本形态——ensureBlankLineBeforeGfmTables
 * 在表头行行首前插入空行（分隔行到达时）→ 非前缀改写 → pilot RESETKEY
 * 重建坍缩。本测试钉死：任何预算下，放行不越过表头行行首/行首空白。
 */
class SafePrefixGateTableHeaderMidLineTest {

    @Test
    fun midLineCutInsideTableHeaderRowRetreatsToHeaderLineStart() {
        // 分隔行未完（无换行收尾）= 三行结构未定案：任何预算下放行不得越过
        // 表头行行首（未来插空行点）——截在表头行中/行首空白/未完分隔行内
        // 三种形态全部回退。
        val snap = "段落\n- 项目状态\n  | 项目 | 状态 |\n  |------|------|"
        val headerLs = snap.indexOf("  |")
        for (budget in 1..snap.length) {
            val r = SafePrefixGate.releaseDelta(snap, 0, budget).newReleased
            assertTrue(
                "budget=" + budget + " released=" + r + " 越过表头行行首 " + headerLs,
                r <= headerLs,
            )
        }
    }

    @Test
    fun completeSeparatorUnlocksReleasePastHeader() {
        // 分隔行完整（三行结构定案、归一化已插空行）：表头/分隔行整体放行解锁
        val snap = "段落\n- 项目状态\n  | 项目 | 状态 |\n  |------|------|\n  | 设计 | 完成 |"
        val r = SafePrefixGate.releaseDelta(snap, 0, Int.MAX_VALUE).newReleased
        // 末条正文行未带换行收尾按未完行扣留（既有语义）；断言=放行越过完整分隔行
        val sepEnd = snap.indexOf("  | 设计 |")
        assertTrue("完整表格应放行过分隔行，released=" + r + " sepEnd=" + sepEnd, r >= sepEnd)
    }

    @Test
    fun whitespaceOnlyTailLineHeldAtLineStart() {
        val snap = "段落\n- 项目状态\n  "
        val r = SafePrefixGate.releaseDelta(snap, 0, Int.MAX_VALUE).newReleased
        assertEquals("空白尾行扣留在行首", snap.indexOf("  "), r)
    }

    @Test
    fun prefixMonotonicAcrossTableSeparatorArrival() {
        val fence = String(intArrayOf(96, 96, 96), 0, 3)
        val base = "清单：\n" +
            "  " + fence + "python\n  x = 1\n  " + fence + "\n\n" +
            "**引用：**\n- 注\n  > q\n\n" +
            "**表格：**\n- 状态\n  | 项目 | 状态 |\n  |------|------|\n  | 设计 | 完成 |"
        var releasedMax = 0
        for (k in 1..base.length) {
            val snap = base.take(k)
            val r = SafePrefixGate.releaseDelta(snap, 0, Int.MAX_VALUE).newReleased
            // pilot 语义：released 取历史最大（gate 回退不回写 state）——已放行
            // 前缀必须是后续快照的前缀（非前缀改写=RESETKEY 重建坍缩的源头）。
            assertTrue(
                "k=" + k + " 非前缀改写：releasedMax=" + releasedMax + " r=" + r,
                snap.startsWith(base.take(minOf(releasedMax, snap.length))),
            )
            if (r >= releasedMax) {
                assertTrue("k=" + k + " 越过未定案表头", snap.startsWith(base.take(r)))
                releasedMax = r
            }
        }
    }
}
