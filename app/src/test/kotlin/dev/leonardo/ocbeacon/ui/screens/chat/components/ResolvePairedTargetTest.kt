package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListItemInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * #438 R-1（2026-09-28 null-key 裸 index 通道根修）的纯函数测试。
 *
 * 机制：配对目标位解析（resolvePairedTarget）——可见窗内按 layout 索引/尺寸
 * 换算（原逻辑）；落点越窗时原逻辑 targetKey=null → 反射 requestPosition 按
 * 字面 index 重锚，插入/重排后即 LEAP 残余通道（R9 -7562 实证，817607b4 的
 * 条件式修复只覆盖「落点可见」）。本修复引入数据侧 key 投影兜底：越窗时
 * index → chatEntries key（装配层提供），key 锚定使重排后仍正确重锚。
 */
class ResolvePairedTargetTest {

    private fun item(index: Int, key: Any, size: Int): LazyListItemInfo {
        val m = mockk<LazyListItemInfo>()
        every { m.index } returns index
        every { m.key } returns key
        every { m.size } returns size
        return m
    }

    @Test
    fun `in-window target resolves anchor key with cross-item offset conversion`() {
        val infos = listOf(item(0, "a", 100), item(1, "b", 200), item(2, "c", 50))
        val (fii, fiso, key) = resolvePairedTarget(0, 80, total = 150, visible = infos, dataKeyAt = null)
        // 80+150=230；230>=a.size(100) → offset 130；130<b.size(200) → 落 b@130
        check(fii == 1 && fiso == 130 && key == "b")
    }

    @Test
    fun `out-of-window target falls back to data-side key projection`() {
        val infos = listOf(item(0, "a", 100)) // 只可见 a(100px)：600 换算消耗 a 后落 index 1 越窗
        val (fii, fiso, key) = resolvePairedTarget(0, 0, total = 600, visible = infos, dataKeyAt = { i -> "entry_$i" })
        // 设计语义：key 锚定到越窗首项（entry_1），offset 用换算余量近似（500，下帧自愈）
        check(key == "entry_1") { "expected data-side key, got $key" }
        check(fii == 1 && fiso == 500)
    }

    @Test
    fun `out-of-window without projection degrades to legacy null key`() {
        val infos = listOf(item(0, "a", 100))
        val (fii, _, key) = resolvePairedTarget(0, 0, total = 600, visible = infos, dataKeyAt = null)
        check(key == null && fii == 1) // 旧通道：anchor miss 后停在下一个 index
    }

    @Test
    fun `projection miss also degrades to null`() {
        val infos = listOf(item(0, "a", 100))
        val (_, _, key) = resolvePairedTarget(0, 0, total = 600, visible = infos, dataKeyAt = { null })
        check(key == null)
    }
}
