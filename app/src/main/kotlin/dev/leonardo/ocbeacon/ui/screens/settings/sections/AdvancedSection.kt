package dev.leonardo.ocbeacon.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.voice.VoiceUrl
import dev.leonardo.ocbeacon.ui.screens.settings.SettingsViewModel
import dev.leonardo.ocbeacon.ui.screens.settings.components.SectionHeader
import dev.leonardo.ocbeacon.ui.theme.ListItemTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

@Composable
fun AdvancedSection(
    viewModel: SettingsViewModel,
    onNavigateToDiagnostics: () -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_section_advanced))

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_diagnostics)) },
        supportingContent = { Text(stringResource(R.string.settings_diagnostics_desc)) },
        leadingContent = {
            Icon(Icons.Default.BugReport, contentDescription = stringResource(R.string.settings_diagnostics))
        },
        modifier = Modifier.clickable { onNavigateToDiagnostics() }.padding(ListItemTokens.ContentPaddingMedium),
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = SpacingTokens.XS.dp))

    // 2026-10-08 Wave 2：语音（omo-pulse）连接端点。保存时归一化，非法输入禁用保存并提示。
    val storedUrl by viewModel.omoPulseUrl.collectAsStateWithLifecycle()
    var urlDraft by remember(storedUrl) { mutableStateOf(storedUrl) }
    val normalized = VoiceUrl.normalize(urlDraft)
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_omo_pulse_url)) },
        supportingContent = { Text(stringResource(R.string.settings_omo_pulse_url_desc)) },
        leadingContent = {
            Icon(Icons.Default.Podcasts, contentDescription = stringResource(R.string.settings_omo_pulse_url))
        },
        modifier = Modifier.padding(ListItemTokens.ContentPaddingMedium),
    )
    Column(Modifier.padding(horizontal = SpacingTokens.LG.dp)) {
        OutlinedTextField(
            value = urlDraft,
            onValueChange = { urlDraft = it },
            singleLine = true,
            isError = normalized == null,
            supportingText = {
                Text(
                    stringResource(
                        if (normalized == null) R.string.settings_omo_pulse_url_invalid
                        else R.string.settings_omo_pulse_url_desc
                    ),
                    color = if (normalized == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                TextButton(
                    onClick = { normalized?.let(viewModel::setOmoPulseUrl) },
                    enabled = normalized != null && normalized != storedUrl,
                ) { Text(stringResource(R.string.save)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = SpacingTokens.XS.dp))
}
