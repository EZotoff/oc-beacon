package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import dev.snipme.highlights.model.SyntaxTheme

/**
 * #488②：代码块语法高亮主题——M3 colorScheme 语义色 → highlights
 * [SyntaxTheme] 9 角色映射（纯函数，JVM 可单测）。
 *
 * 放置本域而非 ui/theme：映射目标是 highlights 渲染类型，放 ui/theme 会给
 * 主题层引入 chat 渲染依赖方向（影响面分析 §1.1.c）。
 *
 * 映射决策（ui-conventions Theme Token System 的延伸，映射表已录该文档）：
 * - code=onSurface（= codeBlockFg 非 user 分支，MarkdownContent 颜色三叉）
 * - keyword=primary（最强强调位）
 * - string=tertiary / literal=secondary（Material You 二三色调）
 * - comment/multilineComment/punctuation=onSurfaceVariant（灰阶降权——
 *   ColorHighlight 强制 alpha=1，不能靠透明度降权只能换色相令牌）
 * - metadata/mark=tertiary（无显然 M3 对应的初值，批 2 视觉试验）
 *
 * AMOLED 免特判（Theme.kt 的 AMOLED 只覆盖 surface 族，accent 角色不变）；
 * 动态色自动适配（primary/secondary/tertiary 来自 dynamic scheme）。
 */
internal fun ColorScheme.toCodeSyntaxTheme(): SyntaxTheme = SyntaxTheme(
    key = CODE_SYNTAX_THEME_KEY,
    code = onSurface.toArgb(),
    keyword = primary.toArgb(),
    string = tertiary.toArgb(),
    literal = secondary.toArgb(),
    comment = onSurfaceVariant.toArgb(),
    metadata = tertiary.toArgb(),
    multilineComment = onSurfaceVariant.toArgb(),
    punctuation = onSurfaceVariant.toArgb(),
    mark = tertiary.toArgb(),
)

internal const val CODE_SYNTAX_THEME_KEY = "ocbeacon"
