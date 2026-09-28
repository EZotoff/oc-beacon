package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 紧凑标签（2026-08-12 用户确认：与输入组件 AgentModelVariantSelector 的
 * agent 选择器**完全同款**——不是 M3 Chip（Chip 有固定高度/8dp 圆角/行高，
 * 视觉与输入组件不一致）。
 *
 * 本组件**高度自适应**：由内容 + padding 决定（不固定高度），
 * 与同行内容（时间/模型文本）自然对齐。
 * 实现：clip(小圆角) + 淡色底 + labelSmall 文字（与输入组件逐项一致）。
 *
 * 使用点：agent 徽章（统计栏/后台面板）、QUEUED 状态徽章。
 */
@Composable
internal fun CompactTag(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    // 2026-08-12：默认 null = 保持 labelSmall 原生字号（11sp），与输入组件
    // AgentModelVariantSelector 完全一致；特殊场景（QUEUED 8sp）显式传值。
    fontSize: Int? = null,
    shape: RoundedCornerShape = ShapeTokens.smallMedium,
    // 2026-08-16（agent 徽标可点击）：非空时标签可点（涟漪限定标签区域）。
    // 此前消息流 agent 徽标纯静态 Box（从未有过点击行为），与输入栏同款
    // 视觉却不可点造成感知错位——点击 = 选中该 agent 到输入栏（影响下次发送）。
    onClick: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(containerColor)
            .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.XS.dp)
    ) {
        val baseStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = fontWeight)
        Text(
            text = text,
            style = if (fontSize != null) baseStyle.copy(fontSize = fontSize.sp) else baseStyle,
            color = contentColor
        )
    }
}

/**
 * agent 名称标签（统计栏信息簇）。
 * agentColor 提供语义色（不同 agent 不同色相）。
 *
 * 2026-09-27 用户裁决：去实底背景改**边框**形态——消息层扁平化后正文/
 * 统计栏皆无背景，实底徽标与周遭不协调；色相语义保留在边框 + 文字色。
 * （原「与输入组件 AgentModelVariantSelector 完全同款」的实底形态退役。）
 */
@Composable
internal fun AgentTag(
    agent: String,
    tagColor: Color,
    modifier: Modifier = Modifier,
    // 2026-08-16（agent 徽标可点击）：点击 = 选中该 agent 到输入栏
    onClick: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(ShapeTokens.smallMedium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .border(
                width = 1.dp,
                color = tagColor.copy(alpha = AlphaTokens.MUTED),
                shape = ShapeTokens.smallMedium,
            )
            .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.XS.dp)
    ) {
        Text(
            text = agent.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = tagColor,
        )
    }
}
