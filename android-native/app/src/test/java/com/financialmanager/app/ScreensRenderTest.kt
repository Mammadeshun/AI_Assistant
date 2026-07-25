package com.financialmanager.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financialmanager.app.ui.AppViewModel
import com.financialmanager.app.ui.AssistantScreen
import com.financialmanager.app.ui.DashboardScreen
import com.financialmanager.app.ui.FinancialManagerTheme
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
                DashboardScreen(model(), PaddingValues(0.dp()), onImport = {})
            }
        }
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
        compose.onNodeWithText("Import a statement").assertIsDisplayed()
    }

    @Test
    fun `transactions screen opens`() {
        compose.setContent {
            FinancialManagerTheme {
                TransactionsScreen(model(), PaddingValues(0.dp()))
            }
        }
        compose.onNodeWithText("Search transactions").assertIsDisplayed()
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
