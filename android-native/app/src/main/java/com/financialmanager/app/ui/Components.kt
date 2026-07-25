package com.financialmanager.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financialmanager.app.money.Money

private val CardShape = RoundedCornerShape(20.dp)

@Composable
private fun surfaceCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = CardShape,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun StatCard(
    label: String,
    value: String,
    note: String? = null,
    valueColour: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    surfaceCard(modifier) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            style = MoneyLarge.copy(fontSize = 22.sp),
            color = valueColour,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    surfaceCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            trailing?.invoke()
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * A ring showing what the month's spending went on.
 *
 * Drawn directly rather than pulled in from a charting library — it is one arc
 * per slice, and a dependency for that would be a poor trade.
 */
@Composable
fun CategoryDonut(
    slices: List<Triple<String, Long, Color>>,
    currency: String,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.second }
    if (total <= 0L) return

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(128.dp)) {
                val stroke = 24.dp.toPx()
                var startAngle = -90f
                slices.forEach { (_, amount, colour) ->
                    val sweep = 360f * amount / total
                    drawArc(
                        color = colour,
                        startAngle = startAngle,
                        sweepAngle = (sweep - 1.2f).coerceAtLeast(0.4f),  // hairline gap
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke),
                    )
                    startAngle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Spent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(Money.formatShort(total, currency), style = MoneyMedium)
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.take(6).forEach { (name, amount, colour) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colour)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(Money.format(amount, currency), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Twelve months of money in and money out, as paired bars. */
@Composable
fun CashFlowChart(
    months: List<Triple<String, Long, Long>>,
    modifier: Modifier = Modifier,
) {
    if (months.isEmpty()) return

    val peak = months.flatMap { listOf(it.second, it.third) }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val incomeColour = positiveColour()
    val expenseColour = negativeColour()

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val groupWidth = size.width / months.size
            val barWidth = (groupWidth / 3f).coerceAtMost(14.dp.toPx())

            months.forEachIndexed { index, (_, income, expense) ->
                val centre = groupWidth * index + groupWidth / 2
                val incomeHeight = size.height * income / peak
                val expenseHeight = size.height * expense / peak

                drawRect(
                    color = incomeColour,
                    topLeft = Offset(centre - barWidth - 1.5f, size.height - incomeHeight),
                    size = Size(barWidth, incomeHeight.toFloat().coerceAtLeast(1.5f)),
                )
                drawRect(
                    color = expenseColour,
                    topLeft = Offset(centre + 1.5f, size.height - expenseHeight),
                    size = Size(barWidth, expenseHeight.toFloat().coerceAtLeast(1.5f)),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                months.first().first,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                months.last().first,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
