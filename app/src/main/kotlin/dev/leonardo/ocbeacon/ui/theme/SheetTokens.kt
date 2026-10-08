package dev.leonardo.ocbeacon.ui.theme

/**
 * 主对话抽屉（ModalBottomSheet）高度规格令牌。
 *
 * 2026-08-20 用户决策：主对话内所有抽屉屏占比高度保持一致——最小/最大
 * 高度统一为屏高 75%（固定高度：内容少时底部留白、不塌缩；内容多时
 * 内部滚动）。取代 2026-08-16「只设 75% 上限、内容自然收缩」方案。
 *
 * 用法：抽屉内容根 Column
 * `Modifier.height(LocalConfiguration.current.screenHeightDp.dp * SheetTokens.ChatSheetHeightFraction)`
 * （配合内部列表 weight(1f) 保证超长内容在固定高度内滚动；
 * ModalBottomSheet 需传 rememberModalBottomSheetState(skipPartiallyExpanded = true)
 * 避免固定高度下先落在半展开锚点）。
 */
object SheetTokens {
    /** 主对话抽屉统一高度占屏比（min = max = 该比例 → 固定高度）。 */
    const val ChatSheetHeightFraction = 0.75f

    /**
     * 布局内 sheet（#493）scrim 透明度——与 M3 1.4.0 ModalBottomSheet
     * 默认 scrim 同值（ScrimTokens.containerColor @ 0.32f，反编译实证）。
     */
    const val ScrimAlpha = 0.32f

    /** 布局内 sheet（#493）手柄拖拽收起判定：位移超过面板高的该比例松手即收。 */
    const val DragDismissFraction = 0.3f
}
