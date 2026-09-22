package dev.leonardo.ocbeacon.ui.screens.supervisor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.domain.model.SupervisorDecision
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import dev.leonardo.ocbeacon.ui.theme.OpenCodeTheme
import org.junit.Rule
import org.junit.Test

class SupervisorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun open_items_destination_renders_glance_and_attention_only() {
        val snapshot = SupervisorSnapshot(
            rootsMonitored = 6,
            rootsFailing = 1,
            errorsPeak = 4,
            attentionItems = listOf(
                SupervisorAttentionItem("att_1", "Choose the release path", "oc-beacon", "2026-09-22T09:00:00Z", 4),
            ),
            recentDecisions = listOf(
                SupervisorDecision("ESCALATE", "oc-beacon", "Operator input is required", "2026-09-22T09:30:00Z"),
            ),
        )

        composeRule.setContent {
            OpenCodeTheme {
                SupervisorScreen(
                    state = SupervisorUiState(snapshot = snapshot, isLoading = false),
                    destination = SupervisorDestination.OPEN_ITEMS,
                    onNavigateBack = {},
                    onNavigateToOtherDestination = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("6 roots").assertIsDisplayed()
        composeRule.onNodeWithText("Choose the release path").assertIsDisplayed()
        composeRule.onNodeWithText("Operator input is required").assertDoesNotExist()
    }

    @Test
    fun decisions_destination_renders_decisions_only() {
        val snapshot = SupervisorSnapshot(
            rootsMonitored = 6,
            rootsFailing = 1,
            errorsPeak = 4,
            attentionItems = listOf(
                SupervisorAttentionItem("att_1", "Choose the release path", "oc-beacon", "2026-09-22T09:00:00Z", 4),
            ),
            recentDecisions = listOf(
                SupervisorDecision("ESCALATE", "oc-beacon", "Operator input is required", "2026-09-22T09:30:00Z"),
            ),
        )

        composeRule.setContent {
            OpenCodeTheme {
                SupervisorScreen(
                    state = SupervisorUiState(snapshot = snapshot, isLoading = false),
                    destination = SupervisorDestination.DECISIONS_LOG,
                    onNavigateBack = {},
                    onNavigateToOtherDestination = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("ESCALATE").assertIsDisplayed()
        composeRule.onNodeWithText("Operator input is required").assertIsDisplayed()
        composeRule.onNodeWithText("Choose the release path").assertDoesNotExist()
    }
}
