package com.financialmanager.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
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
 *
 * It carries three things now rather than one: the figure, the month running out
 * as a bar under it, and the next payment due. That last line is the one that
 * makes a glance worth taking — knowing €430 leaves on Friday changes what you
 * do today in a way that a balance never does.
 */
class SafeToSpendWidget : GlanceAppWidget() {

    // Two layouts: the tall one adds the next payment and the daily allowance.
    // A widget that scales by squashing its text is the usual look of one that
    // was only ever designed at a single size.
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 60.dp), DpSize(250.dp, 120.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = load(context)
        provideContent { Content(snapshot, context) }
    }

    @Composable
    private fun Content(snapshot: Snapshot, context: Context) {
        GlanceTheme {
            val tall = LocalSize.current.height >= 110.dp
            val accent = if (snapshot.short) DANGER else GlanceTheme.colors.primary

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.widgetBackground)
                    .cornerRadius(24.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Text(
                        if (snapshot.short) "Short by" else "Safe to spend",
                        style = TextStyle(
                            fontSize = 12.sp(),
                            fontWeight = FontWeight.Medium,
                            color = GlanceTheme.colors.onSurfaceVariant,
                        ),
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    if (tall) {
                        Text(
                            "${snapshot.daysLeft} days left",
                            style = TextStyle(
                                fontSize = 12.sp(),
                                color = GlanceTheme.colors.onSurfaceVariant,
                            ),
                        )
                    }
                }

                Spacer(GlanceModifier.height(2.dp))

                Text(
                    snapshot.headline,
                    style = TextStyle(
                        fontSize = if (tall) 34.sp() else 26.sp(),
                        fontWeight = FontWeight.Bold,
                        color = if (snapshot.short) DANGER else GlanceTheme.colors.onSurface,
                    ),
                )

                Spacer(GlanceModifier.height(if (tall) 10.dp else 7.dp))

                // The month as a bar, drawn out of two coloured boxes: Glance has
                // no progress indicator worth the name, and this is exact.
                MonthBar(snapshot.monthProgress, accent)

                if (tall) {
                    Spacer(GlanceModifier.height(11.dp))
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Text(
                            snapshot.allowance,
                            style = TextStyle(
                                fontSize = 13.sp(),
                                fontWeight = FontWeight.Medium,
                                color = GlanceTheme.colors.onSurface,
                            ),
                        )
                        Spacer(GlanceModifier.defaultWeight())
                        Text(
                            snapshot.next,
                            style = TextStyle(
                                fontSize = 13.sp(),
                                color = GlanceTheme.colors.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * The month as a row of segments, one per stretch of days, filling as it
     * goes.
     *
     * Segments rather than a continuous bar because Glance cannot lay a child
     * out at a fraction of its parent's width — and because a widget is glanced
     * at, where a count of blocks reads faster than the length of a line.
     */
    @Composable
    private fun MonthBar(progress: Float, colour: ColorProvider) {
        val filled = (progress.coerceIn(0f, 1f) * SEGMENTS).toInt().coerceAtLeast(1)
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            repeat(SEGMENTS) { index ->
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .height(6.dp)
                        .cornerRadius(3.dp)
                        .background(
                            if (index < filled) colour else GlanceTheme.colors.surfaceVariant
                        ),
                ) {}
                if (index < SEGMENTS - 1) Spacer(GlanceModifier.width(3.dp))
            }
        }
    }

    private data class Snapshot(
        val headline: String,
        val allowance: String,
        val next: String,
        val daysLeft: Int,
        val monthProgress: Float,
        val short: Boolean,
    )

    private suspend fun load(context: Context): Snapshot {
        val today = LocalDate.now()
        val progress = today.dayOfMonth.toFloat() / today.lengthOfMonth()

        val db = FinanceDatabase.get(context)
        val currency = db.transactions().currencies().firstOrNull()
            ?: return Snapshot("—", "", "Import a statement", 0, progress, false)

        val month = today.withDayOfMonth(1)
        val commitments = db.commitments().activeNow()
        val plan = Planner.monthPlan(
            currency = currency,
            balanceMinor = db.transactions().balance(currency).first() +
                (db.cash().amountNow(currency) ?: 0L),
            incomeMinor = db.transactions()
                .incomeBetween(currency, month, month.plusMonths(1).minusDays(1)).first(),
            spentMinor = db.transactions()
                .spendBetween(currency, month, month.plusMonths(1).minusDays(1)).first(),
            commitments = commitments,
            totalOwedMinor = db.commitments().totalOwed(currency).first(),
        )

        val next = Planner.upcoming(commitments, withinDays = 31, today = today).firstOrNull()

        return Snapshot(
            headline = Money.format(kotlin.math.abs(plan.safeToSpendMinor), currency),
            allowance = if (plan.isOverstretched) "Nothing spare"
            else "${Money.format(plan.dailyAllowanceMinor, currency)} a day",
            next = next?.let {
                "${it.commitment.name} ${it.whenText.lowercase()}"
            } ?: "Nothing due",
            daysLeft = plan.daysLeft,
            monthProgress = progress,
            short = plan.isOverstretched,
        )
    }

    companion object {
        /** Called whenever something that changes the figure changes. */
        suspend fun refresh(context: Context) {
            runCatching { SafeToSpendWidget().updateAll(context) }
        }

        /**
         * Red is hard-coded rather than taken from the widget theme: the theme's
         * error colour is whatever the launcher's wallpaper tinting made it, and
         * "you are short" is the one thing here that must not be a pastel. One
         * shade for both themes, chosen to hold up on a white home screen and a
         * black one alike.
         */
        private val DANGER: ColorProvider = ColorProvider(Color(0xFFE5484D))

        /** How many blocks the month is divided into. */
        private const val SEGMENTS = 14
    }
}

class SafeToSpendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SafeToSpendWidget()
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(
    this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp,
)
