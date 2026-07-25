package com.financialmanager.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
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
 *
 * The screen used to carry seven cards, including a second copy of the home
 * screen's headline. It is now four groups, in the order the questions actually
 * get asked: what is left, what I hold, what I owe every month, what I have
 * capped.
 */
@Composable
fun PlanScreen(model: AppViewModel, padding: PaddingValues) {
    val plan by model.plan.collectAsStateWithLifecycle()
    val commitments by model.commitments.collectAsStateWithLifecycle()
    val suggestions by model.suggestions.collectAsStateWithLifecycle()
    val budgets by model.budgetProgress.collectAsStateWithLifecycle()
    val cash by model.cash.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Commitment?>(null) }
    var adding by remember { mutableStateOf(false) }
    var budgetFor by remember { mutableStateOf<String?>(null) }
    var editingCash by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { model.findSuggestions() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 96.dp,
        ),
    ) {
        item { PlanTitle() }

        item {
            InsetGroup {
                MoneyRow(
                    label = if (plan.isOverstretched) "Short by" else "Safe to spend",
                    amountMinor = kotlin.math.abs(plan.safeToSpendMinor),
                    currency = plan.currency,
                    colour = if (plan.isOverstretched) negativeColour()
                    else MaterialTheme.colorScheme.onSurface,
                    strong = true,
                )
                Hairline()
                MoneyRow("Committed every month", plan.committedMinor, plan.currency)
                Hairline()
                MoneyRow("Still to leave this month", plan.stillToLeaveMinor, plan.currency)
                if (plan.expectedIncomeMinor > 0) {
                    Hairline()
                    MoneyRow(
                        "Still to come in", plan.expectedIncomeMinor, plan.currency,
                        colour = positiveColour(),
                    )
                }
                if (plan.totalOwedMinor > 0) {
                    Hairline()
                    MoneyRow(
                        "Still owed in total", plan.totalOwedMinor, plan.currency,
                        colour = negativeColour(),
                    )
                }
            }
        }

        item {
            GroupLabel("What you hold")
            InsetGroup {
                MoneyRow("In the account", plan.balanceMinor - cash, plan.currency)
                Hairline()
                GroupRow(
                    title = "Cash in your pocket",
                    subtitle = if (cash > 0) "Counted in everything above"
                    else "No statement knows about this — say and it counts",
                    onClick = { editingCash = true },
                    chevron = true,
                    trailing = {
                        Text(
                            if (cash > 0) Money.format(cash, plan.currency) else "Add",
                            style = MoneyMedium,
                            color = if (cash > 0) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }

        item {
            GroupLabel("Every month", trailing = {
                TextButton(onClick = { adding = true }) { Text("Add") }
            })
            if (commitments.isEmpty()) {
                InsetGroup {
                    Text(
                        "Nothing recorded yet. Add a debt, a loan, an instalment plan, rent " +
                            "or a subscription and the figure above starts telling the truth.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                InsetGroup {
                    commitments.forEachIndexed { index, commitment ->
                        CommitmentRow(
                            commitment = commitment,
                            onClick = { editing = commitment },
                            onToggle = { model.setCommitmentActive(commitment, it) },
                        )
                        if (index < commitments.lastIndex) Hairline(62.dp)
                    }
                }
            }
        }

        if (suggestions.isNotEmpty()) {
            item {
                GroupLabel("Looks regular in your history")
                InsetGroup {
                    val shown = suggestions.take(3)
                    shown.forEachIndexed { index, suggestion ->
                        SuggestionRow(
                            suggestion = suggestion,
                            onAdd = { model.acceptSuggestion(suggestion, it) },
                            onDismiss = { model.dismissSuggestion(suggestion) },
                        )
                        if (index < shown.lastIndex) Hairline()
                    }
                }
            }
        }

        item {
            GroupLabel("Budgets", trailing = {
                TextButton(onClick = { budgetFor = "" }) { Text("Set") }
            })
            InsetGroup {
                if (budgets.isEmpty()) {
                    Text(
                        "Cap a category and this warns you when you're running ahead of the " +
                            "calendar, not just when it's gone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    budgets.forEachIndexed { index, budget ->
                        BudgetRow(budget) { budgetFor = budget.category }
                        if (index < budgets.lastIndex) Hairline()
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

    if (editingCash) {
        CashEditor(
            currency = plan.currency,
            existing = cash,
            onDismiss = { editingCash = false },
            onSave = { model.setCash(it); editingCash = false },
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
private fun PlanTitle() {
    Column(Modifier.padding(top = 4.dp, bottom = 6.dp)) {
        Text("Plan", style = MaterialTheme.typography.headlineLarge)
    }
}

/** A label on the left and a figure on the right: the whole of a summary group. */
@Composable
private fun MoneyRow(
    label: String,
    amountMinor: Long,
    currency: String,
    colour: Color? = null,
    strong: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (strong) 14.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        AnimatedMoney(
            amountMinor = amountMinor,
            currency = currency,
            style = if (strong) MoneyLarge else MoneyMedium,
            colour = colour ?: MaterialTheme.colorScheme.onSurface,
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

    GroupRow(
        title = suggestion.merchant,
        subtitle = "${Money.format(suggestion.typicalAmountMinor, suggestion.currency)} " +
            "every ${suggestion.averageGapDays} days · " +
            "${Money.format(suggestion.yearlyMinor, suggestion.currency)} a year",
        trailing = {
            TextButton(onClick = { choosing = true }) { Text("Add") }
            TextButton(onClick = onDismiss) {
                Text("No", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )

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
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CategoryBadge(
                                CategoryIcons.forCommitment(kind.name),
                                MaterialTheme.colorScheme.primary,
                            )
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
    val finishes = commitment.payoffDate()?.let {
        "finishes ${it.month.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())} ${it.year}"
    }

    GroupRow(
        title = commitment.name,
        subtitle = buildString {
            append(commitment.kind.label)
            commitment.dayOfMonth?.let { append(" · on the ${ordinal(it)}") }
            finishes?.let { append(" · $it") }
        },
        icon = CategoryIcons.forCommitment(commitment.kind.name),
        iconTint = if (commitment.active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline,
        onClick = onClick,
        trailing = {
            Text(
                Money.format(
                    commitment.amountMinor, commitment.currency,
                    signed = commitment.kind.isIncome,
                ),
                style = MoneyMedium,
                color = when {
                    !commitment.active -> MaterialTheme.colorScheme.onSurfaceVariant
                    commitment.kind.isIncome -> positiveColour()
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.width(6.dp))
            Switch(checked = commitment.active, onCheckedChange = onToggle)
        },
    )
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(CategoryIcons[budget.category], colour)
            Spacer(Modifier.width(12.dp))
            Text(
                budget.category,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${Money.format(budget.spentMinor, budget.currency)} of " +
                    Money.format(budget.limitMinor, budget.currency),
                style = MoneyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(fraction = budget.fraction, colour = colour)
        if (budget.isOver || budget.isAheadOfPace()) {
            Spacer(Modifier.height(6.dp))
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
    var amount by remember {
        mutableStateOf(existing?.let { decimalOf(it.amountMinor, currency) } ?: "")
    }
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
                    label = {
                        Text(
                            if (kind.isIncome) "Amount coming in each month"
                            else "Amount each month"
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = day,
                    onValueChange = { day = it.filter(Char::isDigit).take(2) },
                    label = { Text("Day of the month") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (kind.isIncome) {
                        "With a day, it shows under Coming up before it arrives. It is not " +
                            "added to what's safe to spend until it does."
                    } else {
                        "With a day, it shows up under Coming up before it leaves."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CategoryBadge(CategoryIcons[option.name], Color(option.colour))
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

/**
 * Lets the user say how much cash they are carrying.
 *
 * A bank export cannot know this, and without it every figure the app shows is
 * short by whatever is in a pocket.
 */
@Composable
fun CashEditor(
    currency: String,
    existing: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
) {
    var amount by remember {
        mutableStateOf(if (existing > 0) decimalOf(existing, currency) else "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cash on hand") },
        text = {
            Column {
                Text(
                    "Notes and coins you are carrying. It is added to the balance " +
                        "everything else is worked out from, and only you can update it — " +
                        "no statement knows what is in your wallet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount ($currency)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Set it to zero to remove it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = amount.isBlank() || parseMoney(amount, currency) != null,
                onClick = { onSave(parseMoney(amount, currency) ?: 0L) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
