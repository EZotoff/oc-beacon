package dev.leonardo.ocbeacon.ui.screens.supervisor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.domain.model.SupervisorDecision
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import java.time.Duration
import java.time.Instant

enum class SupervisorDestination {
    OPEN_ITEMS,
    DECISIONS_LOG,
}

@Composable
fun SupervisorRoute(
    destination: SupervisorDestination,
    onNavigateBack: () -> Unit,
    onNavigateToOtherDestination: () -> Unit,
    viewModel: SupervisorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SupervisorScreen(
        state = state,
        destination = destination,
        onNavigateBack = onNavigateBack,
        onNavigateToOtherDestination = onNavigateToOtherDestination,
        onRefresh = viewModel::refresh,
        onReply = viewModel::sendReply,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupervisorScreen(
    state: SupervisorUiState,
    destination: SupervisorDestination,
    onNavigateBack: () -> Unit,
    onNavigateToOtherDestination: () -> Unit,
    onRefresh: () -> Unit,
    onReply: (SupervisorAttentionItem, String) -> Unit = { _, _ -> },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (destination == SupervisorDestination.OPEN_ITEMS) {
                                R.string.supervisor_open_items
                            } else {
                                R.string.supervisor_recent_decisions
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onNavigateToOtherDestination) {
                        Text(
                            stringResource(
                                if (destination == SupervisorDestination.OPEN_ITEMS) {
                                    R.string.supervisor_recent_decisions
                                } else {
                                    R.string.supervisor_open_items
                                }
                            )
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.workspace_refresh))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.loadFailed && state.snapshot == null -> LoadFailure(onRefresh)
                state.snapshot != null -> when (destination) {
                    SupervisorDestination.OPEN_ITEMS -> OpenItems(state, onReply)
                    SupervisorDestination.DECISIONS_LOG -> DecisionsLog(state)
                }
            }
        }
    }
}

@Composable
private fun LoadFailure(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.supervisor_load_failed))
        AssistChip(onClick = onRetry, label = { Text(stringResource(R.string.retry)) })
    }
}

@Composable
private fun OpenItems(
    state: SupervisorUiState,
    onReply: (SupervisorAttentionItem, String) -> Unit,
) {
    val snapshot = checkNotNull(state.snapshot)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(SpacingTokens.LG.dp),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp),
    ) {
        item { GlanceHeader(snapshot) }
        if (state.healthContext) item { HealthContextHeader() }
        if (state.openItems.isEmpty()) item { EmptyText(R.string.supervisor_no_open_items) }
        items(state.openItems, key = { it.id }) { item ->
            AttentionCard(
                item = item,
                replyInFlight = item.id in state.replyInFlight,
                replyFailed = item.id in state.replyFailed,
                onReply = onReply,
            )
        }
    }
}

@Composable
private fun HealthContextHeader() {
    // root 健康通知落地：明确标注这是健康上下文，不是某个问题卡。
    Card(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.supervisor_health_context),
            modifier = Modifier.padding(SpacingTokens.MD.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DecisionsLog(state: SupervisorUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(SpacingTokens.LG.dp),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp),
    ) {
        if (state.decisions.isEmpty()) item { EmptyText(R.string.supervisor_no_decisions) }
        items(state.decisions, key = { "${it.decidedAt}:${it.action}:${it.project}" }) { DecisionCard(it) }
    }
}

@Composable
private fun GlanceHeader(snapshot: SupervisorSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SpacingTokens.LG.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
        ) {
            MetricRow(
                stringResource(R.string.supervisor_roots, snapshot.rootsMonitored),
                stringResource(R.string.supervisor_failing, snapshot.rootsFailing),
            )
            MetricRow(
                stringResource(R.string.supervisor_error_peak, snapshot.errorsPeak),
                stringResource(R.string.supervisor_open_count, snapshot.attentionItems.size),
            )
        }
    }
}

@Composable
private fun MetricRow(first: String, second: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
        AssistChip(onClick = {}, label = { Text(first) }, modifier = Modifier.weight(1f))
        AssistChip(onClick = {}, label = { Text(second) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AttentionCard(
    item: SupervisorAttentionItem,
    replyInFlight: Boolean,
    replyFailed: Boolean,
    onReply: (SupervisorAttentionItem, String) -> Unit,
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var draft by rememberSaveable(item.id) { mutableStateOf("") }
    // 发送成功（inFlight 熄灭且未失败）即清空草稿。
    LaunchedEffect(item.id, replyInFlight, replyFailed) {
        if (!replyInFlight && !replyFailed) draft = ""
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(SpacingTokens.MD.dp)) {
            // AR-glance minimal card: single-line ellipsized headline only.
            // Full detail remains in the backend queue (queue.json) — never on the card.
            Text(
                item.question,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${project(item.project)} · ${age(item.createdAt)}", style = MaterialTheme.typography.bodySmall)
            if (item.root.isNotBlank()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.supervisor_reply_collapse else R.string.supervisor_reply_expand
                        )
                    )
                }
                if (expanded) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !replyInFlight,
                        placeholder = { Text(stringResource(R.string.supervisor_reply_hint)) },
                        trailingIcon = {
                            IconButton(
                                onClick = { onReply(item, draft) },
                                enabled = !replyInFlight && draft.isNotBlank(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.supervisor_reply_send))
                            }
                        },
                        singleLine = false,
                        maxLines = 4,
                    )
                    if (replyFailed) {
                        Text(
                            stringResource(R.string.supervisor_reply_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DecisionCard(decision: SupervisorDecision) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(SpacingTokens.MD.dp), verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
            Text(decision.action, color = actionColor(decision.action), style = MaterialTheme.typography.labelLarge)
            Text(decision.rationale, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
            Text("${project(decision.project)} · ${age(decision.decidedAt)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun project(value: String): String = value.ifBlank { stringResource(R.string.supervisor_unknown_project) }

@Composable
private fun actionColor(action: String): Color = when (action) {
    "ESCALATE", "REFORMULATE" -> MaterialTheme.colorScheme.error
    "STEER", "CONTINUE" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

private fun age(timestamp: String): String {
    val minutes = runCatching { Duration.between(Instant.parse(timestamp), Instant.now()).toMinutes().coerceAtLeast(0) }
        .getOrDefault(0)
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 1_440 -> "${minutes / 60}h"
        else -> "${minutes / 1_440}d"
    }
}

@Composable
private fun EmptyText(resource: Int) = Text(stringResource(resource), color = MaterialTheme.colorScheme.onSurfaceVariant)
