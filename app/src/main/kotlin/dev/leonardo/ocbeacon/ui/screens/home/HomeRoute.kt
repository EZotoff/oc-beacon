package dev.leonardo.ocbeacon.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.leonardo.ocbeacon.data.api.dsh.DshPairPayload
import dev.leonardo.ocbeacon.ui.components.voice.VoiceWidget
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * HomeScreen 的路由包装。
 * 处理 ViewModel 绑定和导航参数提取。
 * NavGraph 调用此函数而非直接调用 HomeScreen。
 */
@Composable
fun HomeRoute(
    windowSizeClass: WindowSizeClass,
    onNavigateToSessions: (serverId: String) -> Unit,
    onNavigateToServerSettings: (serverId: String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToSupervisor: (serverId: String) -> Unit,
    // #325②：DSH 配对深链预填载荷（null = 无待处理；消费后由 NavGraph 置空）
    pendingPairRequest: DshPairPayload? = null,
    onPairRequestConsumed: () -> Unit = {},
) {
    val viewModel: HomeViewModel = hiltViewModel()
    Box(Modifier.fillMaxSize()) {
        HomeScreen(
        windowSizeClass = windowSizeClass,
        viewModel = viewModel,
        onNavigateToSessions = onNavigateToSessions,
        onNavigateToServerSettings = onNavigateToServerSettings,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToAbout = onNavigateToAbout,
        onNavigateToDiagnostics = onNavigateToDiagnostics,
        onNavigateToSupervisor = onNavigateToSupervisor,
        pendingPairRequest = pendingPairRequest,
            onPairRequestConsumed = onPairRequestConsumed,
        )
        // 2026-10-08 走测：PTT 也挂在首页 —— 无需先进入 supervisor 屏即可呼叫 Vox。
        VoiceWidget(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(SpacingTokens.LG.dp),
        )
    }
}
