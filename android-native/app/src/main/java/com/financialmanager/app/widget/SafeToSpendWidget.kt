package com.financialmanager.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.financialmanager.app.MainActivity
import com.financialmanager.app.data.FinanceDatabase
import com.financialmanager.app.money.Money
import com.financialmanager.app.plan.Planner
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * A home-screen tile showing what is actually left to spend.
 *
 * This is the number worth glancing at, and the reason the widget exists: a
 * balance is on the banking app's own widget already, and it is the misleading
 * one. This subtracts what is still due to leave this month.
 */
class SafeToSpendWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = load(context)
        provideContent { Content(snapshot, context) }
    }

    @Composable
    private fun Content(snapshot: Snapshot, context: Context) {
        GlanceTheme {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.widgetBackground)
                    .cornerRadius(20.dp)
                    .padding(14.dp)
                    .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(
                    if (snapshot.short) "SHORT BY" else "SAFE TO SPEND",
                    style = TextStyle(
                        fontSize = 11.sp(),
                        fontWeight = FontWeight.Medium,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                )
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    snapshot.headline,
                    style = TextStyle(
                        fontSize = 24.sp(),
                        fontWeight = FontWeight.Bold,
                        color = if (snapshot.short) GlanceTheme.colors.error
                        else GlanceTheme.colors.onSurface,
                    ),
                )
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    snapshot.note,
                    style = TextStyle(
                        fontSize = 12.sp(),
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                )
            }
        }
    }

    private data class Snapshot(val headline: String, val note: String, val short: Boolean)

    private suspend fun load(context: Context): Snapshot {
        val db = FinanceDatabase.get(context)
        val currency = db.transactions().currencies().firstOrNull()
            ?: return Snapshot("—", "Import a statement", false)

        val month = LocalDate.now().withDayOfMonth(1)
        val plan = Planner.monthPlan(
            currency = currency,
            balanceMinor = db.transactions().balance(currency).first(),
            incomeMinor = db.transactions()
                .incomeBetween(currency, month, month.plusMonths(1).minusDays(1)).first(),
            spentMinor = db.transactions()
                .spendBetween(currency, month, month.plusMonths(1).minusDays(1)).first(),
            commitments = db.commitments().activeNow(),
            totalOwedMinor = db.commitments().totalOwed(currency).first(),
        )

        val amount = Money.format(kotlin.math.abs(plan.safeToSpendMinor), currency)
        val note = if (plan.isOverstretched) {
            "${plan.daysLeft} days left this month"
        } else {
            "${Money.format(plan.dailyAllowanceMinor, currency)} a day for ${plan.daysLeft} days"
        }
        return Snapshot(amount, note, plan.isOverstretched)
    }

    companion object {
        /** Called whenever something that changes the figure changes. */
        suspend fun refresh(context: Context) {
            runCatching { SafeToSpendWidget().updateAll(context) }
        }
    }
}

class SafeToSpendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SafeToSpendWidget()
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(
    this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp,
)
