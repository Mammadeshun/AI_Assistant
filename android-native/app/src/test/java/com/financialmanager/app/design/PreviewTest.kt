package com.financialmanager.app.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.financialmanager.app.money.Money
import com.financialmanager.app.ui.BalanceHero
import com.financialmanager.app.ui.CashFlowChart
import com.financialmanager.app.ui.CategoryDonut
import com.financialmanager.app.ui.CategoryIcons
import com.financialmanager.app.ui.FinancialManagerTheme
import com.financialmanager.app.ui.GroupLabel
import com.financialmanager.app.ui.GroupRow
import com.financialmanager.app.ui.Hairline
import com.financialmanager.app.ui.InsetGroup
import com.financialmanager.app.ui.MoneyMedium
import com.financialmanager.app.ui.Pill
import com.financialmanager.app.ui.ProgressBar
import com.financialmanager.app.ui.SectionCard
import com.financialmanager.app.ui.StatTile
import com.financialmanager.app.ui.amountColour
import com.financialmanager.app.ui.negativeColour
import com.financialmanager.app.ui.positiveColour
import org.junit.Rule
import org.junit.Test

/**
 * Renders the screens to PNG so the design can be looked at rather than
 * imagined.
 *
 * There is no emulator on the machine this is built on, and a layout that
 * overflows, or a colour that disappears against its own background, is not
 * something a unit test would ever notice.
 */
class PreviewTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test fun dashboardLight() = paparazzi.snapshot { Dashboard() }

    @Test
    fun dashboardDark() {
        paparazzi.unsafeUpdateConfig(
            DeviceConfig.PIXEL_6.copy(nightMode = com.android.resources.NightMode.NIGHT)
        )
        paparazzi.snapshot { Dashboard() }
    }

    @Test fun transactionsLight() = paparazzi.snapshot { Transactions() }

    @Test
    fun transactionsDark() {
        paparazzi.unsafeUpdateConfig(
            DeviceConfig.PIXEL_6.copy(nightMode = com.android.resources.NightMode.NIGHT)
        )
        paparazzi.snapshot { Transactions() }
    }

    /** The alarming case, which has to look different at a glance. */
    @Test
    fun shortOfMoney() {
        paparazzi.snapshot {
            FinancialManagerTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp)
                ) {
                    BalanceHero(
                        label = "Short by",
                        amountMinor = 8_450,
                        currency = "EUR",
                        caption = "€430.00 still has to leave this month, and there isn't enough.",
                        monthProgress = 0.72f,
                        short = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun Dashboard() {
    FinancialManagerTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
        ) {
            Text("July", style = MaterialTheme.typography.headlineLarge)

            BalanceHero(
                label = "Safe to spend",
                amountMinor = 61_117,
                currency = "EUR",
                caption = "€47.01 a day for the 13 days left, after €120.00 still to leave.",
                monthProgress = 0.58f,
                short = false,
            )

            GroupLabel("Coming up", trailing = {
                Text(
                    "€620.00 out",
                    style = MoneyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            })
            InsetGroup {
                GroupRow(
                    title = "Rent",
                    icon = CategoryIcons.forCommitment("BILL"),
                    trailing = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("€500.00", style = MoneyMedium)
                            Spacer(Modifier.height(3.dp))
                            Pill("Tomorrow", negativeColour())
                        }
                    },
                )
                Hairline(62.dp)
                GroupRow(
                    title = "Klarna – Zara",
                    icon = CategoryIcons.forCommitment("INSTALMENT"),
                    trailing = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("€120.00", style = MoneyMedium)
                            Spacer(Modifier.height(3.dp))
                            Pill("in 9 days", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
                Hairline(62.dp)
                // Money arriving, which has to read as different at a glance
                // from money leaving without needing to be read.
                GroupRow(
                    title = "Revenue",
                    icon = CategoryIcons.forCommitment("INCOME"),
                    iconTint = positiveColour(),
                    trailing = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                Money.format(80_000, "EUR", signed = true),
                                style = MoneyMedium,
                                color = positiveColour(),
                            )
                            Spacer(Modifier.height(3.dp))
                            Pill("in 6 days", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile(
                    "In this month", 165_100, "EUR",
                    Modifier.weight(1f).fillMaxHeight(),
                    colour = positiveColour(), note = "412 payments in all",
                )
                StatTile(
                    "Out this month", 112_358, "EUR",
                    Modifier.weight(1f).fillMaxHeight(), note = "net +€527.42",
                )
            }

            SectionCard("Where it went") {
                CategoryDonut(
                    slices = listOf(
                        Triple("Groceries", 42_300L, Color(0xFF4CAF50)),
                        Triple("Restaurants", 28_150L, Color(0xFFFF7043)),
                        Triple("Transport", 18_900L, Color(0xFF42A5F5)),
                        Triple("Shopping", 14_008L, Color(0xFFEC407A)),
                        Triple("Bills", 9_000L, Color(0xFF26A69A)),
                    ),
                    currency = "EUR",
                )
            }

            SectionCard("Groceries budget") {
                ProgressBar(fraction = 0.78f, colour = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "€234.00 of €300.00",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Running ahead",
                        style = MoneyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Transactions() {
    val rows = listOf(
        Triple("Esselunga", "Groceries", -1_845L),
        Triple("Il Caffè all'Università", "Restaurants & Cafés", -270L),
        Triple("Trenitalia", "Transport", -1_150L),
        Triple("Payment from Employer", "Salary", 150_000L),
        Triple("iliad", "Bills & Utilities", -999L),
        Triple("Klarna - Zara", "Shopping", -2_499L),
    )
    val tints = listOf(
        Color(0xFF4CAF50), Color(0xFFFF7043), Color(0xFF42A5F5),
        Color(0xFF26A69A), Color(0xFFFFB020), Color(0xFFEC407A),
    )

    FinancialManagerTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
        ) {
            Text("Activity", style = MaterialTheme.typography.headlineLarge)
            GroupLabel("Today")

            InsetGroup {
                rows.forEachIndexed { index, (merchant, category, amount) ->
                    GroupRow(
                        title = merchant,
                        subtitle = category,
                        icon = CategoryIcons[category],
                        iconTint = tints[index],
                        trailing = {
                            Text(
                                Money.format(amount, "EUR", signed = true),
                                style = MoneyMedium,
                                color = amountColour(amount),
                            )
                        },
                    )
                    if (index < rows.lastIndex) Hairline(62.dp)
                }
            }

            GroupLabel("Yesterday")
            InsetGroup {
                GroupRow(
                    title = "Amazon",
                    subtitle = "Shopping",
                    icon = Icons.Outlined.Home,
                    trailing = { Text("-€31.20", style = MoneyMedium) },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionCard("In and out") {
                CashFlowChart(
                    months = listOf(
                        Triple("Feb", 150_000L, 132_000L),
                        Triple("Mar", 148_000L, 121_000L),
                        Triple("Apr", 151_000L, 165_000L),
                        Triple("May", 149_000L, 118_000L),
                        Triple("Jun", 152_000L, 129_000L),
                        Triple("Jul", 165_100L, 112_358L),
                    )
                )
            }
        }
    }
}
