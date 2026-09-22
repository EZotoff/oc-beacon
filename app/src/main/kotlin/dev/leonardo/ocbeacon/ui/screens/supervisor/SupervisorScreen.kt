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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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

@Composable
fun SupervisorRoute(
    onNavigateBack: () -> Unit,
    viewModel: SupervisorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SupervisorScreen(state = state, onNavigateBack = onNavigateBack, onRefresh = viewModel::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupervisorScreen(
    state: SupervisorUiState,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
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
                actions = {
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
                state.snapshot != null -> Digest(state.snapshot)
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
private fun Digest(snapshot: SupervisorSnapshot) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(SpacingTokens.LG.dp),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp),
    ) {
        item { GlanceHeader(snapshot) }
        item { SectionTitle(R.string.supervisor_open_items) }
        if (snapshot.attentionItems.isEmpty()) item { EmptyText(R.string.supervisor_no_open_items) }
        items(snapshot.attentionItems, key = { it.id }) { AttentionCard(it) }
        item { SectionTitle(R.string.supervisor_recent_decisions) }
        if (snapshot.recentDecisions.isEmpty()) item { EmptyText(R.string.supervisor_no_decisions) }
        items(snapshot.recentDecisions, key = { "${it.decidedAt}:${it.action}:${it.project}" }) { DecisionCard(it) }
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
private fun AttentionCard(item: SupervisorAttentionItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(SpacingTokens.MD.dp)) {
            Text(item.question, style = MaterialTheme.typography.titleMedium)
            Text("${project(item.project)} · ${age(item.createdAt)}", style = MaterialTheme.typography.bodySmall)
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
private fun SectionTitle(resource: Int) = Text(stringResource(resource), style = MaterialTheme.typography.titleLarge)

@Composable
private fun EmptyText(resource: Int) = Text(stringResource(resource), color = MaterialTheme.colorScheme.onSurfaceVariant)
