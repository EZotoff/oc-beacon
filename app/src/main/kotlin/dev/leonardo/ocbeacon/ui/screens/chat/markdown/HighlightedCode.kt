package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.intellij.markdown.ast.ASTNode

/*
 * #488②：主对话流代码块（围栏/缩进）语法高亮组件。
 * fork 自 mikepenz multiplatform-markdown-renderer-code v0.45.0 的
 * MarkdownHighlightedCode.kt（@ tag v0.45.0）——不引 -code 依赖的仓库内
 * 自建壳（影响面分析 §1.0：本需求的三处定制全落其 private 区）。
 *
 * 与上游 fork 基线的差异（core 升级时 diff 官方同文件）：
 *  ① 主题注入 = [SyntaxTheme]（M3 令牌映射，CodeSyntaxTheme.kt）而非
 *     SyntaxThemes.default，且纳入 produceState 键——上游只键 code，
 *     切主题后旧值残留（颜色 stale）。
 *  ② 每高亮作业新建 Highlights.Builder——上游共享 builder 是可变对象
 *     （字节码含 setCode/setLanguage setter），同消息多代码块并发高亮
 *     共享实例会竞态。
 *  ③ 区间守卫：PhraseLocation.end 为 exclusive 语义（官方 README
 *     emphasis(13,25) = 索引 13..24）；反向区间野外真实存在
 *     （SnipMeDev/Highlights#75）→ addStyle 前守卫，防 Reversed range
 *     崩溃（mikepenz#415 同类）。FileViewer HighlightBuilder 的 end+1
 *     是多染一字符的存量偏差（#489），此处按 exclusive 正确语义写。
 *  ④ AppLogger 打点（DEBUG-only，组合侧）。
 * showHeader/immediate 参数未带入（批 1 不开——纯着色零文案零 i18n 面）。
 */

@Composable
internal fun SafeHighlightedCodeFence(
    content: String,
    node: ASTNode,
    style: TextStyle,
    theme: SyntaxTheme,
) {
    MarkdownCodeFence(content, node, style) { code, language, codeStyle ->
        SafeHighlightedCode(code = code, language = language, style = codeStyle, theme = theme)
    }
}

@Composable
internal fun SafeHighlightedCodeBlock(
    content: String,
    node: ASTNode,
    style: TextStyle,
    theme: SyntaxTheme,
) {
    MarkdownCodeBlock(content, node, style) { code, language, codeStyle ->
        SafeHighlightedCode(code = code, language = language, style = codeStyle, theme = theme)
    }
}

@Composable
internal fun SafeHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    theme: SyntaxTheme,
) {
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock
    // 初值 = 纯文本（无空窗）；key 含 theme——主题切换重启作业换新色（上游
    // 潜在 stale 修复）。language 变化仅在流式围栏改写 info 行时可观测，一并入键。
    val codeHighlights: AnnotatedString by produceState(
        initialValue = AnnotatedString(text = code),
        key1 = code,
        key2 = language,
        key3 = theme,
    ) {
        val job = launch(Dispatchers.Default) {
            val t0 = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
            value = buildSafeHighlightedAnnotatedString(code, language, theme)
            if (BuildConfig.DEBUG) {
                AppLogger.d(
                    "CodeHL",
                    "len=" + code.length + " lang=" + (language ?: "-") +
                        " spans=" + value.spanStyles.size + " ms=" + (System.currentTimeMillis() - t0),
                )
            }
        }
        awaitDispose { job.cancel() }
    }

    MarkdownCodeBackground(
        color = backgroundCodeColor,
        shape = RoundedCornerShape(codeBackgroundCornerSize),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        language = language,
        code = code,
    ) {
        MarkdownBasicText(
            text = codeHighlights,
            style = style,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(codeBlockPadding),
        )
    }
}

/**
 * 高亮构建（纯函数，JVM 可单测）。
 *
 * [SyntaxLanguage.getByName] 未命中返回 null（大小写不敏感；1.1.0 字节码
 * aconst_null 分支实证）——未知/空语言**纯文本直返**：实测引擎对无语言
 * 构建仍可能按 DEFAULT 语言集产出内容相关的噪声着色，无语言信息的高亮
 * 本身是猜测，静默纯色才是「与现状等价」的承诺行为。
 * 引擎异常/空产出降级纯文本（runCatching + orEmpty）——getHighlights()
 * 对无语言构建可返回 null（平台可空），高亮纯增益，任何失败不得影响
 * 代码块文本呈现。
 */
internal fun buildSafeHighlightedAnnotatedString(
    code: String,
    language: String?,
    theme: SyntaxTheme,
): AnnotatedString {
    val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
        ?: return AnnotatedString(code)
    val highlights = runCatching {
        Highlights.Builder()
            .code(code)
            .language(syntaxLanguage)
            .theme(theme)
            .build()
            .getHighlights()
    }.getOrNull().orEmpty()
    return applyHighlightSpans(code, highlights)
}

/**
 * span 应用（独立纯函数供 JVM 单测直测区间守卫）。
 *
 * 守卫三连：start 越界 / end 越界钳制 / 反向区间（end <= start）——全部
 * 跳过该 span；ColorHighlight 强制 alpha=1（上游同款，主题色不含透明度）。
 */
internal fun applyHighlightSpans(code: String, highlights: List<CodeHighlight>): AnnotatedString =
    buildAnnotatedString {
        append(code)
        val maxIndex = code.length
        highlights.forEach { h ->
            // end 为 exclusive 语义；coerceAtMost 防引擎产出越界区间
            val start = h.location.start
            val end = h.location.end.coerceAtMost(maxIndex)
            if (start < 0 || start >= maxIndex || end <= start) return@forEach
            when (h) {
                is ColorHighlight -> addStyle(
                    SpanStyle(color = Color(h.rgb).copy(alpha = 1f)),
                    start, end,
                )
                is BoldHighlight -> addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                    start, end,
                )
            }
        }
    }
