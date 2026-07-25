package com.financialmanager.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financialmanager.app.ai.Assistant
import com.financialmanager.app.ai.ChatMessage
import com.financialmanager.app.ai.Provider
import com.financialmanager.app.ai.Secrets
import com.financialmanager.app.data.CategoryTotal
import com.financialmanager.app.data.FinanceDatabase
import com.financialmanager.app.data.MerchantTotal
import com.financialmanager.app.data.MonthTotal
import com.financialmanager.app.data.TransactionRow
import com.financialmanager.app.statement.ImportResult
import com.financialmanager.app.statement.StatementImporter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val secrets = Secrets(application)
    private val assistant = Assistant(dao)

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
        }
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
            _importState.value = try {
                val result = StatementImporter(getApplication()).import(uri)
                dao.currencies().firstOrNull()?.let { _currency.value = it }
                // Land on a month that actually has something in it.
                dao.latest()?.let { _month.value = it.withDayOfMonth(1) }
                ImportState.Done(result)
            } catch (error: Exception) {
                ImportState.Failed(error.message ?: "That statement could not be read.")
            }
        }
    }

    fun clearImportState() { _importState.value = ImportState.Idle }

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
        }
    }

    fun clearChat() { _chat.value = emptyList() }

    fun deleteEverything() {
        viewModelScope.launch { dao.deleteAll() }
    }
}
