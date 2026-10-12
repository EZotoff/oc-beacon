package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #529 soak 根修(2026-10-08):冷抬底资格——内容未组合(measuredH==0)期间
 * floor 持续有效。
 *
 * 定罪(04:42 单帧):回收-展开态重组合后重内容异步组合逾经典冷窗
 * (count≤2),floor 过期→盒上报 0→item −4490 手势中塌缩→147ms 后落地
 * +4490 回长(RESIZE 15982→11492→15982)=可见闪跳。零高不是
 * finalHCache>0 展开卡的合法终态,零高续抬无陈旧风险。
 */
class ColdFloorEligibleTest {

    @Test
    fun `classic cold window lifts within first two measures`() {
        assertTrue(coldFloorEligible(fraction = 1f, animating = false, measureCount = 1, measuredH = 0))
        assertTrue(coldFloorEligible(fraction = 1f, animating = false, measureCount = 2, measuredH = 51))
    }

    @Test
    fun `zero-height content keeps floor beyond cold window`() {
        // 定罪帧:count>2 但内容未组合(异步 markdown 在途)——floor 必须续命
        assertTrue(coldFloorEligible(fraction = 1f, animating = false, measureCount = 3, measuredH = 0))
        assertTrue(coldFloorEligible(fraction = 1f, animating = false, measureCount = 40, measuredH = 0))
    }

    @Test
    fun `landed content releases floor beyond cold window`() {
        // 内容已组合(>0)且过冷窗:真测接管(合法变矮走 steady 配对——旧行为保留)
        assertFalse(coldFloorEligible(fraction = 1f, animating = false, measureCount = 3, measuredH = 120))
    }

    @Test
    fun `collapsed card never lifts`() {
        assertFalse(coldFloorEligible(fraction = 0f, animating = false, measureCount = 1, measuredH = 0))
        assertFalse(coldFloorEligible(fraction = 0.5f, animating = false, measureCount = 1, measuredH = 0))
    }

    @Test
    fun `episode window excludes floor`() {
        // animating 期 episode 独占(抬底不进锚点基,#529 勘误结论维持)
        assertFalse(coldFloorEligible(fraction = 1f, animating = true, measureCount = 1, measuredH = 0))
        assertFalse(coldFloorEligible(fraction = 1f, animating = true, measureCount = 9, measuredH = 0))
    }
}
