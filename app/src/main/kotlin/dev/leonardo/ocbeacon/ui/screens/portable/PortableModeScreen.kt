package dev.leonardo.ocbeacon.ui.screens.portable

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.data.api.voice.SelectionKind
import dev.leonardo.ocbeacon.data.api.voice.ShowView
import dev.leonardo.ocbeacon.data.api.voice.ViewContextView
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.ui.components.voice.*
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlin.math.abs

@Composable
fun PortableModeScreen(
    connectedServerIds: Set<String>,
    onBack: () -> Unit,
    onOpenSession: (String, String) -> Unit,
    onOpenAttention: (String, String) -> Unit,
    voice: VoiceViewModel,
    viewModel: PortableModeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connection by voice.connection.collectAsStateWithLifecycle()
    val show by voice.showFrame.collectAsStateWithLifecycle()
    var presentation by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var attentionPage by rememberSaveable { mutableIntStateOf(0) }
    var selectedLabel by rememberSaveable { mutableStateOf("") }
    val currentView = if (presentation && show?.view == ShowView.Known.COMPARISON) ViewContextView.COMPARISON else ViewContextView.SUPERVISOR
    DisposableEffect(connectedServerIds) {
        viewModel.observeServers(connectedServerIds)
        onDispose { viewModel.stopObserving() }
    }
    LaunchedEffect(show) {
        if (show != null) presentation = true
        page = 0
        selectedLabel = ""
        viewModel.clearShowSelection()
    }
    LaunchedEffect(presentation) { if (!presentation) viewModel.clearShowSelection() }
    LaunchedEffect(state.project, state.session, connection, currentView, presentation) {
        viewModel.emitContext(currentView)
    }
    BackHandler(presentation) { presentation = false }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().fillMaxSize().padding(SpacingTokens.LG.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (presentation) presentation = false else onBack() }) {
                    Text(stringResource(R.string.portable_back))
                }
                Text(stringResource(R.string.portable_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(
                Modifier.widthIn(max = 360.dp).fillMaxWidth().weight(1f).pointerInput(Unit) {
                    var drag = Offset.Zero
                    detectDragGestures(
                        onDragStart = { drag = Offset.Zero },
                        onDrag = { change, amount -> change.consume(); drag += amount },
                        onDragEnd = {
                            val threshold = 48.dp.toPx()
                            if (maxOf(abs(drag.x), abs(drag.y)) >= threshold) {
                                if (abs(drag.x) > abs(drag.y)) viewModel.pageProject(if (drag.x < 0) 1 else -1)
                                else viewModel.pageSession(if (drag.y < 0) 1 else -1)
                                presentation = false
                            }
                        },
                    )
                }, verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
            ) {
                ProjectPosition(state, viewModel::pageProject)
                if (presentation && show != null) {
                    ShortLine("> ${state.session?.title ?: stringResource(R.string.portable_no_sessions)}", selected = true)
                    val original = checkNotNull(show)
                    val bounded = PortableShowPage.of(original, page)
                    ShowViewCard(bounded.frame, largeTargets = true, onSelect = { tag, index ->
                        val label = when (original.view) {
                            ShowView.Known.TABLE -> entryText(elements(original.payload, "rows").getOrNull(index + bounded.offset))
                            ShowView.Known.CHOICE -> entryText(elements(original.payload, "options").getOrNull(index + bounded.offset))
                            else -> entryText(elements(original.payload, "items").getOrNull(index + bounded.offset))
                        }.ifBlank { "${index + bounded.offset + 1}" }
                        selectedLabel = label
                        viewModel.selectShow(tag, index + bounded.offset, label,
                            if (original.view == ShowView.Known.CHOICE) SelectionKind.OPTION else SelectionKind.ROW, currentView)
                    })
                    if (selectedLabel.isNotEmpty()) ShortLine(stringResource(R.string.portable_selected, selectedLabel), selected = true)
                    if (bounded.pages > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { page-- }, enabled = page > 0) { Text(stringResource(R.string.portable_previous)) }
                        Text("${page + 1}/${bounded.pages}", modifier = Modifier.weight(1f))
                        TextButton(onClick = { page++ }, enabled = page < bounded.pages - 1) { Text(stringResource(R.string.portable_next)) }
                    }
                    TextButton(onClick = { presentation = false }) { Text(stringResource(R.string.portable_current)) }
                } else {
                    Text(stringResource(R.string.portable_needs_you, state.attention.size), style = MaterialTheme.typography.labelLarge)
                    if (state.unavailable) ShortLine(stringResource(R.string.portable_unavailable))
                    if (state.attention.isEmpty() && !state.unavailable) ShortLine(stringResource(R.string.portable_no_attention))
                    val attentionPages = ((state.attention.size + 1) / 2).coerceAtLeast(1)
                    val start = attentionPage.coerceIn(0, attentionPages - 1) * 2
                    state.attention.drop(start).take(2).forEach { item ->
                        OutlinedCard(onClick = {
                            viewModel.selectAttention(item)
                            onOpenAttention(item.serverId, item.item.id)
                        }, enabled = !item.stale, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Column(Modifier.padding(SpacingTokens.SM.dp)) {
                                ShortLine("${item.item.project} / ${item.item.sessionLabel}")
                                ShortLine(if (item.stale) stringResource(R.string.portable_stale) else
                                    stringResource(R.string.portable_waiting))
                            }
                        }
                    }
                    if (attentionPages > 1) TextButton(onClick = { attentionPage = (attentionPage + 1) % attentionPages }) {
                        Text(stringResource(R.string.portable_more_attention, start / 2 + 1, attentionPages))
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.portable_current), style = MaterialTheme.typography.labelLarge)
                    SessionPosition(state, viewModel::pageSession)
                    val selected = state.session
                    ShortLine(stringResource(when (selected?.status) {
                        SessionStatus.Busy -> R.string.portable_running
                        SessionStatus.Asking -> R.string.portable_waiting
                        SessionStatus.Idle -> R.string.portable_idle
                        is SessionStatus.Retry -> R.string.portable_retrying
                        null -> R.string.portable_unknown
                    }), selected = true)
                    if (selected != null) {
                        ShortLine(stringResource(R.string.portable_updated,
                            ((System.currentTimeMillis() - selected.updated).coerceAtLeast(0L) / 60_000)))
                        Button(onClick = {
                            viewModel.emitContext(ViewContextView.SESSION)
                            state.project?.let { onOpenSession(it.serverId, selected.id) }
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.portable_enter)) }
                    }
                    Row {
                        TextButton(onClick = viewModel::previous, enabled = state.selection.previous != null) { Text(stringResource(R.string.portable_previous)) }
                        if (show != null) TextButton(onClick = { presentation = true }) { Text(stringResource(R.string.portable_show)) }
                    }
                }
            }
            VoiceWidget(viewModel = voice, showPresentation = false,
                beforePtt = { viewModel.emitContext(currentView) }, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth())
        }
    }
}

@Composable
private fun ProjectPosition(state: PortableUiState, onPage: (Int) -> Unit) {
    val index = state.projects.indexOf(state.project)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPage(-1) }, enabled = state.projects.size > 1) { Text("←") }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
            if (state.projects.size > 1) Text(state.projects[Math.floorMod(index - 1, state.projects.size)].label,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.project?.label?.ifBlank { stringResource(R.string.portable_unassigned) }
                ?: stringResource(R.string.portable_no_projects), modifier = Modifier.weight(1.5f),
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.projects.size > 1) Text(state.projects[(index + 1) % state.projects.size].label,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { onPage(1) }, enabled = state.projects.size > 1) { Text("→") }
    }
}

@Composable
private fun SessionPosition(state: PortableUiState, onPage: (Int) -> Unit) {
    val sessions = state.project?.sessions.orEmpty()
    val index = sessions.indexOf(state.session)
    if (sessions.size > 1) TextButton(onClick = { onPage(-1) }) {
        ShortLine("↑ ${sessions[Math.floorMod(index - 1, sessions.size)].title}")
    }
    ShortLine("> ${state.session?.title ?: stringResource(R.string.portable_no_sessions)}", selected = true)
    if (sessions.size > 1) TextButton(onClick = { onPage(1) }) {
        ShortLine("↓ ${sessions[(index + 1) % sessions.size].title}")
    }
}

@Composable
private fun ShortLine(text: String, selected: Boolean = false) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = if (selected) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}
