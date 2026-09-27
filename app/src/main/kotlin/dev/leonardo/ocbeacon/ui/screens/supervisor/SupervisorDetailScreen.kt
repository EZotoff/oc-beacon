package dev.leonardo.ocbeacon.ui.screens.supervisor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import java.time.Duration
import java.time.Instant

/**
 * 单事项 Detail（glance → dive → answer 的 dive/answer 终点）：
 * push 点击与卡片点击都落在这里——上下文先行，承诺（回复）在后。
 *
 * 五节诚实视图 + Answer 节（操作员决定 2026-09-25：卡片回复输入迁入此处）。
 * 不提供 Open session（supervisor 尚未发布 sessionID）；无 dismiss、无其他写路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorDetailRoute(
    onNavigateBack: () -> Unit,
    onNavigateToOpenItems: () -> Unit,
    viewModel: SupervisorDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SupervisorDetailScreen(
        state = state,
        onNavigateBack = onNavigateBack,
        onNavigateToOpenItems = onNavigateToOpenItems,
        onReply = viewModel::sendReply,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupervisorDetailScreen(
    state: SupervisorDetailUiState,
    onNavigateBack: () -> Unit,
    onNavigateToOpenItems: () -> Unit,
    onReply: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.supervisor_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            state.itemMissing -> MissingContent(onNavigateToOpenItems)
            else -> DetailContent(state, onReply, onNavigateBack, Modifier.padding(padding))
        }
    }
}

@Composable
private fun MissingContent(onNavigateToOpenItems: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(SpacingTokens.LG.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.supervisor_detail_missing))
        TextButton(onClick = onNavigateToOpenItems) {
            Text(stringResource(R.string.supervisor_open_items))
        }
    }
}

@Composable
private fun DetailContent(
    state: SupervisorDetailUiState,
    onReply: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = checkNotNull(state.item)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingTokens.LG.dp),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp),
    ) {
        if (state.stale) {
            Text(
                stringResource(R.string.supervisor_stale_banner),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        // 冻结镜像灰显：内容可读但不可交互（含 Answer 输入）。
        val contentColor = if (state.stale) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        Section(title = R.string.supervisor_detail_section_what_happened) {
            Text(
                item.reasonText,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${projectLabel(item.project)} · ${item.sessionLabel} · ${age(item.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
            )
        }

        Section(title = R.string.supervisor_detail_section_judgment) {
            Text(
                stringResource(R.string.supervisor_detail_summary_label),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
            Text(item.reasonText, style = MaterialTheme.typography.bodyMedium, color = contentColor)
        }

        Section(title = R.string.supervisor_detail_section_why) {
            if (item.premiseTexts.isEmpty()) {
                Text(
                    stringResource(R.string.supervisor_no_premises),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            } else {
                item.premiseTexts.forEach { premise ->
                    Text("• $premise", style = MaterialTheme.typography.bodyMedium, color = contentColor)
                }
            }
        }

        Section(title = R.string.supervisor_detail_section_evidence) {
            Text(
                stringResource(R.string.supervisor_detail_evidence_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        }

        Section(title = R.string.supervisor_detail_section_next_step) {
            // 无 sessionID 可跳（Phase 2 之前不臆造）——回到 Open Items 是唯一去向。
            TextButton(onClick = onNavigateBack, enabled = !state.stale) {
                Text(stringResource(R.string.supervisor_open_items))
            }
        }

        AnswerSection(state, item.id, onReply, contentColor)
    }
}

/** Answer 节：卡片回复 UI（TextField + 发送 + in-flight/失败态）原样迁入。 */
@Composable
private fun AnswerSection(
    state: SupervisorDetailUiState,
    itemId: String,
    onReply: (String) -> Unit,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    var draft by rememberSaveable(itemId) { mutableStateOf("") }
    // 发送成功（inFlight 熄灭且未失败）即清空草稿。
    LaunchedEffect(itemId, state.replyInFlight, state.replyFailed) {
        if (!state.replyInFlight && !state.replyFailed) draft = ""
    }
    Section(title = R.string.supervisor_detail_section_answer) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.replyInFlight && !state.stale,
            placeholder = { Text(stringResource(R.string.supervisor_reply_hint)) },
            trailingIcon = {
                IconButton(
                    onClick = { onReply(draft) },
                    enabled = !state.replyInFlight && !state.stale && draft.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.supervisor_reply_send))
                }
            },
            singleLine = false,
            maxLines = 4,
        )
        if (state.replyFailed) {
            Text(
                stringResource(R.string.supervisor_reply_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SpacingTokens.MD.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
        ) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun projectLabel(value: String): String =
    value.ifBlank { stringResource(R.string.supervisor_unknown_project) }

private fun age(timestamp: String): String {
    val minutes = runCatching {
        Duration.between(Instant.parse(timestamp), Instant.now()).toMinutes().coerceAtLeast(0)
    }.getOrDefault(0)
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 1_440 -> "${minutes / 60}h"
        else -> "${minutes / 1_440}d"
    }
}
