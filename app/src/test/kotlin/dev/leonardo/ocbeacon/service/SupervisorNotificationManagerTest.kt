package dev.leonardo.ocbeacon.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SupervisorNotificationManagerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val channelManager: SupervisorChannelManager = mockk(relaxed = true)
    private val notificationManager: NotificationManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        every { appContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getActivity(any(), any(), any(), any()) } returns mockk(relaxed = true)
        mockkConstructor(NotificationCompat.Builder::class)
        every { anyConstructed<NotificationCompat.Builder>().build() } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun manager() = SupervisorNotificationManager(appContext, channelManager)

    private fun item(
        id: String,
        escalationKind: String = "DECISION",
        actionClass: String = "ESCALATE",
    ) = SupervisorAttentionItem(
        id = id,
        question = "q-$id",
        project = "/work/$id",
        createdAt = "2026-09-27T09:00:00Z",
        stakes = 1,
        actionClass = actionClass,
        escalationKind = escalationKind,
        reasonText = "reason-$id",
        premiseTexts = listOf("p1"),
        severity = "B",
        sessionLabel = "ses-$id",
    )

    @Test
    fun `filter allows only APPROVAL and DECISION`() {
        assertTrue(SupervisorPushSpec.shouldNotify(item("a", escalationKind = "APPROVAL")))
        assertTrue(SupervisorPushSpec.shouldNotify(item("a", escalationKind = "DECISION")))
        for (kind in listOf("INFORMATION", "", "unknown", "approval")) {
            assertFalse("kind=$kind must not push", SupervisorPushSpec.shouldNotify(item("a", escalationKind = kind)))
        }
    }

    @Test
    fun `INFORMATION and blank escalationKind build no notification at all`() = runTest {
        for (kind in listOf("INFORMATION", "")) {
            manager().notifyAttentionItem("s1", item("x", escalationKind = kind))
        }
        verify { notificationManager wasNot Called }
    }

    @Test
    fun `APPROVAL builds public lockscreen notification with per-item id and request code`() = runTest {
        val item = item("a", escalationKind = "APPROVAL")
        manager().notifyAttentionItem("s1", item)

        verify {
            anyConstructed<NotificationCompat.Builder>().setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }
        verify {
            anyConstructed<NotificationCompat.Builder>().setContentTitle(any<String>())
            anyConstructed<NotificationCompat.Builder>().setContentText(any<String>())
        }
        // 无设备保守回退：不分组（无 setGroup / setGroupAlertBehavior）。
        verify(exactly = 0) { anyConstructed<NotificationCompat.Builder>().setGroup(any()) }
        verify(exactly = 0) { anyConstructed<NotificationCompat.Builder>().setGroupAlertBehavior(any()) }
        verify {
            notificationManager.notify(
                SupervisorPushSpec.attentionNotificationId("s1", "a"),
                any(),
            )
        }
        verify {
            PendingIntent.getActivity(
                any(),
                SupervisorPushSpec.attentionRequestCode("s1", "a"),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `distinct items get distinct notification ids and request codes`() = runTest {
        manager().notifyAttentionItem("s1", item("a"))
        manager().notifyAttentionItem("s1", item("b"))

        val idA = SupervisorPushSpec.attentionNotificationId("s1", "a")
        val idB = SupervisorPushSpec.attentionNotificationId("s1", "b")
        val rcA = SupervisorPushSpec.attentionRequestCode("s1", "a")
        val rcB = SupervisorPushSpec.attentionRequestCode("s1", "b")
        assertNotEquals(idA, idB)
        assertNotEquals(rcA, rcB)
        verify { notificationManager.notify(idA, any()) }
        verify { notificationManager.notify(idB, any()) }
        verify { PendingIntent.getActivity(any(), rcA, any(), any()) }
        verify { PendingIntent.getActivity(any(), rcB, any(), any()) }
    }

    @Test
    fun `root health keeps fixed id and request code with health context and no item id`() = runTest {
        manager().notifyRootHealth("s1", failingRoots = listOf("/work/x"), errorsPeak = 0, peakIncreased = false)

        verify {
            notificationManager.notify(SupervisorPushSpec.rootHealthNotificationId("s1"), any())
        }
        verify {
            PendingIntent.getActivity(any(), SupervisorPushSpec.ROOT_HEALTH_REQUEST_CODE, any(), any())
        }
    }

    @Test
    fun `open targets carry item extras for attention and health context for root health`() {
        val attention = SupervisorPushSpec.attentionTarget("s1", "item-9")
        assertEquals("s1", attention.serverId)
        assertEquals("item-9", attention.itemId)
        assertFalse(attention.healthContext)

        val health = SupervisorPushSpec.rootHealthTarget("s1")
        assertEquals("s1", health.serverId)
        assertNull(health.itemId)
        assertTrue(health.healthContext)
    }
}
