package dev.leonardo.ocbeacon.ui.screens.supervisor

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 事项 Detail 路由入口（task 7 先接通路由与返回；完整五节视图在 task 8 落地）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorDetailRoute(
    onNavigateBack: () -> Unit,
    onNavigateToOpenItems: () -> Unit,
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
        Text(
            stringResource(R.string.supervisor_open_items),
            modifier = Modifier.padding(padding).padding(SpacingTokens.LG.dp),
        )
    }
}
