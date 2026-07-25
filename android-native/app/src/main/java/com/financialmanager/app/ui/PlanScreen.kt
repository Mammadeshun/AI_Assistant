package com.financialmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financialmanager.app.data.Commitment
import com.financialmanager.app.data.CommitmentKind
import com.financialmanager.app.data.DetectedRecurring
import com.financialmanager.app.money.Money
import com.financialmanager.app.plan.BudgetProgress
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/**
 * Everything the user has promised to pay, and what that leaves.
 *
 * This is the part a statement cannot tell you. A loan taken out last year is
 * invisible until it takes the money, so it has to be written down.
 */
@Composable
fun PlanScreen(model: AppViewModel, padding: PaddingValues) {
    val plan by model.plan.collectAsStateWithLifecycle()
    val commitments by model.commitments.collectAsStateWithLifecycle()
    val suggestions by model.suggestions.collectAsStateWithLifecycle()
    val budgets by model.budgetProgress.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Commitment?>(null) }
    var adding by remember { mutableStateOf(false) }
    var budgetFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { model.findSuggestions() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SafeToSpendCard(plan) }

        if (suggestions.isNotEmpty()) {
            item {
                SectionCard("Regular payments found") {
                    Text(
                        "These repeat like clockwork in your history. Adding one lets the " +
                            "plan above account for it before it leaves.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    suggestions.take(6).forEach { suggestion ->
                        SuggestionRow(
                            suggestion = suggestion,
                            onAdd = { model.acceptSuggestion(suggestion, it) },
                            onDismiss = { model.dismissSuggestion(suggestion) },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                "Monthly commitments",
                trailing = { TextButton(onClick = { adding = true }) { Text("Add") } },
            ) {
                if (commitments.isEmpty()) {
                    Text(
                        "Nothing recorded yet. Add a debt, a loan, an instalment plan, rent " +
                            "or a subscription and the figure above starts telling the truth.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    commitments.forEach { commitment ->
                        CommitmentRow(
                            commitment = commitment,
                            onClick = { editing = commitment },
                            onToggle = { model.setCommitmentActive(commitment, it) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Every month", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            Money.format(plan.committedMinor, plan.currency),
                            style = MoneyMedium,
                        )
                    }
                    if (plan.totalOwedMinor > 0) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Still owed in total",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                Money.format(plan.totalOwedMinor, plan.currency),
                                style = MoneyMedium,
                                color = negativeColour(),
                            )
                        }
                    }
                }
            }
        }

        val payoffs = commitments.filter { it.active && it.remainingMinor != null }
        if (payoffs.isNotEmpty()) {
            item {
                SectionCard("When these finish") {
                    payoffs.sortedBy { it.monthsRemaining ?: Int.MAX_VALUE }.forEach { debt ->
                        val date = debt.payoffDate()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(debt.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${debt.monthsRemaining} payments left",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (date != null) {
                                Text(
                                    "${date.month.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())} ${date.year}",
                                    style = MoneyMedium,
                                    color = positiveColour(),
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                "Budgets",
                trailing = { TextButton(onClick = { budgetFor = "" }) { Text("Set") } },
            ) {
                if (budgets.isEmpty()) {
                    Text(
                        "Set a monthly limit on a category and this warns you when you're " +
                            "running ahead of the calendar, not just when it's gone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    budgets.forEach { budget ->
                        BudgetRow(budget) { budgetFor = budget.category }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        CommitmentEditor(
            existing = editing,
            currency = plan.currency,
            onDismiss = { adding = false; editing = null },
            onSave = {
                model.saveCommitment(it)
                adding = false; editing = null
            },
            onDelete = editing?.let { commitment ->
                {
                    model.deleteCommitment(commitment)
                    editing = null
                }
            },
        )
    }

    budgetFor?.let { category ->
        BudgetEditor(
            category = category,
            currency = plan.currency,
            existing = budgets.firstOrNull { it.category == category }?.limitMinor,
            onDismiss = { budgetFor = null },
            onSave = { name, limit ->
                model.setBudget(name, limit)
                budgetFor = null
            },
        )
    }
}

@Composable
private fun SafeToSpendCard(plan: com.financialmanager.app.plan.MonthPlan) {
    val short = plan.isOverstretched
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (short) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer
            )
            .padding(20.dp),
    ) {
        Text(
            if (short) "SHORT BY" else "SAFE TO SPEND",
            style = MaterialTheme.typography.labelMedium,
            color = if (short) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            Money.format(kotlin.math.abs(plan.safeToSpendMinor), plan.currency),
            style = MoneyLarge,
            color = if (short) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (short) {
                "Once this month's remaining payments leave, you're short. " +
                    "${plan.daysLeft} days to go."
            } else {
                "${Money.format(plan.dailyAllowanceMinor, plan.currency)} a day for the " +
                    "${plan.daysLeft} days left, after the " +
                    "${Money.format(plan.stillToLeaveMinor, plan.currency)} still due to leave."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (short) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun SuggestionRow(
    suggestion: DetectedRecurring,
    onAdd: (CommitmentKind) -> Unit,
    onDismiss: () -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    suggestion.merchant,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${Money.format(suggestion.typicalAmountMinor, suggestion.currency)} " +
                        "every ${suggestion.averageGapDays} days · " +
                        "${Money.format(suggestion.yearlyMinor, suggestion.currency)} a year",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { choosing = true }) { Text("Add") }
            TextButton(onClick = onDismiss) { Text("No") }
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("What is ${suggestion.merchant}?") },
            text = {
                Column {
                    CommitmentKind.entries.forEach { kind ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { choosing = false; onAdd(kind) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(kind.icon)
                            Spacer(Modifier.width(12.dp))
                            Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CommitmentRow(
    commitment: Commitment,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text(commitment.kind.icon) }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                commitment.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(commitment.kind.label)
                    commitment.dayOfMonth?.let { append(" · on the ${ordinal(it)}") }
                    commitment.monthsRemaining?.let { append(" · $it left") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            Money.format(commitment.amountMinor, commitment.currency),
            style = MoneyMedium,
            color = if (commitment.active) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Switch(checked = commitment.active, onCheckedChange = onToggle)
    }
}

@Composable
private fun BudgetRow(budget: BudgetProgress, onClick: () -> Unit) {
    val colour = when {
        budget.isOver -> negativeColour()
        budget.isAheadOfPace() -> MaterialTheme.colorScheme.tertiary
        else -> positiveColour()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(budget.category, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${Money.format(budget.spentMinor, budget.currency)} of " +
                    Money.format(budget.limitMinor, budget.currency),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { budget.fraction.coerceIn(0f, 1f) },
            color = colour,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        if (budget.isOver || budget.isAheadOfPace()) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (budget.isOver) {
                    "Over by ${Money.format(-budget.remainingMinor, budget.currency)}"
                } else {
                    "Running ahead of the month"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colour,
            )
        }
    }
}

@Composable
private fun CommitmentEditor(
    existing: Commitment?,
    currency: String,
    onDismiss: () -> Unit,
    onSave: (Commitment) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: CommitmentKind.SUBSCRIPTION) }
    var amount by remember { mutableStateOf(existing?.let { decimalOf(it.amountMinor, currency) } ?: "") }
    var day by remember { mutableStateOf(existing?.dayOfMonth?.toString() ?: "") }
    var owed by remember {
        mutableStateOf(existing?.remainingMinor?.let { decimalOf(it, currency) } ?: "")
    }

    val owesTotal = kind == CommitmentKind.DEBT || kind == CommitmentKind.INSTALMENT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add a commitment" else "Edit") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CommitmentKind.entries.take(2).forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CommitmentKind.entries.drop(2).forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount each month") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = day,
                    onValueChange = { day = it.filter(Char::isDigit).take(2) },
                    label = { Text("Day of the month (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (owesTotal) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = owed,
                        onValueChange = { owed = it },
                        label = { Text("Still owed in total") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Used to work out when it finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && parseMoney(amount, currency) != null,
                onClick = {
                    val monthly = parseMoney(amount, currency) ?: return@TextButton
                    onSave(
                        (existing ?: Commitment(
                            name = "", kind = kind, amountMinor = 0, currency = currency,
                        )).copy(
                            name = name.trim(),
                            kind = kind,
                            amountMinor = monthly,
                            currency = currency,
                            dayOfMonth = day.toIntOrNull()?.takeIf { it in 1..31 },
                            remainingMinor = if (owesTotal) parseMoney(owed, currency) else null,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun BudgetEditor(
    category: String,
    currency: String,
    existing: Long?,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
) {
    val categories = com.financialmanager.app.categorise.Categoriser.DEFAULTS
        .filter { it.kind == com.financialmanager.app.categorise.Categoriser.Kind.EXPENSE }
    var chosen by remember { mutableStateOf(category.ifBlank { categories.first().name }) }
    var limit by remember { mutableStateOf(existing?.let { decimalOf(it, currency) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monthly limit") },
        text = {
            Column {
                LazyColumn(Modifier.height(180.dp)) {
                    items(categories.size) { index ->
                        val option = categories[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { chosen = option.name }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(option.icon)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                option.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (option.name == chosen) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it },
                    label = { Text("Limit for $chosen") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Set it to zero to remove the budget.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parseMoney(limit, currency) != null,
                onClick = { onSave(chosen, parseMoney(limit, currency) ?: 0L) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Accepts a comma or a dot, because phone keyboards differ by locale. */
private fun parseMoney(text: String, currency: String): Long? {
    val cleaned = text.trim().replace(",", ".")
    if (cleaned.isEmpty()) return null
    val value = cleaned.toBigDecimalOrNull() ?: return null
    if (value.signum() < 0) return null
    return Money.toMinor(value, currency)
}

private fun decimalOf(minor: Long, currency: String) =
    Money.fromMinor(minor, currency).toPlainString()

private fun ordinal(day: Int): String {
    val suffix = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}
