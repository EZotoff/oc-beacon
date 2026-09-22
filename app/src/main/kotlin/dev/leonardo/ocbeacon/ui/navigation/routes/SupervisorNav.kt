package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry

object SupervisorNav {
    const val ROUTE = "supervisor"
    val navArguments = ServerRouteParams.navArguments
    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}"

    fun createRoute(serverId: String): String = "$ROUTE?${ServerRouteParams.queryString(serverId)}"

    fun serverId(entry: NavBackStackEntry): String = entry.serverRouteParams().serverId
}
