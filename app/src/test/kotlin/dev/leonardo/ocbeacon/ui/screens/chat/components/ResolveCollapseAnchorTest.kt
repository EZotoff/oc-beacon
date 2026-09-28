package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #462(2026-09-29):多卡展开态收起锚点解析——增量镜像语义(纯函数)。
 *
 * 根因(数学定罪+真机日志证实:展开配对后 fiso+=H,收起快照恢复=−H):
 * 收起用「本卡展开前绝对快照」恢复,仅在无其他位移时正确;多卡场景
 * V=Pa+H_A+H_B,收起 A 绝对恢复 Pa → 偏差 −H_B(视口多退 B 的展开量)
 * =「收起其一视角跳变」。增量镜像恒正确:目标=当前 V−本卡实际配对消费。
 */
class ResolveCollapseAnchorTest {

    @Test
    fun `zero consumption keeps anchor unchanged`() {
        // 贴底免派发/未消费:consumedPx=0 → 原位(贴底锚定自动保持,天然自洽)
        assertEquals(Pair(0, 0), resolveCollapseAnchor(0, 0, 0f, emptyList()))
        assertEquals(Pair(8, 4103), resolveCollapseAnchor(8, 4103, 0f, emptyList()))
    }

    @Test
    fun `single card mirrors own consumed shift`() {
        // 单卡:fiso=4297(=快照4103+H194),consumed=194 → 目标 4103(与旧绝对恢复等价)
        assertEquals(Pair(8, 4103), resolveCollapseAnchor(8, 4297, 194f, emptyList()))
    }

    @Test
    fun `multi card preserves other cards net shift`() {
        // 多卡:A 展开(194)后 B 展开(260):fiso=Pa+194+260。收起 A(consumed=194)
        // → 目标=Pa+260(保 B);旧绝对恢复 Pa 会偏 −260(视角跳变 260px)
        val pa = 3909
        assertEquals(
            Pair(8, pa + 260),
            resolveCollapseAnchor(8, pa + 194 + 260, 194f, emptyList()),
        )
    }

    @Test
    fun `cross item boundary walks back via visible sizes`() {
        // fiso=100−150=−50 → 落前一 item(size 520):offset'=520−50=470 → (7, 470)
        // (LazyList 语义:offset=视口起点距 item 顶;V=sum(sizes[..])+offset 守恒)
        assertEquals(Pair(7, 470), resolveCollapseAnchor(8, 100, 150f, listOf(520)))
    }

    @Test
    fun `boundary beyond visible chain returns null for dispatch fallback`() {
        // 链不足(fii=0 或 sizes 空)→ null(调用方退 dispatch 镜像)
        assertNull(resolveCollapseAnchor(0, 100, 150f, emptyList()))
        assertNull(resolveCollapseAnchor(8, 100, 150f, emptyList()))
    }
}
