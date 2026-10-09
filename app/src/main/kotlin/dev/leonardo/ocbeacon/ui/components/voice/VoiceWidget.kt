package dev.leonardo.ocbeacon.ui.components.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.voice.VoicePttAction
import dev.leonardo.ocbeacon.domain.voice.VoicePttGate
import dev.leonardo.ocbeacon.domain.voice.VoiceSessionConnection
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * Voice walking-test surface: connection chip + show cards above a
 * press-and-hold PTT button in the thumb zone. Always visible — voice does
 * NOT depend on supervisor data (view-context degrades gracefully without it).
 */
@Composable
fun VoiceWidget(
    modifier: Modifier = Modifier,
    viewModel: VoiceViewModel = hiltViewModel(),
    showPresentation: Boolean = true,
    beforePtt: () -> Unit = {},
) {

    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val showFrame by viewModel.showFrame.collectAsStateWithLifecycle()
    val audioFailure by viewModel.audioFailure.collectAsStateWithLifecycle()
    val focusLost by viewModel.focusLost.collectAsStateWithLifecycle()
    val pttHeld by viewModel.pttHeld.collectAsStateWithLifecycle()

    // 2026-10-09: tap-to-toggle has no finger-release guarantee — release capture
    // (and the mic) when the widget leaves composition or the app backgrounds,
    // otherwise the recorder holds RECORD_AUDIO system-wide and breaks keyboard
    // voice input on other screens/apps.
    DisposableEffect(Unit) { onDispose { viewModel.releasePtt() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.releasePtt() }

    val context = LocalContext.current
    var pressAfterPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pressAfterPermission) {
            beforePtt()
            viewModel.pressPtt()
        }
        pressAfterPermission = false
    }

    val onPttToggle: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        when (VoicePttGate.resolve(granted)) {
            VoicePttAction.PRESS -> {
                beforePtt()
                if (pttHeld) viewModel.releasePtt() else viewModel.pressPtt()
            }
            VoicePttAction.REQUEST_PERMISSION -> {
                pressAfterPermission = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
    ) {
        ConnectionChip(connection)
        if (audioFailure != null) {
            BlockedOverlay(stringResource(R.string.voice_audio_blocked, audioFailure ?: ""))
        } else if (focusLost) {
            BlockedOverlay(stringResource(R.string.voice_audio_focus_lost))
        }
        showFrame?.takeIf { showPresentation }?.let { frame ->
            ShowViewCard(
                frame = frame,
                onSelect = viewModel::sendSelection,
                modifier = Modifier.widthIn(max = 360.dp),
            )
        }
        PttButton(onToggle = onPttToggle, connection = connection, held = pttHeld)
    }
}

@Composable
private fun ConnectionChip(connection: VoiceSessionConnection) {
    val (colorRes, labelRes) = when (connection) {
        VoiceSessionConnection.Live -> Color(0xFF4CAF50) to R.string.voice_state_live
        VoiceSessionConnection.Connecting -> Color(0xFFFFC107) to R.string.voice_state_connecting
        VoiceSessionConnection.MovedToAnotherSurface -> MaterialTheme.colorScheme.tertiary to R.string.voice_state_moved
        VoiceSessionConnection.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant to R.string.voice_state_offline
    }
    AssistChip(
        onClick = {},
        leadingIcon = {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colorRes),
            )
        },
        label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
private fun BlockedOverlay(message: String) {
    Card(Modifier.widthIn(max = 360.dp)) {
        Text(
            message,
            modifier = Modifier.padding(SpacingTokens.MD.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PttButton(
    onToggle: () -> Unit,
    connection: VoiceSessionConnection,
    held: Boolean,
) {
    // Tap-to-speak toggle states, visually distinct at a glance:
    //   listening (held + live): red, "Listening — tap to stop"
    //   connecting: amber, "Connecting…"
    //   live idle / offline: primary, "Tap to speak"
    val recording = held && connection == VoiceSessionConnection.Live
    val (container, labelRes) = when {
        recording -> Color(0xFFE53935) to R.string.voice_ptt_listening
        connection == VoiceSessionConnection.Connecting -> Color(0xFFFFC107) to R.string.voice_ptt_connecting
        connection == VoiceSessionConnection.Live -> Color(0xFF4CAF50) to R.string.voice_ptt
        else -> MaterialTheme.colorScheme.primary to R.string.voice_ptt
    }
    val scale = if (recording) 1.08f else 1f
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = Color.White,
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(72.dp)
            .scale(scale)
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onToggle() } },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(SpacingTokens.SM.dp),
            )
        }
    }
}
