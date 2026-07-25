package com.financialmanager.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financialmanager.app.ai.Assistant
import com.financialmanager.app.ai.ChatMessage
import com.financialmanager.app.ai.Actions
import com.financialmanager.app.ai.PhotoExtractor
import com.financialmanager.app.ai.Provider
import com.financialmanager.app.ai.Secrets
import com.financialmanager.app.categorise.Categoriser
import com.financialmanager.app.data.Budget
import com.financialmanager.app.data.CashHolding
import com.financialmanager.app.data.ChangeRecord
import com.financialmanager.app.data.CategoryTotal
import com.financialmanager.app.data.Commitment
import com.financialmanager.app.data.CommitmentKind
import com.financialmanager.app.data.DetectedRecurring
import com.financialmanager.app.data.FinanceDatabase
import com.financialmanager.app.data.RecurringDetector
import com.financialmanager.app.data.MerchantTotal
import com.financialmanager.app.data.MonthTotal
import com.financialmanager.app.data.TransactionRow
import com.financialmanager.app.money.Money
import com.financialmanager.app.plan.BudgetProgress
import com.financialmanager.app.plan.MonthPlan
import com.financialmanager.app.plan.Observation
import com.financialmanager.app.plan.Planner
import com.financialmanager.app.plan.Upcoming
import com.financialmanager.app.statement.ImportResult
import com.financialmanager.app.statement.StatementImporter
import com.financialmanager.app.widget.SafeToSpendWidget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class Dashboard(
    val currency: String = "EUR",
    val balanceMinor: Long = 0,
    val incomeMinor: Long = 0,
    val spendMinor: Long = 0,
    val byCategory: List<CategoryTotal> = emptyList(),
    val topMerchants: List<MerchantTotal> = emptyList(),
    val months: List<MonthTotal> = emptyList(),
) {
    val netMinor: Long get() = incomeMinor - spendMinor
}

/** What the import sheet is doing right now. */
sealed interface ImportState {
    data object Idle : ImportState
    data object Working : ImportState
    data class Done(val result: ImportResult) : ImportState
    data class Failed(val message: String) : ImportState
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val db = FinanceDatabase.get(application)
    private val dao = db.transactions()
    private val commitmentDao = db.commitments()
    private val budgetDao = db.budgets()
    private val cashDao = db.cash()
    private val photos = PhotoExtractor(application)
    private val secrets = Secrets(application)
    private val changeDao = db.changes()
    private val actions = Actions(db, changeDao) { _currency.value }
    private val assistant = Assistant(dao, commitmentDao, actions)

    val transactionCount: StateFlow<Int> =
        dao.count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _currency = MutableStateFlow("EUR")
    val currency: StateFlow<String> = _currency.asStateFlow()

    /** The month being shown on the dashboard. */
    private val _month = MutableStateFlow(LocalDate.now().withDayOfMonth(1))
    val month: StateFlow<LocalDate> = _month.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val _categoryFilter = MutableStateFlow<String?>(null)
    val categoryFilter: StateFlow<String?> = _categoryFilter.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val _chatBusy = MutableStateFlow(false)
    val chatBusy: StateFlow<Boolean> = _chatBusy.asStateFlow()

    private val _apiKeySet = MutableStateFlow(secrets.hasApiKey())
    val apiKeySet: StateFlow<Boolean> = _apiKeySet.asStateFlow()

    private val _provider = MutableStateFlow(secrets.provider())
    val provider: StateFlow<Provider> = _provider.asStateFlow()

    /** False when this phone's keystore wouldn't open, so a key lasts one run. */
    val keyStorageIsPersistent: Boolean get() = secrets.persistent

    init {
        // The dashboard totals are per currency and never converted, so the app
        // shows whichever currency the statements are actually in.
        viewModelScope.launch {
            dao.currencies().firstOrNull()?.let { _currency.value = it }
            refreshDerived()
        }
    }

    /** Figures that are worked out once rather than watched continuously. */
    private suspend fun refreshDerived() {
        val month = _month.value
        lastMonthSpend = dao
            .spendBetween(_currency.value, month.minusMonths(1), month.minusDays(1))
            .first()
        findSuggestions()
        SafeToSpendWidget.refresh(getApplication())
    }

    val dashboard: StateFlow<Dashboard> =
        combine(_currency, _month) { currency, month -> currency to month }
            .flatMapLatest { (currency, month) ->
                val start = month
                val end = month.plusMonths(1).minusDays(1)
                val since = month.minusMonths(11)

                combine(
                    dao.balance(currency),
                    dao.incomeBetween(currency, start, end),
                    dao.spendBetween(currency, start, end),
                    dao.spendByCategory(currency, start, end),
                    dao.topMerchants(currency, start, end, 8),
                    dao.monthlyTotals(currency, since),
                ) { values ->
                    @Suppress("UNCHECKED_CAST")
                    Dashboard(
                        currency = currency,
                        balanceMinor = values[0] as Long,
                        incomeMinor = values[1] as Long,
                        spendMinor = values[2] as Long,
                        byCategory = values[3] as List<CategoryTotal>,
                        topMerchants = values[4] as List<MerchantTotal>,
                        months = values[5] as List<MonthTotal>,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Dashboard())

    val commitments: StateFlow<List<Commitment>> = commitmentDao.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val budgets: StateFlow<List<Budget>> = budgetDao.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * What is about to be taken, soonest first.
     *
     * The one thing a statement genuinely cannot tell you, and the thing worth
     * putting near the top of the home screen: money already on its way out.
     */
    val upcoming: StateFlow<List<Upcoming>> = commitments
        .map { Planner.upcoming(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * What is left once the promises are taken out — the number the whole Plan
     * tab and the home-screen widget are built around.
     */
    val plan: StateFlow<MonthPlan> =
        combine(_currency, _month) { currency, month -> currency to month }
            .flatMapLatest { (currency, month) ->
                val start = month
                val end = month.plusMonths(1).minusDays(1)
                combine(
                    combine(dao.balance(currency), cashDao.forCurrency(currency)) { bank, cash ->
                        // Cash in a pocket is real money and no statement knows
                        // about it, so it belongs in the balance being planned
                        // against.
                        bank + (cash?.amountMinor ?: 0L)
                    },
                    dao.incomeBetween(currency, start, end),
                    dao.spendBetween(currency, start, end),
                    commitmentDao.active(),
                    commitmentDao.totalOwed(currency),
                ) { balance, income, spent, commitments, owed ->
                    Planner.monthPlan(
                        currency = currency,
                        balanceMinor = balance,
                        incomeMinor = income,
                        spentMinor = spent,
                        commitments = commitments,
                        totalOwedMinor = owed,
                    )
                }
            }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                Planner.monthPlan("EUR", 0, 0, 0, emptyList(), 0),
            )

    val budgetProgress: StateFlow<List<BudgetProgress>> =
        combine(_currency, _month) { currency, month -> currency to month }
            .flatMapLatest { (currency, month) ->
                combine(
                    budgetDao.all(),
                    dao.spendByCategory(currency, month, month.plusMonths(1).minusDays(1)),
                ) { budgets, spend -> Planner.budgetProgress(budgets, spend) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Regular payments spotted in the history and not yet turned into commitments. */
    private val _suggestions = MutableStateFlow<List<DetectedRecurring>>(emptyList())
    val suggestions: StateFlow<List<DetectedRecurring>> = _suggestions.asStateFlow()

    val observations: StateFlow<List<Observation>> =
        combine(plan, budgetProgress, commitments) { plan, budgets, commitments ->
            Planner.observations(plan, budgets, commitments, lastMonthSpend)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var lastMonthSpend: Long = 0

    val transactions: Flow<List<TransactionRow>> =
        combine(_search, _categoryFilter) { query, category -> query to category }
            .flatMapLatest { (query, category) -> dao.search(query, category, 500, 0) }

    fun setSearch(value: String) { _search.value = value }

    fun setCategoryFilter(value: String?) { _categoryFilter.value = value }

    fun stepMonth(months: Long) {
        val next = _month.value.plusMonths(months)
        // Never step past the current month: there is nothing there to show.
        if (next <= LocalDate.now().withDayOfMonth(1)) _month.value = next
    }

    fun setCategory(id: Long, category: String) {
        viewModelScope.launch { dao.setCategory(id, category) }
    }

    fun importStatement(uri: Uri) {
        viewModelScope.launch {
            _importState.value = ImportState.Working
            _lastImportError.value = null
            _importState.value = try {
                val result = StatementImporter(getApplication()).import(uri)
                dao.currencies().firstOrNull()?.let { _currency.value = it }
                // Land on a month that actually has something in it.
                dao.latest()?.let { _month.value = it.withDayOfMonth(1) }
                refreshDerived()
                ImportState.Done(result)
            } catch (error: Throwable) {
                // Throwable rather than Exception on purpose: an OutOfMemoryError
                // is an Error, and catching only Exception let the import die
                // silently with nothing on screen.
                val message = error.message
                    ?: "${error.javaClass.simpleName} while reading that file."
                _lastImportError.value = message
                ImportState.Failed(message)
            }
        }
    }

    fun clearImportState() { _importState.value = ImportState.Idle }

    /** A transaction typed in by hand, for when there is no file to import. */
    fun addManualTransaction(entry: ManualEntry) {
        viewModelScope.launch {
            val minor = Money.toMinor(entry.amount, entry.currency)
                .let { if (entry.spending) -it else it }
            val verdict = Categoriser.categorise(entry.merchant, entry.merchant, minor)
            dao.insertAll(
                listOf(
                    TransactionRow(
                        // Time-stamped so two identical entries stay distinct.
                        externalId = "manual:${System.currentTimeMillis()}",
                        bookedAt = entry.date,
                        amountMinor = minor,
                        currency = entry.currency,
                        description = entry.merchant,
                        merchant = entry.merchant,
                        category = verdict.category,
                        isTransfer = verdict.isTransfer,
                    )
                )
            )
            _currency.value = entry.currency
            refreshDerived()
        }
    }

    /**
     * The last import failure, kept until dismissed.
     *
     * A snackbar disappears in four seconds, which is no use when the message is
     * the only clue about why a statement would not read.
     */
    private val _lastImportError = MutableStateFlow<String?>(null)
    val lastImportError: StateFlow<String?> = _lastImportError.asStateFlow()

    fun dismissImportError() { _lastImportError.value = null }

    /* --- commitments ------------------------------------------------- */

    fun saveCommitment(commitment: Commitment) {
        viewModelScope.launch {
            if (commitment.id == 0L) commitmentDao.insert(commitment)
            else commitmentDao.update(commitment)
            refreshWidget()
        }
    }

    fun deleteCommitment(commitment: Commitment) {
        viewModelScope.launch {
            commitmentDao.delete(commitment)
            refreshWidget()
        }
    }

    fun setCommitmentActive(commitment: Commitment, active: Boolean) {
        saveCommitment(commitment.copy(active = active))
    }

    /** Turns a spotted recurring payment into a commitment the plan knows about. */
    fun acceptSuggestion(suggestion: DetectedRecurring, kind: CommitmentKind) {
        viewModelScope.launch {
            commitmentDao.insert(
                Commitment(
                    name = suggestion.merchant,
                    kind = kind,
                    amountMinor = suggestion.typicalAmountMinor,
                    currency = suggestion.currency,
                    dayOfMonth = suggestion.dayOfMonth,
                    autoDetected = true,
                )
            )
            dismissSuggestion(suggestion)
            refreshWidget()
        }
    }

    fun dismissSuggestion(suggestion: DetectedRecurring) {
        _suggestions.value = _suggestions.value.filterNot { it.merchant == suggestion.merchant }
        dismissedSuggestions += suggestion.merchant.lowercase()
    }

    private val dismissedSuggestions = mutableSetOf<String>()

    /**
     * Looks for regular payments in the last two years, minus anything already
     * recorded or waved away.
     */
    fun findSuggestions() {
        viewModelScope.launch {
            val rows = dao.since(LocalDate.now().minusYears(2))
            val existing = commitmentDao.activeNow().map { it.name.lowercase() }.toSet()
            _suggestions.value = RecurringDetector.detect(rows).filterNot {
                it.merchant.lowercase() in existing || it.merchant.lowercase() in dismissedSuggestions
            }
        }
    }

    /* --- cash -------------------------------------------------------- */

    val cash: StateFlow<Long> = _currency
        .flatMapLatest { cashDao.forCurrency(it) }
        .map { it?.amountMinor ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setCash(amountMinor: Long) {
        viewModelScope.launch {
            if (amountMinor <= 0) cashDao.clear(_currency.value)
            else cashDao.set(CashHolding(_currency.value, amountMinor))
            SafeToSpendWidget.refresh(getApplication())
        }
    }

    /* --- what the assistant changed ---------------------------------- */

    /**
     * Shown on the dashboard, not only in the chat: an edit made by talking
     * should be as visible as one made by tapping.
     */
    val recentChanges: StateFlow<List<ChangeRecord>> = changeDao.recent(8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun undoChange(record: ChangeRecord) {
        viewModelScope.launch {
            actions.undo(record)
            SafeToSpendWidget.refresh(getApplication())
        }
    }

    /* --- budgets ----------------------------------------------------- */

    fun setBudget(category: String, limitMinor: Long) {
        viewModelScope.launch {
            if (limitMinor <= 0) budgetDao.delete(category)
            else budgetDao.upsert(Budget(category, limitMinor, _currency.value))
        }
    }

    private fun refreshWidget() {
        viewModelScope.launch { SafeToSpendWidget.refresh(getApplication()) }
    }

    fun saveApiKey(key: String, provider: Provider) {
        val trimmed = key.trim()
        // The key's own prefix is better evidence than the chosen radio button,
        // which is easy to leave on the wrong one.
        val actual = Provider.guessFrom(trimmed) ?: provider
        secrets.setApiKey(trimmed, actual)
        _provider.value = actual
        _apiKeySet.value = secrets.hasApiKey()
    }

    fun forgetApiKey() {
        secrets.clearApiKey()
        _apiKeySet.value = false
        _provider.value = secrets.provider()
        _chat.value = emptyList()
    }

    fun ask(question: String) {
        val key = secrets.apiKey() ?: return
        viewModelScope.launch {
            _chat.value = _chat.value + ChatMessage(role = "user", text = question)
            _chatBusy.value = true
            val reply = try {
                assistant.ask(key, secrets.provider(), _chat.value, _currency.value)
            } catch (error: Exception) {
                ChatMessage(
                    role = "assistant",
                    text = error.message ?: "The assistant could not be reached.",
                    isError = true,
                )
            }
            _chat.value = _chat.value + reply
            _chatBusy.value = false
            if (reply.changed) SafeToSpendWidget.refresh(getApplication())
        }
    }

    /**
     * A picture sent to the assistant, with whatever was typed alongside it.
     *
     * What it finds is saved immediately rather than shown as a list to tick
     * through — the same rule as everything else the assistant does, and each
     * line lands in "Just changed" on the home screen with an Undo next to it.
     */
    fun askWithPhoto(note: String, uri: Uri) {
        val key = secrets.apiKey() ?: return
        viewModelScope.launch {
            _chat.value = _chat.value + ChatMessage(
                role = "user",
                text = if (note.isBlank()) "(photo)" else "$note  (photo)",
            )
            _chatBusy.value = true

            val reply = try {
                val extraction = photos.extract(
                    key, secrets.provider(), uri, _currency.value, note.takeIf { it.isNotBlank() },
                )
                val added = actions.addProposed(extraction.proposals)
                ChatMessage(
                    role = "assistant",
                    text = buildString {
                        if (extraction.summary.isNotBlank()) {
                            appendLine(extraction.summary)
                            appendLine()
                        }
                        append(added.joinToString("\n") { "• $it" })
                    }.trim(),
                    changed = added.isNotEmpty(),
                )
            } catch (error: Throwable) {
                ChatMessage(
                    role = "assistant",
                    text = error.message ?: "That picture could not be read.",
                    // "There is no payment plan in this" is an answer, not a
                    // fault, so it is not shown as one.
                    isError = error !is PhotoExtractor.NothingFound,
                )
            }

            _chat.value = _chat.value + reply
            _chatBusy.value = false
            if (reply.changed) SafeToSpendWidget.refresh(getApplication())
        }
    }

    fun clearChat() { _chat.value = emptyList() }

    fun deleteEverything() {
        viewModelScope.launch { dao.deleteAll() }
    }
}
