package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.R
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
 *
 * #488③：SafeHighlightedMathBlock——数学降级块（```math 围栏，
 * [transformMathFallback] 产物专属识别位）的「公式」徽标 + 手写轻着色。
 * highlights 引擎无 tex/math 语言（SyntaxLanguage 枚举取证），
 * 不走 [buildSafeHighlightedAnnotatedString]。
 */

/** #488③：数学降级块的围栏 info 识别位（transformMathFallback 产物专属，AI 手写 math 围栏同享受徽标——语义本就是数学）。 */
internal const val MATH_FENCE_LANGUAGE = "math"

/**
 * #488 批2：同步初值着色的长度阈值。JVM 实测 buildSafeHighlightedAnnotatedString
 * ~1.3µs/char 线性（HighlightedCodeSyncCapProbeTest：459c=0.67ms / 1883c=2.5ms），
 * Android ×3 折算 400c≈2ms——主线程组合内可付；更大的块走
 * [CodeHighlightCache] 命中路径（重看零成本）或保持异步初值（首看 1-2 帧纯色）。
 * 流式围栏逐批重组也经此分支：≤400 字符的批次同步着色成本被 48ms 批节奏天然
 * 节流（≤20/s × 2ms ≈ 4% 主线程）。
 */
internal const val SYNC_HIGHLIGHT_CAP = 400

@Composable
internal fun SafeHighlightedCodeFence(
    content: String,
    node: ASTNode,
    style: TextStyle,
    theme: SyntaxTheme,
    /** #517：终末块距载体修剪——true 时外距 bottom 归零（内容→统计栏间隙与 user 侧对齐）。 */
    trimBottomGap: Boolean = false,
) {
    // #517 GapDiag：components 闭包捕获值取证（DEBUG-only 探针）
    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
        AppLogger.d("GapDiag", "FENCE trim=" + trimBottomGap + " contentLen=" + content.length)
    }
    MarkdownCodeFence(content, node, style) { code, language, codeStyle ->
        if (language.equals(MATH_FENCE_LANGUAGE, ignoreCase = true)) {
            SafeHighlightedMathBlock(code = code, style = codeStyle, trimBottomGap = trimBottomGap)
        } else {
            SafeHighlightedCode(code = code, language = language, style = codeStyle, theme = theme, trimBottomGap = trimBottomGap, showHeader = true)
        }
    }
}

@Composable
internal fun SafeHighlightedCodeBlock(
    content: String,
    node: ASTNode,
    style: TextStyle,
    theme: SyntaxTheme,
    /** #517 同 [SafeHighlightedCodeFence]。 */
    trimBottomGap: Boolean = false,
) {
    MarkdownCodeBlock(content, node, style) { code, language, codeStyle ->
        SafeHighlightedCode(code = code, language = language, style = codeStyle, theme = theme, trimBottomGap = trimBottomGap)
    }
}

@Composable
internal fun SafeHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    theme: SyntaxTheme,
    /** #517 同 [SafeHighlightedCodeFence]。 */
    trimBottomGap: Boolean = false,
    /** #488 批3①：语言标签+复制顶栏（库 MarkdownCodeBackground showHeader 透传）。
     *  仅围栏路径开（有语言）；缩进代码块（语言恒空）与数学块（自带徽标）不开。 */
    showHeader: Boolean = false,
) {
    // #517 GapDiag：叶子旗标取证（DEBUG-only 探针）
    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
        AppLogger.d("GapDiag", "SHC lang=" + (language ?: "-") + " len=" + code.length + " trim=" + trimBottomGap)
    }
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock
    // 初值 = 缓存命中（重看零成本）> 同步着色（≤CAP 小块首帧即着色）> 纯文本
    // （无空窗兜底，大块首看异步 1-2 帧纯色后着色并落缓存）。
    // 同步分支只在初值会被消费时执行（produceState 状态首次创建）——流式逐批
    // re-key 时 remember 重算的同步结果是纯浪费（状态不重置、初值不再读取），
    // consumed 旗标挡之；组合位销毁重建（滚动离开再回看）旗标随之复位。
    // SideEffect 提交后置位：组合中写状态会白付一次额外重组。
    var initialConsumed by remember { mutableStateOf(false) }
    val initialHighlighted: AnnotatedString = remember(code, language, theme) {
        initialHighlightedFor(code, language, theme, allowSync = !initialConsumed)
    }
    androidx.compose.runtime.SideEffect { initialConsumed = true }
    val codeHighlights: AnnotatedString by produceState(
        initialValue = initialHighlighted,
        key1 = code,
        key2 = language,
        key3 = theme,
    ) {
        // 跳过条件 = 同一性：value 就是本次键的初值实例且已着色（缓存命中/
        // 同步路径产物）→ 无重复作业。re-key（新批文本/主题切换/语言改写）时
        // value 残留上一键产物（produceState 跨键不重置）≠ 新初值实例 → 必然
        // 走作业——主题换色语义由此保全（旧实现「key 含 theme 重启换新色」
        // 的行为不得回归）。文本相等性守卫在此不可用：主题变了文本没变会
        // 误跳过、冻结旧色。
        if (value === initialHighlighted && value.spanStyles.isNotEmpty()) {
            return@produceState
        }
        val job = launch(Dispatchers.Default) {
            val t0 = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
            val v = buildSafeHighlightedAnnotatedString(code, language, theme)
            if (v.spanStyles.isNotEmpty()) {
                CodeHighlightCache.put(codeCacheKey(language, theme, code), v)
            }
            value = v
            if (BuildConfig.DEBUG) {
                AppLogger.d(
                    "CodeHL",
                    "len=" + code.length + " lang=" + (language ?: "-") +
                        " spans=" + v.spanStyles.size + " ms=" + (System.currentTimeMillis() - t0),
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
            // #517：bottom 外距承担块间分离（top 不变）；终末块（turn 尾）
            // 修剪归零——内容→统计栏间隙与 user 侧 4dp+行内基准对齐
            .padding(
                top = SpacingTokens.SM.dp,
                bottom = if (trimBottomGap) 0.dp else SpacingTokens.SM.dp,
            ),
        language = language,
        code = code,
        showHeader = showHeader,
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

/*
 * #488③（方案 c：降级 + 标注升级）：数学降级块渲染——
 * [transformMathFallback] 把完结消息的块级 $$…$$ / \[…\] 归一化为 ```math
 * 围栏，此处按 [MATH_FENCE_LANGUAGE] 精确识别（不误伤 AI 手写 tex 围栏——
 * 那仍走 [SafeHighlightedCode]，引擎无 tex 语言 → 纯文本等价）。
 *
 * 形态 = 代码块同款底壳（MarkdownCodeBackground）+「公式」徽标行
 * （i18n）+ 三角色轻着色等宽原文（真排版 KaTeX 级属远期）。
 * 数学变换只在完结渲染期发生（流式期无 math 围栏），静态内容同步构建 +
 * remember 即可——无 produceState 流式重启需求；键 = 参与着色的
 * colorScheme 角色（MarkdownContent 键纪律同款，主题切换重建换色）。
 */
@Composable
internal fun SafeHighlightedMathBlock(
    code: String,
    style: TextStyle,
    /** #517 同 [SafeHighlightedCode]。 */
    trimBottomGap: Boolean = false,
) {
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock
    val colorScheme = MaterialTheme.colorScheme
    val mathText = remember(
        code,
        colorScheme.tertiary, colorScheme.secondary, colorScheme.onSurfaceVariant,
    ) {
        buildMathAnnotatedString(
            code = code,
            commandColor = colorScheme.tertiary,
            braceColor = colorScheme.onSurfaceVariant,
            scriptColor = colorScheme.secondary,
        )
    }

    MarkdownCodeBackground(
        color = backgroundCodeColor,
        shape = RoundedCornerShape(codeBackgroundCornerSize),
        modifier = Modifier
            .fillMaxWidth()
            // #517 同 SafeHighlightedCode：终末块 bottom 外距修剪
            .padding(
                top = SpacingTokens.SM.dp,
                bottom = if (trimBottomGap) 0.dp else SpacingTokens.SM.dp,
            ),
        language = MATH_FENCE_LANGUAGE,
        code = code,
    ) {
        Column(modifier = Modifier.padding(codeBlockPadding)) {
            MarkdownBasicText(
                // 库的 String 重载是 internal——公共重载只收 AnnotatedString
                text = AnnotatedString(stringResource(R.string.math_block_badge)),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.padding(bottom = SpacingTokens.XS.dp),
            )
            MarkdownBasicText(
                text = mathText,
                style = style,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
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
 * #488 批2：produceState 初值策略（纯函数供 JVM 直测）。
 *
 * 优先级：缓存命中（重看零成本）> 同步着色（[allowSync]=首组合且 ≤CAP）> 纯文本
 * 兜底（无空窗）。同步产物回写缓存（下轮重看同享零成本）；空结果（未知语言）
 * 不入缓存（命中与 miss 不可区分且无重取价值）。键含 theme 身份防跨主题串色。
 */
internal fun initialHighlightedFor(
    code: String,
    language: String?,
    theme: SyntaxTheme,
    allowSync: Boolean,
): AnnotatedString {
    val cacheKey = codeCacheKey(language, theme, code)
    CodeHighlightCache.get(cacheKey)?.let { return it }
    if (allowSync && code.length <= SYNC_HIGHLIGHT_CAP) {
        buildSafeHighlightedAnnotatedString(code, language, theme)
            .takeIf { it.spanStyles.isNotEmpty() }
            ?.also { CodeHighlightCache.put(cacheKey, it) }
            ?.let { return it }
    }
    return AnnotatedString(text = code)
}

/**
 * 缓存键（初值查询与异步作业落缓存两处共用——手拼两份一旦漂移即静默 miss）。
 * theme 用实例身份：[CodeSyntaxTheme] 按 colorScheme 键控 remember，主题/AMOLED
 * 切换必然换实例（identityHashCode 碰撞率可忽略，LRU 兜底）。
 */
internal fun codeCacheKey(language: String?, theme: SyntaxTheme, code: String): String =
    "$language|${System.identityHashCode(theme)}|$code"

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

/**
 * 数学块轻着色（纯函数，JVM 可单测）——#488③ 方案 c。
 *
 * 三角色（与 [toCodeSyntaxTheme] 令牌映射同哲学）：
 *  - `\命令`（反斜杠 + 连续字母，整段含反斜杠）→ tertiary：LaTeX 控制
 *    序列是公式的「关键字」；
 *  - 花括号 `{`/`}` 单字符 → onSurfaceVariant：分组结构弱化呈现；
 *  - `^`/`_` 单字符 → secondary：上下标钩子。
 * `\` 后非字母（`\,` `\%` `\\`）为 LaTeX 转义字面量，保持原色
 * （highlights 引擎无 tex 语言，此处手写——区间由本扫描器顺序产出，
 * 天然非重叠、界内，无引擎区间守卫需求）。
 */
internal fun buildMathAnnotatedString(
    code: String,
    commandColor: Color,
    braceColor: Color,
    scriptColor: Color,
): AnnotatedString = buildAnnotatedString {
    append(code)
    var i = 0
    val n = code.length
    while (i < n) {
        val c = code[i]
        when {
            c == '\\' && i + 1 < n && code[i + 1].isLetter() -> {
                var j = i + 1
                while (j < n && code[j].isLetter()) j++
                addStyle(SpanStyle(color = commandColor), i, j)
                i = j
            }
            c == '{' || c == '}' -> {
                addStyle(SpanStyle(color = braceColor), i, i + 1)
                i++
            }
            c == '^' || c == '_' -> {
                addStyle(SpanStyle(color = scriptColor), i, i + 1)
                i++
            }
            else -> i++
        }
    }
}
