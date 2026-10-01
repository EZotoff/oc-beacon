package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #442 R2 分片唤醒（A2）——broker 注册/发布/续账语义（单例，逐用例隔离）。
 */
class StreamingShardBrokerTest {

    @After
    fun tearDown() {
        StreamingShardBroker.resetForTest()
    }

    @Test
    fun `未注册且未发布查无控制器`() {
        assertNull(StreamingShardBroker.controllerFor("p_x"))
    }

    @Test
    fun `注册后控制器身份稳定`() {
        StreamingShardBroker.register("t_a", "p_a") {}
        val c1 = StreamingShardBroker.controllerFor("p_a")
        val c2 = StreamingShardBroker.controllerFor("p_a")
        assertNotNull(c1)
        assertSame(c1, c2)
        assertEquals("t_a", c1!!.turnKey)
    }

    @Test
    fun `fire 先行 onFire 钩子再发布（帽 reset 时序）`() {
        val order = mutableListOf<String>()
        StreamingShardBroker.register("t_a", "p_a") { order += "capReset" }
        val ctl = StreamingShardBroker.controllerFor("p_a")!!
        ctl.fire(
            chunks = listOf(FrozenChunk(0, 10), FrozenChunk(10, 20)),
            texts = listOf("AAAA\n\n", "BBBB\n\n"),
            tailFrom = 20,
        )
        assertEquals(listOf("capReset"), order)
        val pub = StreamingShardBroker.shards["p_a"]!!
        assertEquals("t_a", pub.turnKey)
        assertEquals(20, pub.tailFrom)
        assertEquals(2, pub.shards.size)
        assertEquals("AAAA\n\n", pub.shards[0].text)
        assertEquals(1, pub.generation)
        assertTrue(ctl.hasPublished())
    }

    @Test
    fun `fire 未注册时 no-op（完结后无钩子路径）`() {
        StreamingShardBroker.register("t_a", "p_a") {}
        val ctl = StreamingShardBroker.controllerFor("p_a")!!
        StreamingShardBroker.unregister("p_a")
        ctl.fire(listOf(FrozenChunk(0, 5)), listOf("X"), 5)
        assertNull(StreamingShardBroker.shards["p_a"])
    }

    @Test
    fun `注销后已发布 part 仍可得控制器（完结持续性）+ 冷启续账`() {
        StreamingShardBroker.register("t_a", "p_a") {}
        val ctl = StreamingShardBroker.controllerFor("p_a")!!
        ctl.fire(listOf(FrozenChunk(0, 10)), listOf("AAAA\n\n"), 10)
        StreamingShardBroker.unregister("p_a")
        // 注销后：pilot 渲染持续性（shardHold/coldStart）仍需控制器
        val ctl2 = StreamingShardBroker.controllerFor("p_a")
        assertNotNull(ctl2)
        assertEquals(10, ctl2!!.coldStartOrigin())
        val seed = ctl2.coldStartPlan()!!
        assertEquals(listOf(FrozenChunk(0, 10)), seed.chunks)
        assertEquals(10, seed.tailFrom)
        // 但再 fire 已无钩子（no-op，防幽灵发布）
        ctl2.fire(listOf(FrozenChunk(0, 99)), listOf("Y"), 99)
        assertEquals(10, StreamingShardBroker.shards["p_a"]!!.tailFrom)
    }

    @Test
    fun `onRebuild 清发布且冷启回零`() {
        StreamingShardBroker.register("t_a", "p_a") {}
        val ctl = StreamingShardBroker.controllerFor("p_a")!!
        ctl.fire(listOf(FrozenChunk(0, 10)), listOf("AAAA\n\n"), 10)
        ctl.onRebuild()
        assertFalse(ctl.hasPublished())
        assertEquals(0, ctl.coldStartOrigin())
        assertNull(ctl.coldStartPlan())
        assertFalse(StreamingShardBroker.shards.containsKey("p_a"))
    }
}
