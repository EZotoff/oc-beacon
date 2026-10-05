package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** #488②：M3 colorScheme → highlights SyntaxTheme 9 角色映射纯函数。 */
class CodeSyntaxThemeTest {

    @Test
    fun `明色 scheme 九角色逐项映射`() {
        val scheme = lightColorScheme(
            primary = Color(0xFF112233),
            secondary = Color(0xFF445566),
            tertiary = Color(0xFF778899),
            onSurface = Color(0xFFABCDEF),
            onSurfaceVariant = Color(0xFF123456),
        )
        val theme = scheme.toCodeSyntaxTheme()
        assertEquals("ocbeacon", theme.key)
        assertEquals(0xFFABCDEF.toInt(), theme.code)
        assertEquals(0xFF112233.toInt(), theme.keyword)
        assertEquals(0xFF778899.toInt(), theme.string)
        assertEquals(0xFF445566.toInt(), theme.literal)
        assertEquals(0xFF123456.toInt(), theme.comment)
        assertEquals(0xFF123456.toInt(), theme.multilineComment)
        assertEquals(0xFF778899.toInt(), theme.metadata)
        assertEquals(0xFF123456.toInt(), theme.punctuation)
        assertEquals(0xFF778899.toInt(), theme.mark)
    }

    @Test
    fun `暗色 scheme 值跟随不锁死`() {
        val scheme = darkColorScheme(
            primary = Color(0xFF102030),
            secondary = Color(0xFF405060),
            tertiary = Color(0xFF708090),
            onSurface = Color(0xFFA0B0C0),
            onSurfaceVariant = Color(0xFFD0E0F0),
        )
        val theme = scheme.toCodeSyntaxTheme()
        assertEquals(0xFF102030.toInt(), theme.keyword)
        assertEquals(0xFF708090.toInt(), theme.string)
        assertEquals(0xFF405060.toInt(), theme.literal)
        assertEquals(0xFFA0B0C0.toInt(), theme.code)
        assertEquals(0xFFD0E0F0.toInt(), theme.comment)
    }

    @Test
    fun `toArgb 保全 alpha 通道`() {
        // ColorHighlight 消费端强制 alpha=1，但映射本身不得丢非透明色以外信息
        val scheme = lightColorScheme(primary = Color(0x80FF0000))
        assertEquals(0x80FF0000.toInt(), scheme.toCodeSyntaxTheme().keyword)
    }

    @Test
    fun `AMOLED 免特判——同 scheme 同映射`() {
        // AMOLED 只覆盖 surface 族（Theme.kt），accent 角色不变 → 同一 scheme
        // 实例映射结果稳定（纯函数性质钉死）
        val scheme = darkColorScheme(tertiary = Color(0xFF00FF00))
        assertEquals(scheme.toCodeSyntaxTheme(), scheme.toCodeSyntaxTheme())
    }
}
