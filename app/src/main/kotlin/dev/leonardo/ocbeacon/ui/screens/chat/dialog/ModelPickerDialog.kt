package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ModelCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.ui.components.ProviderIcon
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.ItemTokens
import dev.leonardo.ocbeacon.ui.screens.chat.components.SmallSheetDragHandle
import dev.leonardo.ocbeacon.ui.theme.SheetTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 模型选择抽屉（2026-08-21 #187/#188 重做：variant 行内 accordion 二级面板）。
 *
 * 结构（grilling 定案 ①②③ + Q10；2026-08-22 用户复改）：
 * - 模型行：名称 + 选中勾（右对齐簇最左端）+ Free 标签 + 星标（点击 =
 *   设置/取消默认模型 toggle）+ chevron（行内 accordion 开关，无 variants
 *   模型也统一显示——Q10；行尾恒定）；
 *   行点击=快速选中该模型（默认 variant）并关闭；
 * - 二级面板（chevron 展开）：variant pills（含「默认」档；点选=带 variant
 *   选中并关闭）；
 * - #188：defaultModel 由调用方以响应式状态传入（DataStore 写入后即时回显）。
 *
 * #493（2026-09-30 用户裁决「基准=不收键盘」）：容器从 ModalBottomSheet
 * （独立 dialog 窗口，打开必夺主窗口焦点→IME 收起）换为**布局内非模态
 * overlay**——主窗口组合树内直接叠加（挂 ChatScreen 根 Box，z 序在 Scaffold
 * 之上），无独立窗口 → 不夺焦点，键盘保持。
 * 保留语义：返回键关闭（BackHandler）、点外关闭（自绘 scrim tap）、scrim、
 * 75% 固定高（SheetTokens.ChatSheetHeightFraction）、手柄拖拽收起（自实现
 * anchoredDrag 等价：跟手位移+越 30% 高松手收起）。
 * #379/#405 手势隔离语义自动满足：无 M3 sheet anchoredDrag 检测器后，内容/
 * 非滚动区拖拽天然不致收起（原两个 modifier 仅为拦截 sheet 拖拽而设）。
 *
 * 取证备注（2026-10-08 真机 houji/MIUI）：Popup(focusable=false) 子窗口候选
 * 先试被否——窗口在场+组合三打点全出（composed/call site/content）但表面
 * 零绘制（wrap 蓝面 0 像素、scrim 0 像素、a11y 树无节点），故换布局内方案。
 */
@Composable
internal fun ModelPickerDialog(
    providers: List<ProviderCatalog>,
    selectedProviderId: String?,
    selectedModelId: String?,
    /** #187：variant 感知选择——null=默认档。 */
    onSelect: (providerId: String, modelId: String, variant: String?) -> Unit,
    onDismiss: () -> Unit,
    /** #188：当前本地默认模型（"pid|mid"），null=未设；须为响应式状态。 */
    defaultModel: String? = null,
    /** 点开关设置/取消默认模型（toggle 语义）。 */
    onSetDefault: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    /** 2026-08-16（管理入口）：跳转服务器模型管理页（开关/搜索） */
    onManageModels: () -> Unit = {},
    /** #187：当前选中 variant（选中模型的 pill 高亮）。 */
    selectedVariant: String? = null,
) {
    val isAmoled = isAmoledTheme()
    val params = amoledDialogParams(shape = ShapeTokens.largeMedium)

    fun isModelFree(providerId: String, model: ModelCatalog): Boolean {
        if (providerId != "opencode") return false
        return model.costInput == 0.0
    }

    // 提供商排序："opencode" 在前，然后按名称排序
    val sortedProviders = remember(providers) {
        providers
            .filter { it.models.isNotEmpty() }
            .sortedWith(compareBy<ProviderCatalog> { it.id != "opencode" }.thenBy { it.name.lowercase() })
    }

    // #187：行内 accordion 展开状态（"pid|mid" → expanded）
    val expandedModels = remember { mutableStateMapOf<String, Boolean>() }

    // #493：出/入场动画状态——收起先播滑出动画，动画落定再回调 onDismiss
    //（调用方此刻才移除组合）。首组合即入场。
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    fun dismiss() {
        visibleState.targetState = false
    }
    LaunchedEffect(visibleState.currentState, visibleState.targetState) {
        if (!visibleState.currentState && !visibleState.targetState) onDismiss()
    }
    // #493：返回键关闭（Popup focusable=false 收不到 key 事件，走组合级 BackHandler）
    BackHandler(enabled = visibleState.targetState) { dismiss() }

    // 2026-08-20（用户决策）：主对话抽屉高度统一——min = max = 75% 屏高
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * SheetTokens.ChatSheetHeightFraction
    val density = LocalDensity.current
    val sheetHeightPx = remember(sheetHeight) { with(density) { sheetHeight.toPx() } }
    val scope = rememberCoroutineScope()
    // #493：手柄拖拽收起——sheet 面跟手下移，松手越 30% 高收起、否则弹回
    val dragOffset = remember { Animatable(0f) }

    // 2026-08-12 用户要求：模型选择改为抽屉式；#493 换布局内非模态 overlay
    //（见文件头取证备注——本组件根即全屏层，须挂在全屏容器内）
    Box(modifier = Modifier.fillMaxSize()) {
            // scrim（点外关闭）
            androidx.compose.animation.AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(180)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 与 M3 1.4.0 ModalBottomSheet 默认 scrim 同值（ScrimTokens.containerColor @ 0.32f）
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .pointerInput(Unit) {
                            detectTapGestures { dismiss() }
                        },
                )
            }
            // sheet 面（底部对齐 + 拖拽跟手位移）
            androidx.compose.animation.AnimatedVisibility(
                visibleState = visibleState,
                enter = slideInVertically(animationSpec = tween(220)) { it } +
                    fadeIn(animationSpec = tween(150)),
                exit = slideOutVertically(animationSpec = tween(180)) { it } +
                    fadeOut(animationSpec = tween(120)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                        .height(sheetHeight)
                        .clip(params.shape)
                        .background(params.containerColor),
                ) {
                    // #379：统一小样式手柄；#493 起手柄槽自承接拖拽收起
                    Box(
                        modifier = Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    scope.launch {
                                        dragOffset.snapTo(
                                            (dragOffset.value + amount).coerceAtLeast(0f)
                                        )
                                    }
                                },
                                onDragEnd = {
                                    if (dragOffset.value > sheetHeightPx * 0.3f) dismiss()
                                    else scope.launch { dragOffset.animateTo(0f) }
                                },
                            )
                        },
                    ) {
                        SmallSheetDragHandle()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.a11y_icon_select_model),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onManageModels) {
                            Text(stringResource(dev.leonardo.ocbeacon.R.string.chat_manage_models))
                        }
                        IconButton(onClick = { dismiss() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .navigationBarsPadding()
                            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.XS.dp)
                    ) {
                        for ((index, provider) in sortedProviders.withIndex()) {
                            val sortedModels = provider.models.values
                                .sortedWith(compareBy<ModelCatalog> { !isModelFree(provider.id, it) }.thenBy { it.name.lowercase() })

                            item(key = "provider_header_" + provider.id) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = ItemTokens.MinHeightDense.dp)
                                        .padding(start = SpacingTokens.XS.dp, end = SpacingTokens.MD.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
                                ) {
                                    ProviderIcon(
                                        providerId = provider.id,
                                        size = 18.dp,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                                    )
                                    Text(
                                        text = provider.name.ifEmpty { provider.id },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            items(
                                sortedModels,
                                key = { "model_" + provider.id + "_" + it.id }
                            ) { model ->
                                val isSelected = provider.id == selectedProviderId && model.id == selectedModelId
                                val modelKey = provider.id + "|" + model.id
                                val isExpanded = expandedModels[modelKey] == true
                                val isDefault = defaultModel == modelKey
                                Column {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = ItemTokens.MinHeightDense.dp)
                                            .clip(ShapeTokens.small)
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = AlphaTokens.MUTED)
                                                else Color.Transparent
                                            )
                                            .clickable {
                                                onSelect(provider.id, model.id, null)
                                                dismiss()
                                            }
                                            .padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.SM.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = model.name.ifEmpty { model.id },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        // 2026-08-22 用户决策：对钩放右对齐簇最左端（名称后）——
                                        // 行尾恒为 chevron，选中态不改变尾图标位置
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = stringResource(R.string.a11y_icon_select_model),
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        if (isModelFree(provider.id, model)) {
                                            Text(
                                                text = stringResource(R.string.chat_free_label),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = AlphaTokens.HIGH),
                                                modifier = Modifier.padding(start = SpacingTokens.SM.dp),
                                            )
                                        }
                                        // 2026-08-22 用户决策：星标恢复点击=设置/取消默认模型
                                        //（#187 曾收编为面板开关，现按用户要求改回星标 toggle）
                                        IconButton(
                                            onClick = { onSetDefault(provider.id, model.id) },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (isDefault) Icons.Filled.Star else Icons.Outlined.Star,
                                                contentDescription = stringResource(dev.leonardo.ocbeacon.R.string.chat_default_model),
                                                modifier = Modifier.size(16.dp),
                                                tint = if (isDefault) MaterialTheme.colorScheme.tertiary
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                                            )
                                        }
                                        // #187 ① + Q10：chevron 行内 accordion（无 variants 也显示）
                                        IconButton(
                                            onClick = {
                                                expandedModels[modelKey] = !isExpanded
                                            },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = stringResource(
                                                    if (isExpanded) R.string.a11y_icon_collapse else R.string.a11y_icon_expand
                                                ),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                            )
                                        }
                                    }
                                    // #187 ②：二级面板——variant pills
                                    AnimatedVisibility(
                                        visible = isExpanded,
                                        enter = fadeIn() + expandVertically(),
                                        exit = fadeOut() + shrinkVertically(),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = SpacingTokens.MD.dp, end = SpacingTokens.XS.dp, bottom = SpacingTokens.SM.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
                                            ) {
                                                FilterChip(
                                                    selected = isSelected && selectedVariant == null,
                                                    onClick = {
                                                        onSelect(provider.id, model.id, null)
                                                        dismiss()
                                                    },
                                                    label = { Text(stringResource(R.string.chat_default_variant)) },
                                                )
                                                model.variantNames.sorted().forEach { variant ->
                                                    FilterChip(
                                                        selected = isSelected && selectedVariant == variant,
                                                        onClick = {
                                                            onSelect(provider.id, model.id, variant)
                                                            dismiss()
                                                        },
                                                        label = { Text(variant.replaceFirstChar { it.uppercase() }) },
                                                    )
                                                }
                                            }
                                            // 默认模型开关已删（2026-08-22 用户决策：星标点击 toggle）
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
}
