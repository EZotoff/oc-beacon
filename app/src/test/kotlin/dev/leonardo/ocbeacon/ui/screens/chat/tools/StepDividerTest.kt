package dev.leonardo.ocbeacon.ui.screens.chat.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #463(2026-09-29):step 边界分割线判定(纯函数)。
 *
 * 用户提案:React 密集轮次(思考+执行卡重复铺屏)在 turn 内每个 step 之间
 * 加分割线并标注 Step x(step 边界=消息边界,#422 既有语义)。判定:首个
 * step 前(turn 开始处)不插;第 k≥2 个 step 的首组前插线并标 k。
 */
class StepDividerTest {

    private val starts = listOf(0, 3, 5) // step1@0 step2@3 step3@5(共 8 组)

    @Test
    fun `first step has no divider`() {
        assertNull(stepDividerBefore(0, starts))
    }

    @Test
    fun `second and later step starts get divider with ordinal`() {
        assertEquals(2, stepDividerBefore(3, starts))
        assertEquals(3, stepDividerBefore(5, starts))
    }

    @Test
    fun `non boundary groups have no divider`() {
        assertNull(stepDividerBefore(1, starts))
        assertNull(stepDividerBefore(4, starts))
        assertNull(stepDividerBefore(7, starts))
    }

    @Test
    fun `empty or single step never divides`() {
        assertNull(stepDividerBefore(0, emptyList()))
        assertNull(stepDividerBefore(0, listOf(0)))
        assertNull(stepDividerBefore(2, listOf(0)))
    }
}
