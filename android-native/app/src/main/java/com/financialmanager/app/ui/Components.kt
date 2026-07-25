package com.financialmanager.app.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.financialmanager.app.money.Money

/**
 * The building blocks.
 *
 * The shape of the whole app is one idea: a grey page with white groups floating
 * on it, hairlines between the rows, and nothing coloured unless the colour
 * means something. It is the layout every phone owner already knows how to read,
 * which for a money app matters more than looking novel.
 */

/* --- grouped lists ---------------------------------------------------- */

/** The small grey heading that sits above a group. */
@Composable
fun GroupLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left as written rather than uppercased. Shouting the headings is the
        // older platform convention, and a screen reader reads an uppercased
        // string letter by letter.
        Text(
            text,
            style = GroupHeader,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * A white group of rows.
 *
 * No padding of its own: the rows inside set their own, so a hairline can run
 * from the text edge to the card edge the way a list separator should.
 */
@Composable
fun InsetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Shape.card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

/** A hairline between two rows, indented past the icon like a list separator. */
@Composable
fun Hairline(indent: Dp = 16.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * One row of a group: an optional icon, a title, a note under it, and whatever
 * belongs on the right.
 */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = false,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            CategoryBadge(icon, iconTint ?: MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(10.dp))
            it()
        }
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * A group with its own padding, for prose and charts rather than rows.
 *
 * Kept because plenty of the app is a paragraph and a button, which does not
 * want to pretend to be a list.
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) GroupLabel(title, trailing = trailing)
        InsetGroup {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

/* --- the headline ----------------------------------------------------- */

/**
 * What is left, in the largest type in the app, with no card around it.
 *
 * The earlier version was a coloured gradient panel. It looked designed and read
 * as decoration; sitting the number straight on the page with a hairline under
 * it makes it the thing you see first, which is what it is for.
 *
 * The bar underneath is the month running out. €200 left is comfortable on the
 * 28th and a problem on the 4th, and the figure alone cannot say which.
 */
@Composable
fun BalanceHero(
    label: String,
    amountMinor: Long,
    currency: String,
    caption: String,
    monthProgress: Float,
    short: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val accent = if (short) negativeColour() else MaterialTheme.colorScheme.onSurface

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Shape.card))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        AnimatedMoney(
            amountMinor = amountMinor,
            currency = currency,
            style = MoneyHero,
            colour = accent,
        )
        Spacer(Modifier.height(12.dp))
        MonthBar(monthProgress, if (short) negativeColour() else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The month as a thin line, filling as the days pass. */
@Composable
fun MonthBar(progress: Float, colour: Color, modifier: Modifier = Modifier) {
    val filled by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(900, easing = LinearOutSlowInEasing),
        label = "month",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(Shape.pill))
            .background(MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(filled)
                .height(4.dp)
                .clip(RoundedCornerShape(Shape.pill))
                .background(colour)
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
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
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

/** A capsule of text, for a due date or a count. */
@Composable
fun Pill(text: String, colour: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(Shape.pill))
            .background(colour.copy(alpha = 0.13f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = colour)
    }
}

/* --- charts ----------------------------------------------------------- */

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
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(112.dp)) {
                val stroke = 13.dp.toPx()
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
                    "Spent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(Money.formatShort(total, currency), style = MoneyMedium)
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            slices.take(5).forEach { (name, amount, colour) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
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
                .height(120.dp)
        ) {
            val slot = size.width / months.size
            val barWidth = (slot * 0.26f).coerceAtMost(10.dp.toPx())
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

/**
 * A category's icon in a soft circle, down the left of a list.
 *
 * These used to be emoji. Emoji were quicker and looked it — another platform's
 * illustration style, at whatever weight each one happened to be drawn at.
 */
@Composable
fun CategoryBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/** A thin bar for budget progress, coloured by how it is going. */
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
            .height(6.dp)
            .clip(RoundedCornerShape(Shape.pill))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(width)
                .height(6.dp)
                .clip(RoundedCornerShape(Shape.pill))
                .background(colour)
        )
    }
}
