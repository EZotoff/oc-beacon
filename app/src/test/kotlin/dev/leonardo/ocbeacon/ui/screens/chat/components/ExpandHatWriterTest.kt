package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #540 帽×机件单写者（planned 写手纯函数）：机件原子帧（反射待定位 + driveTo 同遍
 * measure）落在帽物主 item 上时，抬/落帽必须与增长同快照——否则增长被 min(child,
 * reserved) 钳一帧，flush 残差释放与机件反射叠成双注入/错帧（§4.1 宽限帽污染面）。
 */
class ExpandHatWriterTest {

    @Test
    fun `宿主即物主抬帽`() {
        assertEquals(1066, hatPairedRaise(reserved = 1000, ownerKey = "k1", hostKey = "k1", delta = 66))
    }

    @Test
    fun `非物主不动`() {
        assertEquals(1000, hatPairedRaise(reserved = 1000, ownerKey = "k2", hostKey = "k1", delta = 66))
    }

    @Test
    fun `未初始化不动`() {
        assertEquals(-1, hatPairedRaise(reserved = -1, ownerKey = "k1", hostKey = "k1", delta = 66))
    }

    @Test
    fun `零或负增量不动`() {
        assertEquals(1000, hatPairedRaise(reserved = 1000, ownerKey = "k1", hostKey = "k1", delta = 0))
    }

    @Test
    fun `落帽镜像`() {
        assertEquals(934, hatPairedLower(reserved = 1000, ownerKey = "k1", hostKey = "k1", delta = 66))
    }

    @Test
    fun `落帽下界哨兵归未初始化`() {
        assertEquals(-1, hatPairedLower(reserved = 50, ownerKey = "k1", hostKey = "k1", delta = 66))
    }

    @Test
    fun `落帽非物主或零增量不动`() {
        assertEquals(1000, hatPairedLower(reserved = 1000, ownerKey = "k2", hostKey = "k1", delta = 66))
        assertEquals(1000, hatPairedLower(reserved = 1000, ownerKey = "k1", hostKey = "k1", delta = 0))
    }
}
