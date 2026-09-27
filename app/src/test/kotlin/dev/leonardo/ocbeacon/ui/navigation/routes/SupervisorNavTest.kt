package dev.leonardo.ocbeacon.ui.navigation.routes

import android.os.Bundle
import androidx.navigation.NavBackStackEntry
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorNavTest {

    @Test
    fun `detail route pattern carries itemId path placeholder`() {
        assertTrue(SupervisorNav.detailRoutePattern.contains("supervisor/item/{itemId}?"))
    }

    @Test
    fun `open items pattern carries health context flag with default`() {
        assertTrue(SupervisorNav.openItemsRoutePattern.contains("healthContext={healthContext}"))
    }

    @Test
    fun `create detail route encodes server and item ids`() {
        val route = SupervisorNav.createDetailRoute("srv 1", "att/42")
        assertEquals("supervisor/item/att%2F42?serverId=srv+1", route)
    }

    @Test
    fun `create open items route defaults health context to false and can opt in`() {
        assertEquals("supervisor/open-items?serverId=s1&healthContext=false", SupervisorNav.createOpenItemsRoute("s1"))
        assertEquals("supervisor/open-items?serverId=s1&healthContext=true", SupervisorNav.createOpenItemsRoute("s1", true))
    }

    @Test
    fun `itemId entry accessor round-trips encoded ids`() {
        assertEquals("att/42", SupervisorNav.itemId(entryWithItemId("att%2F42")))
        assertEquals("", SupervisorNav.itemId(entryWithItemId(null)))
    }

    private fun entryWithItemId(raw: String?): NavBackStackEntry {
        val entry = mockk<NavBackStackEntry>()
        val arguments = mockk<Bundle>()
        every { entry.arguments } returns arguments
        every { arguments.getString("itemId") } returns raw
        return entry
    }
}
