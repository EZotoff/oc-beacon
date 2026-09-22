package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry

object SupervisorNav {
    const val ROUTE = "supervisor"
    private const val OPEN_ITEMS = "open-items"
    private const val DECISIONS_LOG = "decisions-log"
    val navArguments = ServerRouteParams.navArguments
    val openItemsRoutePattern: String
        get() = "$ROUTE/$OPEN_ITEMS?${ServerRouteParams.queryPattern()}"
    val decisionsLogRoutePattern: String
        get() = "$ROUTE/$DECISIONS_LOG?${ServerRouteParams.queryPattern()}"

    fun createOpenItemsRoute(serverId: String): String = "$ROUTE/$OPEN_ITEMS?${ServerRouteParams.queryString(serverId)}"

    fun createDecisionsLogRoute(serverId: String): String = "$ROUTE/$DECISIONS_LOG?${ServerRouteParams.queryString(serverId)}"

    fun serverId(entry: NavBackStackEntry): String = entry.serverRouteParams().serverId
}
