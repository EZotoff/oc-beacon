package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLEncoder

object SupervisorNav {
    const val ROUTE = "supervisor"
    const val PARAM_ITEM_ID = "itemId"
    const val PARAM_HEALTH_CONTEXT = "healthContext"
    private const val OPEN_ITEMS = "open-items"
    private const val DECISIONS_LOG = "decisions-log"
    private const val ITEM = "item"
    val navArguments = ServerRouteParams.navArguments
    val openItemsRoutePattern: String
        get() = "$ROUTE/$OPEN_ITEMS?${ServerRouteParams.queryPattern()}&$PARAM_HEALTH_CONTEXT={$PARAM_HEALTH_CONTEXT}"
    val decisionsLogRoutePattern: String
        get() = "$ROUTE/$DECISIONS_LOG?${ServerRouteParams.queryPattern()}"
    val detailRoutePattern: String
        get() = "$ROUTE/$ITEM/{$PARAM_ITEM_ID}?${ServerRouteParams.queryPattern()}"
    val openItemsNavArguments = ServerRouteParams.navArguments +
        navArgument(PARAM_HEALTH_CONTEXT) { type = NavType.BoolType; defaultValue = false }
    val detailNavArguments = ServerRouteParams.navArguments +
        navArgument(PARAM_ITEM_ID) { type = NavType.StringType }

    fun createOpenItemsRoute(serverId: String, healthContext: Boolean = false): String =
        "$ROUTE/$OPEN_ITEMS?${ServerRouteParams.queryString(serverId)}&$PARAM_HEALTH_CONTEXT=$healthContext"

    fun createDecisionsLogRoute(serverId: String): String = "$ROUTE/$DECISIONS_LOG?${ServerRouteParams.queryString(serverId)}"

    fun createDetailRoute(serverId: String, itemId: String): String =
        "$ROUTE/$ITEM/${URLEncoder.encode(itemId, "UTF-8")}?${ServerRouteParams.queryString(serverId)}"

    fun serverId(entry: NavBackStackEntry): String = entry.serverRouteParams().serverId

    fun itemId(entry: NavBackStackEntry): String =
        safeDecodeParam(entry.arguments?.getString(PARAM_ITEM_ID).orEmpty())
}

/**
 * Supervisor 通知点击导航事件：itemId 存在 → 该事项的 Detail；
 * healthContext=true（root 健康通知）→ Open Items + 标注的健康上下文。
 */
data class SupervisorNavEvent(
    val serverId: String,
    val itemId: String? = null,
    val healthContext: Boolean = false,
)
