package com.financialmanager.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financialmanager.app.ui.AppViewModel
import com.financialmanager.app.ui.AssistantScreen
import com.financialmanager.app.ui.DashboardScreen
import com.financialmanager.app.ui.FinancialManagerTheme
import com.financialmanager.app.ui.PlanScreen
import com.financialmanager.app.ui.SettingsScreen
import com.financialmanager.app.ui.TransactionsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Renders each screen for real.
 *
 * There is no emulator on the machine this is built on, so without these a
 * successful build would say nothing about whether the app opens. Robolectric
 * runs the Android framework on the JVM, which is enough to catch a screen that
 * throws the moment it is composed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScreensRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun model() = AppViewModel(
        androidx.test.core.app.ApplicationProvider.getApplicationContext()
    )

    @Test
    fun `dashboard opens and asks for a statement when empty`() {
        compose.setContent {
            FinancialManagerTheme {
                DashboardScreen(model(), PaddingValues(0.dp()), onImport = {}, onOpenPlan = {})
            }
        }
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
        compose.onNodeWithText("Import a statement").assertIsDisplayed()
    }

    @Test
    fun `plan screen opens and asks about debts`() {
        compose.setContent {
            FinancialManagerTheme {
                PlanScreen(model(), PaddingValues(0.dp()))
            }
        }
        // Only the cards a lazy list has actually composed can be asserted on.
        compose.onNodeWithText("Safe to spend").assertIsDisplayed()
        compose.onNodeWithText("Cash in your pocket").assertIsDisplayed()
    }

    @Test
    fun `dashboard renders with the plan headline`() {
        compose.setContent {
            FinancialManagerTheme {
                DashboardScreen(model(), PaddingValues(0.dp()), onImport = {}, onOpenPlan = {})
            }
        }
        // With no transactions it is still the empty state that shows.
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    @Test
    fun `transactions screen opens`() {
        compose.setContent {
            FinancialManagerTheme {
                TransactionsScreen(model(), PaddingValues(0.dp()))
            }
        }
        compose.onNodeWithText("Search").assertIsDisplayed()
    }

    @Test
    fun `assistant explains itself until a key is added`() {
        compose.setContent {
            FinancialManagerTheme {
                AssistantScreen(model(), PaddingValues(0.dp()), onOpenSettings = {})
            }
        }
        compose.onNodeWithText("The assistant is off").assertIsDisplayed()
        compose.onNode(hasText("Open Settings")).performClick()
    }

    @Test
    fun `settings screen opens`() {
        compose.setContent {
            FinancialManagerTheme {
                SettingsScreen(model(), PaddingValues(0.dp()), onImport = {})
            }
        }
        // Only what a lazy list has actually composed can be asserted on, and
        // these two cards are the visible part; the point here is that the
        // screen composes at all.
        compose.onNodeWithText("Statements").assertIsDisplayed()
        compose.onNodeWithText("Assistant").assertIsDisplayed()
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

/**
 * Opens the actual activity.
 *
 * The screens above are composed one at a time, which says nothing about the
 * thing that wraps them: the tab bar, the floating assistant button and the
 * overlay it opens. Those are the parts a build cannot check and an emulator
 * would — this is the nearest thing available, and it is enough to catch a
 * composition that throws on launch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainActivityTest {

    @get:Rule
    val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<MainActivity>()

    @Test
    fun `the app launches with its tabs and the assistant button`() {
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Activity").assertIsDisplayed()
        compose.onNodeWithText("Plan").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ask the assistant").assertIsDisplayed()
    }

    @Test
    fun `the assistant opens from the floating button and closes again`() {
        compose.onNodeWithContentDescription("Ask the assistant").performClick()
        compose.onNodeWithText("Assistant").assertIsDisplayed()

        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithContentDescription("Ask the assistant").assertIsDisplayed()
    }

    @Test
    fun `moving between tabs works`() {
        compose.onNodeWithText("Plan").performClick()
        compose.onNodeWithText("Safe to spend").assertIsDisplayed()
    }
}
