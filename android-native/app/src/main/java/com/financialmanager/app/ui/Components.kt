package com.financialmanager.app.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financialmanager.app.money.Money

/**
 * The building blocks.
 *
 * Two rules run through all of it. Money is the loudest thing on any screen and
 * everything else gets out of its way; and a figure that changes animates to its
 * new value rather than jumping, because a number that moves is one you notice.
 */

/* --- cards ----------------------------------------------------------- */

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Shape.card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            if (title != null || trailing != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                    trailing?.invoke()
                }
                Spacer(Modifier.height(14.dp))
            }
            content()
        }
    }
}

/**
 * The headline card: what is left, and how far through the month you are.
 *
 * The ring is the month, filling as the days pass. Seeing the money and the
 * time together is the whole point — €200 left is comfortable on the 28th and a
 * problem on the 4th, and one number cannot say which.
 */
@Composable
fun HeroCard(
    label: String,
    amountMinor: Long,
    currency: String,
    caption: String,
    monthProgress: Float,
    short: Boolean,
    modifier: Modifier = Modifier,
) {
    val onHero = onHeroColour()
    val progress by animateFloatAsState(
        targetValue = monthProgress.coerceIn(0f, 1f),
        animationSpec = tween(900, easing = LinearOutSlowInEasing),
        label = "month",
    )

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Shape.hero))
            .background(heroBrush(short)),
    ) {
        Row(
            Modifier.padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), style = Eyebrow, color = onHero.copy(alpha = 0.75f))
                Spacer(Modifier.height(8.dp))
                AnimatedMoney(
                    amountMinor = amountMinor,
                    currency = currency,
                    style = MoneyHero,
                    colour = onHero,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = onHero.copy(alpha = 0.82f),
                )
            }

            Spacer(Modifier.width(14.dp))
            MonthRing(progress = progress, colour = onHero)
        }
    }
}

/** A ring showing how much of the month is gone. */
@Composable
private fun MonthRing(progress: Float, colour: Color, size: androidx.compose.ui.unit.Dp = 62.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = colour.copy(alpha = 0.28f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = colour,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = colour,
        )
    }
}

/**
 * A figure that counts to its new value.
 *
 * Cheap to do and it earns its keep: when the assistant changes something, the
 * number visibly moves, so a change made by talking is impossible to miss.
 */
@Composable
fun AnimatedMoney(
    amountMinor: Long,
    currency: String,
    style: androidx.compose.ui.text.TextStyle,
    colour: Color = MaterialTheme.colorScheme.onSurface,
    signed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = amountMinor.toFloat(),
        animationSpec = tween(700, easing = LinearOutSlowInEasing),
        label = "amount",
    )
    Text(
        Money.format(animated.toLong(), currency, signed = signed),
        style = style,
        color = colour,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A small figure with a label: two or three across a row. */
@Composable
fun StatTile(
    label: String,
    amountMinor: Long,
    currency: String,
    modifier: Modifier = Modifier,
    colour: Color? = null,
    note: String? = null,
    signed: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Shape.card),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                label.uppercase(),
                style = Eyebrow,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            AnimatedMoney(
                amountMinor = amountMinor,
                currency = currency,
                style = MoneyLarge,
                colour = colour ?: MaterialTheme.colorScheme.onSurface,
                signed = signed,
            )
            if (note != null) {
                Spacer(Modifier.height(4.dp))
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
}

/* --- charts ---------------------------------------------------------- */

/**
 * Where the month's money went.
 *
 * Drawn rather than pulled in from a library: it is one arc per slice, and the
 * arcs grow from nothing when the screen opens, which reads as the figure being
 * worked out rather than simply appearing.
 */
@Composable
fun CategoryDonut(
    slices: List<Triple<String, Long, Color>>,
    currency: String,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.second }
    if (total <= 0L) return

    val sweep by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(850, easing = LinearOutSlowInEasing),
        label = "donut",
    )

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(126.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(126.dp)) {
                val stroke = 18.dp.toPx()
                val inset = stroke / 2
                var start = -90f
                slices.forEach { (_, amount, colour) ->
                    val degrees = 360f * amount / total * sweep
                    drawArc(
                        color = colour,
                        startAngle = start,
                        sweepAngle = (degrees - 2f).coerceAtLeast(0.6f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    start += degrees
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "SPENT",
                    style = Eyebrow,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(Money.formatShort(total, currency), style = MoneyMedium)
            }
        }

        Spacer(Modifier.width(18.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            slices.take(5).forEach { (name, amount, colour) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(colour)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(Money.format(amount, currency), style = MoneyMedium)
                }
            }
        }
    }
}

/**
 * Money in and money out, month by month.
 *
 * Income above the line, spending below it, so a month that cost more than it
 * earned is visible as a shape rather than by comparing two bar heights.
 */
@Composable
fun CashFlowChart(
    months: List<Triple<String, Long, Long>>,
    modifier: Modifier = Modifier,
) {
    if (months.isEmpty()) return

    val peak = months.flatMap { listOf(it.second, it.third) }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val income = positiveColour()
    val expense = negativeColour()
    val axis = MaterialTheme.colorScheme.outlineVariant
    val grown by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(800, easing = LinearOutSlowInEasing),
        label = "bars",
    )

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
        ) {
            val slot = size.width / months.size
            val barWidth = (slot * 0.28f).coerceAtMost(11.dp.toPx())
            val middle = size.height / 2
            val usable = middle - 6.dp.toPx()
            val radius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2)

            drawLine(
                color = axis,
                start = Offset(0f, middle),
                end = Offset(size.width, middle),
                strokeWidth = 1.dp.toPx(),
            )

            months.forEachIndexed { index, (_, moneyIn, moneyOut) ->
                val centre = slot * index + slot / 2
                val up = usable * moneyIn / peak * grown
                val down = usable * moneyOut / peak * grown

                drawRoundRect(
                    color = income,
                    topLeft = Offset(centre - barWidth - 2.dp.toPx(), middle - up),
                    size = Size(barWidth, up.toFloat().coerceAtLeast(2f)),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = expense,
                    topLeft = Offset(centre + 2.dp.toPx(), middle),
                    size = Size(barWidth, down.toFloat().coerceAtLeast(2f)),
                    cornerRadius = radius,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot("In", income)
            LegendDot("Out", expense)
            Spacer(Modifier.weight(1f))
            Text(
                "${months.first().first} – ${months.last().first}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendDot(label: String, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(colour)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A category's icon in a soft circle, used down the transaction list. */
@Composable
fun CategoryBadge(icon: String, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(icon, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A thin bar for budget progress, rounded and coloured by how it is going. */
@Composable
fun ProgressBar(fraction: Float, colour: Color, modifier: Modifier = Modifier) {
    val width by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(700, easing = LinearOutSlowInEasing),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(Shape.pill))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(width)
                .height(8.dp)
                .clip(RoundedCornerShape(Shape.pill))
                .background(
                    Brush.horizontalGradient(listOf(colour.copy(alpha = 0.75f), colour))
                )
        )
    }
}
