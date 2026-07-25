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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financialmanager.app.ai.ChatMessage
import com.financialmanager.app.ai.Provider
import com.financialmanager.app.categorise.Categoriser
import com.financialmanager.app.data.TransactionRow
import com.financialmanager.app.money.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

private val CATEGORY_COLOURS: Map<String, Color> =
    Categoriser.DEFAULTS.associate { it.name to Color(it.colour) }

private val CATEGORY_ICONS: Map<String, String> =
    Categoriser.DEFAULTS.associate { it.name to it.icon }

/* ------------------------------------------------------------------ */
/* Dashboard                                                           */
/* ------------------------------------------------------------------ */
@Composable
fun DashboardScreen(
    model: AppViewModel,
    padding: PaddingValues,
    onImport: () -> Unit,
    onOpenPlan: () -> Unit,
) {
    val dashboard by model.dashboard.collectAsStateWithLifecycle()
    val month by model.month.collectAsStateWithLifecycle()
    val count by model.transactionCount.collectAsStateWithLifecycle()
    val plan by model.plan.collectAsStateWithLifecycle()
    val commitments by model.commitments.collectAsStateWithLifecycle()
    val observations by model.observations.collectAsStateWithLifecycle()
    val importError by model.lastImportError.collectAsStateWithLifecycle()
    val recentChanges by model.recentChanges.collectAsStateWithLifecycle()
    var addingManually by remember { mutableStateOf(false) }

    if (count == 0) {
        EmptyState(
            padding = padding,
            onImport = onImport,
            error = importError,
            onDismissError = model::dismissImportError,
            onAddManually = { addingManually = true },
        )
        if (addingManually) {
            ManualTransactionDialog(
                currency = plan.currency,
                onDismiss = { addingManually = false },
                onSave = { model.addManualTransaction(it); addingManually = false },
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MonthPicker(month) { model.stepMonth(it) } }

        // The headline is what is left after what is promised, not the balance.
        item { SafeToSpendHeadline(plan, onOpenPlan) }

        if (commitments.isEmpty()) {
            item { RecordDebtsPrompt(onOpenPlan) }
        }

        // What the assistant changed, on the main screen rather than buried in
        // the chat: an edit made by talking should be as visible as one made by
        // tapping, and as easy to put back.
        if (recentChanges.isNotEmpty()) {
            item {
                SectionCard("Recent changes") {
                    recentChanges.forEach { change ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                change.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { model.undoChange(change) }) { Text("Undo") }
                        }
                    }
                }
            }
        }

        if (observations.isNotEmpty()) {
            item {
                SectionCard("What stands out") {
                    observations.take(4).forEach { note -> ObservationRow(note) }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    label = "In",
                    amountMinor = dashboard.incomeMinor,
                    currency = dashboard.currency,
                    colour = positiveColour(),
                    note = "$count payments",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Out",
                    amountMinor = dashboard.spendMinor,
                    currency = dashboard.currency,
                    colour = negativeColour(),
                    note = "net " + Money.format(
                        dashboard.netMinor, dashboard.currency, signed = true,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (dashboard.byCategory.isNotEmpty()) {
            item {
                SectionCard("Where the money went") {
                    CategoryDonut(
                        slices = dashboard.byCategory.map {
                            Triple(
                                it.category,
                                it.totalMinor,
                                CATEGORY_COLOURS[it.category] ?: Color(0xFF9E9E9E),
                            )
                        },
                        currency = dashboard.currency,
                    )
                }
            }
        }

        if (dashboard.months.size > 1) {
            item {
                SectionCard("In and out, by month") {
                    CashFlowChart(
                        dashboard.months.map { row ->
                            Triple(shortMonth(row.month), row.incomeMinor, row.expenseMinor)
                        }
                    )
                }
            }
        }

        if (dashboard.topMerchants.isNotEmpty()) {
            item {
                SectionCard("Biggest this month") {
                    dashboard.topMerchants.forEach { merchant ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                merchant.merchant,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${merchant.count}×",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                Money.format(merchant.totalMinor, dashboard.currency),
                                style = MoneyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthPicker(month: LocalDate, onStep: (Long) -> Unit) {
    val isCurrentMonth = month == LocalDate.now().withDayOfMonth(1)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onStep(-1) }) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
        }
        Text(
            "${month.month.getDisplayName(DateTextStyle.FULL, Locale.getDefault())} ${month.year}",
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = { onStep(1) }, enabled = !isCurrentMonth) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
        }
    }
}

private fun shortMonth(key: String): String {
    val month = key.substringAfter('-').toIntOrNull() ?: return key
    return java.time.Month.of(month).getDisplayName(DateTextStyle.SHORT, Locale.getDefault())
}

@Composable
private fun EmptyState(
    padding: PaddingValues,
    onImport: () -> Unit,
    error: String? = null,
    onDismissError: () -> Unit = {},
    onAddManually: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("💷", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("Nothing here yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Import a statement from your bank app — the PDF it sends you, or a CSV " +
                "export. It is read on this phone and stays on it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        if (error != null) {
            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "That import didn't work",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    // The exact message, because it is the only clue about what
                    // went wrong and it should not vanish after four seconds.
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport) { Text("Import a statement") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onAddManually) { Text("Or add a transaction by hand") }
    }
}

/* ------------------------------------------------------------------ */
/* Transactions                                                        */
/* ------------------------------------------------------------------ */
@Composable
fun TransactionsScreen(model: AppViewModel, padding: PaddingValues) {
    val transactions by model.transactions.collectAsStateWithLifecycle(emptyList())
    val search by model.search.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TransactionRow?>(null) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = model::setSearch,
            placeholder = { Text("Search transactions") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp, bottom = 8.dp,
                ),
        )

        if (transactions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing matches that.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            var lastDate: LocalDate? = null
            transactions.forEach { txn ->
                if (txn.bookedAt != lastDate) {
                    lastDate = txn.bookedAt
                    item(key = "day-${txn.bookedAt}-${txn.id}") {
                        Text(
                            dayHeading(txn.bookedAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                        )
                    }
                }
                item(key = txn.id) {
                    TransactionRowItem(txn) { editing = txn }
                }
            }
        }
    }

    editing?.let { txn ->
        CategoryPicker(
            current = txn.category,
            onDismiss = { editing = null },
            onPick = { category ->
                model.setCategory(txn.id, category)
                editing = null
            },
        )
    }
}

@Composable
private fun TransactionRowItem(txn: TransactionRow, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(
            icon = CATEGORY_ICONS[txn.category] ?: "•",
            tint = CATEGORY_COLOURS[txn.category] ?: MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.width(13.dp))

        Column(Modifier.weight(1f)) {
            Text(
                txn.merchant.ifBlank { txn.description },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                txn.category,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(Modifier.width(10.dp))

        Text(
            Money.format(txn.amountMinor, txn.currency, signed = true),
            style = MoneyMedium,
            color = amountColour(txn.amountMinor),
        )
    }
}

@Composable
private fun CategoryPicker(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Category") },
        text = {
            LazyColumn {
                items(Categoriser.DEFAULTS) { category ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(category.name) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(category.icon)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            category.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (category.name == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        },
    )
}

private fun dayHeading(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> {
            val pattern = if (date.year == today.year) "EEE d MMM" else "d MMM yyyy"
            date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }
    }
}

/* ------------------------------------------------------------------ */
/* Assistant                                                           */
/* ------------------------------------------------------------------ */
@Composable
fun AssistantScreen(model: AppViewModel, padding: PaddingValues, onOpenSettings: () -> Unit) {
    val hasKey by model.apiKeySet.collectAsStateWithLifecycle()
    val messages by model.chat.collectAsStateWithLifecycle()
    val busy by model.chatBusy.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }

    if (!hasKey) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("✨", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(16.dp))
            Text("The assistant is off", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add a Claude or Gemini API key in Settings and you can ask questions " +
                    "about your own spending. Until you do, nothing about your money " +
                    "leaves this phone — and everything else works without it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpenSettings) { Text("Open Settings") }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding())
    ) {
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "Ask things like \"how much did I spend on groceries this month\" " +
                            "or \"what changed since last month\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(messages) { message -> ChatBubble(message) }
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Thinking…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .padding(bottom = padding.calculateBottomPadding()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Ask about your money") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val question = draft.trim()
                    if (question.isNotEmpty()) {
                        model.ask(question)
                        draft = ""
                    }
                },
                enabled = !busy && draft.isNotBlank(),
            ) { Text("Ask") }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val fromUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    message.isError -> MaterialTheme.colorScheme.errorContainer
                    fromUser -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (fromUser) 16.dp else 4.dp,
                bottomEnd = if (fromUser) 4.dp else 16.dp,
            ),
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Text(
                message.text,
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Settings                                                            */
/* ------------------------------------------------------------------ */
@Composable
fun SettingsScreen(model: AppViewModel, padding: PaddingValues, onImport: () -> Unit) {
    val hasKey by model.apiKeySet.collectAsStateWithLifecycle()
    val provider by model.provider.collectAsStateWithLifecycle()
    val count by model.transactionCount.collectAsStateWithLifecycle()
    val importError by model.lastImportError.collectAsStateWithLifecycle()
    val currency by model.currency.collectAsStateWithLifecycle()
    var keyDraft by remember { mutableStateOf("") }
    var chosenProvider by remember { mutableStateOf(provider) }
    var addingManually by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("Statements") {
                Text(
                    "$count transactions stored on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (importError != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Last import: $importError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImport) { Text("Import") }
                    OutlinedButton(onClick = { addingManually = true }) { Text("Add by hand") }
                }
            }
        }

        item {
            SectionCard("Assistant") {
                Text(
                    if (hasKey) {
                        "A ${provider.label} key is saved. It is kept in this phone's " +
                            "encrypted storage and sent only to ${provider.label}, only " +
                            "when you ask a question."
                    } else {
                        "Paste a key to switch the assistant on. It is stored encrypted on " +
                            "this phone and sent nowhere else."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!model.keyStorageIsPersistent) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This phone's secure storage is unavailable, so a key will only " +
                            "last until the app closes. It is never written down unencrypted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (hasKey) {
                    OutlinedButton(onClick = model::forgetApiKey) { Text("Remove key") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Provider.entries.forEach { option ->
                            FilterChip(
                                selected = chosenProvider == option,
                                onClick = { chosenProvider = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        placeholder = { Text(chosenProvider.keyHint) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Get a key at ${chosenProvider.console}" +
                            if (chosenProvider == Provider.GEMINI) {
                                " — Gemini's free tier covers a few questions a day."
                            } else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            model.saveApiKey(keyDraft, chosenProvider)
                            keyDraft = ""
                        },
                        enabled = keyDraft.isNotBlank(),
                    ) { Text("Save key") }
                }
            }
        }

        item {
            SectionCard("Your data") {
                Text(
                    "Everything is stored on this phone and nowhere else. There is no " +
                        "server, no account, and nothing to sign in to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { confirmingDelete = true }) {
                    Text("Delete everything", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (addingManually) {
        ManualTransactionDialog(
            currency = currency,
            onDismiss = { addingManually = false },
            onSave = { model.addManualTransaction(it); addingManually = false },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete everything?") },
            text = {
                Text(
                    "This removes all $count transactions from this phone. It cannot be " +
                        "undone, though you can import your statements again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    model.deleteEverything()
                    confirmingDelete = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep it") }
            },
        )
    }
}

/* ------------------------------------------------------------------ */
/* Dashboard pieces that depend on the plan                            */
/* ------------------------------------------------------------------ */

/**
 * The number the app is really about. A balance flatters you; this doesn't.
 */
@Composable
private fun SafeToSpendHeadline(
    plan: com.financialmanager.app.plan.MonthPlan,
    onOpenPlan: () -> Unit,
) {
    val today = LocalDate.now()
    HeroCard(
        label = if (plan.isOverstretched) "Short by" else "Safe to spend",
        amountMinor = kotlin.math.abs(plan.safeToSpendMinor),
        currency = plan.currency,
        caption = when {
            plan.committedMinor == 0L ->
                "Nothing committed yet — tap to add debts and regular payments."
            plan.isOverstretched ->
                "${Money.format(plan.stillToLeaveMinor, plan.currency)} still has to leave " +
                    "this month, and there isn't enough for it."
            else ->
                "${Money.format(plan.dailyAllowanceMinor, plan.currency)} a day for " +
                    "${plan.daysLeft} days, after " +
                    "${Money.format(plan.stillToLeaveMinor, plan.currency)} still to leave."
        },
        monthProgress = today.dayOfMonth.toFloat() / today.lengthOfMonth(),
        short = plan.isOverstretched,
        modifier = Modifier.clickable(onClick = onOpenPlan),
    )
}

/** Asks the question a statement can never answer for you. */
@Composable
private fun RecordDebtsPrompt(onOpenPlan: () -> Unit) {
    SectionCard("Do you have debts or instalments?") {
        Text(
            "Loans, card repayments, buy-now-pay-later, rent, subscriptions — none of it " +
                "shows up until it takes the money. Record it once and the figure above " +
                "knows about it from then on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenPlan) { Text("Set this up") }
    }
}

@Composable
private fun ObservationRow(note: com.financialmanager.app.plan.Observation) {
    val colour = when (note.severity) {
        com.financialmanager.app.plan.Severity.ALERT -> negativeColour()
        com.financialmanager.app.plan.Severity.WARNING -> MaterialTheme.colorScheme.tertiary
        com.financialmanager.app.plan.Severity.GOOD -> positiveColour()
        com.financialmanager.app.plan.Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (note.severity) {
        com.financialmanager.app.plan.Severity.ALERT -> "🚨"
        com.financialmanager.app.plan.Severity.WARNING -> "⚠️"
        com.financialmanager.app.plan.Severity.GOOD -> "✅"
        com.financialmanager.app.plan.Severity.INFO -> "💡"
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(icon)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(note.title, style = MaterialTheme.typography.bodyMedium, color = colour)
            if (note.detail.isNotBlank()) {
                Text(
                    note.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Adds one transaction by hand.
 *
 * A way in that does not depend on a file parsing correctly. If the importer
 * ever refuses a statement, the app is still usable rather than an empty shell.
 */
@Composable
fun ManualTransactionDialog(
    currency: String,
    onDismiss: () -> Unit,
    onSave: (ManualEntry) -> Unit,
) {
    var merchant by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var spending by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf(LocalDate.now()) }

    val parsed = amount.trim().replace(",", ".").toBigDecimalOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a transaction") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = spending,
                        onClick = { spending = true },
                        label = { Text("Money out") },
                    )
                    FilterChip(
                        selected = !spending,
                        onClick = { spending = false },
                        label = { Text("Money in") },
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant or description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount ($currency)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { date = date.minusDays(1) }) { Text("−1 day") }
                    Text(
                        dayHeading(date),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = { date = date.plusDays(1) },
                        enabled = date < LocalDate.now(),
                    ) { Text("+1 day") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = merchant.isNotBlank() && parsed != null && parsed.signum() > 0,
                onClick = {
                    val value = parsed ?: return@TextButton
                    onSave(
                        ManualEntry(
                            merchant = merchant.trim(),
                            amount = value,
                            spending = spending,
                            date = date,
                            currency = currency,
                        )
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

data class ManualEntry(
    val merchant: String,
    val amount: java.math.BigDecimal,
    val spending: Boolean,
    val date: LocalDate,
    val currency: String,
)
