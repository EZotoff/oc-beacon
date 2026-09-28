package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #432 贴底免派发判定:贴底全域(fii==0)展开集跳过位移派发。
 * 用户主诉「展开时跳转到其他地方」:贴底端 dispatch +H 把视口推离贴底,
 * 正在看的最新回复被推出屏。
 * 2026-09-28 二轮放宽:半贴底(fii==0 offset>0)一并启用——真机用户验收
 * 定罪反射 (0,fiso+H) 把锚定区下拖 H(「点击时内容往下拖」偶发主诉);
 * 锚定底部语义半贴底同成立(可见卡恒在锚定线上方,增长向上扩展,锚稳定)。
 */
class BottomPinnedExpandSkipTest {

    @Test
    fun strictBottomPinnedSkips() {
        assertTrue(bottomPinnedExpandSkip(fii = 0, fiso = 0))
    }

    @Test
    fun halfBottomAlsoSkips() {
        assertTrue(bottomPinnedExpandSkip(fii = 0, fiso = 424))
    }

    @Test
    fun midListDoesNotSkip() {
        assertFalse(bottomPinnedExpandSkip(fii = 7, fiso = 212))
        assertFalse(bottomPinnedExpandSkip(fii = 14, fiso = 0))
    }
}