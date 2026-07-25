package com.financialmanager.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financialmanager.app.ai.ChatMessage
import com.financialmanager.app.ai.Provider
import com.financialmanager.app.categorise.Categoriser
import com.financialmanager.app.data.TransactionRow
import com.financialmanager.app.money.Money
import com.financialmanager.app.plan.Upcoming
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

private val CATEGORY_COLOURS: Map<String, Color> =
    Categoriser.DEFAULTS.associate { it.name to Color(it.colour) }

@Composable
private fun categoryTint(category: String): Color =
    CATEGORY_COLOURS[category] ?: MaterialTheme.colorScheme.primary

/** The large title every screen opens with, in the platform's usual manner. */
@Composable
private fun ScreenTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

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
    val upcoming by model.upcoming.collectAsStateWithLifecycle()
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
            bottom = padding.calculateBottomPadding() + 96.dp,
        ),
    ) {
        item {
            ScreenTitle(
                month.month.getDisplayName(DateTextStyle.FULL, Locale.getDefault()),
            ) {
                MonthStepper(month) { model.stepMonth(it) }
            }
        }

        // The headline is what is left after what is promised, not the balance.
        item { SafeToSpendHeadline(plan, onOpenPlan) }

        // What is about to leave. Above everything else on purpose: it is the
        // part of the month you cannot see coming in a list of what you spent.
        if (upcoming.isNotEmpty()) {
            item {
                // Only the money leaving is totalled here. Netting income off
                // against it would produce a figure that is neither what you
                // owe nor what you will have.
                val leaving = upcoming.filterNot { it.isIncome }
                    .sumOf { it.commitment.amountMinor }
                GroupLabel("Coming up", trailing = {
                    if (leaving > 0) {
                        Text(
                            "${Money.format(leaving, plan.currency)} out",
                            style = MoneyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                })
                InsetGroup {
                    upcoming.take(4).forEachIndexed { index, due ->
                        UpcomingRow(due, onOpenPlan)
                        if (index < upcoming.take(4).lastIndex) Hairline(62.dp)
                    }
                }
            }
        }

        if (commitments.isEmpty()) {
            item { RecordDebtsPrompt(onOpenPlan) }
        }

        item {
            Spacer(Modifier.height(14.dp))
            // Intrinsic height so the two cards match whatever their notes say;
            // side-by-side cards of different heights is the tell of a layout
            // nobody looked at.
            Row(
                Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile(
                    label = "In this month",
                    amountMinor = dashboard.incomeMinor,
                    currency = dashboard.currency,
                    colour = positiveColour(),
                    note = "$count payments in all",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                StatTile(
                    label = "Out this month",
                    amountMinor = dashboard.spendMinor,
                    currency = dashboard.currency,
                    note = "net " + Money.format(
                        dashboard.netMinor, dashboard.currency, signed = true,
                    ),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        // What the assistant changed, on the main screen rather than buried in
        // the chat: an edit made by talking should be as visible as one made by
        // tapping, and as easy to put back.
        if (recentChanges.isNotEmpty()) {
            item {
                GroupLabel("Just changed")
                InsetGroup {
                    recentChanges.forEachIndexed { index, change ->
                        GroupRow(
                            title = change.summary,
                            trailing = {
                                TextButton(onClick = { model.undoChange(change) }) { Text("Undo") }
                            },
                        )
                        if (index < recentChanges.lastIndex) Hairline()
                    }
                }
            }
        }

        if (observations.isNotEmpty()) {
            item {
                GroupLabel("Worth knowing")
                InsetGroup {
                    observations.take(3).forEachIndexed { index, note ->
                        ObservationRow(note)
                        if (index < observations.take(3).lastIndex) Hairline(52.dp)
                    }
                }
            }
        }

        if (dashboard.byCategory.isNotEmpty()) {
            item {
                SectionCard("Where it went") {
                    CategoryDonut(
                        slices = dashboard.byCategory.map {
                            Triple(it.category, it.totalMinor, categoryTint(it.category))
                        },
                        currency = dashboard.currency,
                    )
                }
            }
        }

        if (dashboard.topMerchants.isNotEmpty()) {
            item {
                GroupLabel("Biggest this month")
                InsetGroup {
                    val top = dashboard.topMerchants.take(5)
                    top.forEachIndexed { index, merchant ->
                        GroupRow(
                            title = merchant.merchant,
                            subtitle = "${merchant.count} payment" +
                                if (merchant.count == 1) "" else "s",
                            trailing = {
                                Text(
                                    Money.format(merchant.totalMinor, dashboard.currency),
                                    style = MoneyMedium,
                                )
                            },
                        )
                        if (index < top.lastIndex) Hairline()
                    }
                }
            }
        }

        if (dashboard.months.size > 1) {
            item {
                SectionCard("In and out") {
                    CashFlowChart(
                        dashboard.months.map { row ->
                            Triple(shortMonth(row.month), row.incomeMinor, row.expenseMinor)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingRow(due: Upcoming, onClick: () -> Unit) {
    // A payment landing tomorrow is worth flagging; money arriving tomorrow is
    // not, so only the outgoings go red as they approach.
    val urgent = due.isImminent && !due.isIncome

    GroupRow(
        title = due.commitment.name,
        icon = CategoryIcons.forCommitment(due.commitment.kind.name),
        iconTint = when {
            due.isIncome -> positiveColour()
            urgent -> negativeColour()
            else -> MaterialTheme.colorScheme.primary
        },
        onClick = onClick,
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Money.format(
                        due.commitment.amountMinor, due.commitment.currency,
                        signed = due.isIncome,
                    ),
                    style = MoneyMedium,
                    color = if (due.isIncome) positiveColour()
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Pill(
                    due.whenText,
                    if (urgent) negativeColour() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** Two chevrons rather than a row of its own, to keep the title line intact. */
@Composable
private fun MonthStepper(month: LocalDate, onStep: (Long) -> Unit) {
    val isCurrentMonth = month == LocalDate.now().withDayOfMonth(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous month")
        }
        IconButton(
            onClick = { onStep(1) },
            enabled = !isCurrentMonth,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Next month")
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
        Text("Nothing here yet", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            "Import a statement from your bank app — the PDF it sends you, or a CSV " +
                "export. It is read on this phone and stays on it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (error != null) {
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(Shape.card),
                color = MaterialTheme.colorScheme.errorContainer,
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
        Spacer(Modifier.height(4.dp))
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

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding())
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            ScreenTitle("Activity")
            SearchField(search, model::setSearch)
            Spacer(Modifier.height(4.dp))
        }

        if (transactions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (search.isBlank()) "Nothing here yet." else "Nothing matches that.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        // One inset group per day, which is what makes a long list scannable:
        // the eye lands on the date, not on a wall of rows.
        val byDay = transactions.groupBy { it.bookedAt }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
        ) {
            byDay.forEach { (date, rows) ->
                item(key = "day-$date") { GroupLabel(dayHeading(date)) }
                item(key = "rows-$date") {
                    InsetGroup {
                        rows.forEachIndexed { index, txn ->
                            TransactionRowItem(txn) { editing = txn }
                            if (index < rows.lastIndex) Hairline(62.dp)
                        }
                    }
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
private fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text("Search") },
        leadingIcon = {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(Shape.control),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TransactionRowItem(txn: TransactionRow, onClick: () -> Unit) {
    GroupRow(
        title = txn.merchant.ifBlank { txn.description },
        subtitle = txn.category,
        icon = CategoryIcons[txn.category],
        iconTint = categoryTint(txn.category),
        onClick = onClick,
        trailing = {
            Text(
                Money.format(txn.amountMinor, txn.currency, signed = true),
                style = MoneyMedium,
                color = amountColour(txn.amountMinor),
            )
        },
    )
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
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryBadge(CategoryIcons[category.name], Color(category.colour))
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
            val pattern = if (date.year == today.year) "EEEE d MMMM" else "d MMMM yyyy"
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

    // The system photo picker: no storage permission, and it only ever hands
    // over the one image chosen.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            model.askWithPhoto(draft.trim(), it)
            draft = ""
        }
    }

    if (!hasKey) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("The assistant is off", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(10.dp))
            Text(
                "Add a Claude or Gemini API key in Settings and you can ask questions " +
                    "about your own spending, or hand it a photo of an instalment plan " +
                    "and have it filled in for you. Until you do, nothing about your " +
                    "money leaves this phone — and everything else works without it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = true,
        ) {
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Thinking…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Newest at the bottom, which reverseLayout gives without having to
            // drive a scroll position as messages arrive.
            items(messages.reversed()) { message -> ChatBubble(message) }
            if (messages.isEmpty()) {
                item { AssistantIntro() }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .padding(bottom = padding.calculateBottomPadding()),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !busy,
            ) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Send a photo")
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Ask, or tell it to change something") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                maxLines = 4,
            )
            Spacer(Modifier.width(6.dp))
            SendButton(enabled = !busy && draft.isNotBlank()) {
                model.ask(draft.trim())
                draft = ""
            }
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Shape.pill),
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(46.dp),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                Icons.Outlined.ArrowUpward,
                contentDescription = "Send",
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AssistantIntro() {
    Column {
        Text(
            "It can answer and it can act.",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            "\"How much went on groceries this month?\"",
            "\"Add my €120 gym membership, taken on the 5th\"",
            "\"I have €40 cash on me\"",
            "Or send a photo of a Klarna or loan screen and it fills in the plan.",
        ).forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Changes happen without asking first, and each one can be undone from the " +
                "home screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val fromUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = when {
                message.isError -> MaterialTheme.colorScheme.errorContainer
                fromUser -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surface
            },
            contentColor = when {
                message.isError -> MaterialTheme.colorScheme.onErrorContainer
                fromUser -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (fromUser) 18.dp else 5.dp,
                bottomEnd = if (fromUser) 5.dp else 18.dp,
            ),
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            Text(
                message.text,
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
            bottom = padding.calculateBottomPadding() + 96.dp,
        ),
    ) {
        item { ScreenTitle("Settings") }

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
                        shape = RoundedCornerShape(Shape.control),
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
    BalanceHero(
        label = if (plan.isOverstretched) "Short by" else "Safe to spend",
        amountMinor = kotlin.math.abs(plan.safeToSpendMinor),
        currency = plan.currency,
        caption = buildString {
            when {
                plan.committedMinor == 0L && plan.expectedIncomeMinor == 0L ->
                    append("Nothing committed yet — tap to add debts and regular payments.")
                plan.isOverstretched ->
                    append(
                        "${Money.format(plan.stillToLeaveMinor, plan.currency)} still has to " +
                            "leave this month, and there isn't enough for it."
                    )
                else ->
                    append(
                        "${Money.format(plan.dailyAllowanceMinor, plan.currency)} a day for " +
                            "the ${plan.daysLeft} days left, after " +
                            "${Money.format(plan.stillToLeaveMinor, plan.currency)} still to leave."
                    )
            }
            // Kept out of the figure above and said out loud instead, so it is
            // clear it has not been counted.
            if (plan.expectedIncomeMinor > 0) {
                append(
                    " ${Money.format(plan.expectedIncomeMinor, plan.currency)} is due in, " +
                        "not counted above."
                )
            }
        },
        monthProgress = today.dayOfMonth.toFloat() / today.lengthOfMonth(),
        short = plan.isOverstretched,
        onClick = onOpenPlan,
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
        com.financialmanager.app.plan.Severity.INFO -> MaterialTheme.colorScheme.primary
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            CategoryIcons.forSeverity(note.severity),
            contentDescription = null,
            tint = colour,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(note.title, style = MaterialTheme.typography.bodyLarge)
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
                    Text(dayHeading(date), style = MaterialTheme.typography.bodyMedium)
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
