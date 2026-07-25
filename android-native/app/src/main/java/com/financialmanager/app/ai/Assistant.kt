package com.financialmanager.app.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.financialmanager.app.data.CommitmentDao
import com.financialmanager.app.data.TransactionDao
import com.financialmanager.app.money.Money
import com.financialmanager.app.plan.Planner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    /** True when this turn changed the user's data, not just talked about it. */
    val changed: Boolean = false,
)

/**
 * Stores the Anthropic API key in Android's encrypted preferences, which are
 * backed by the device keystore.
 *
 * The key is the user's own, tied to their billing, and it never leaves the
 * phone except in the Authorization header of a request to Anthropic.
 */
class Secrets(context: Context) {

    /**
     * Null when the device keystore refuses to co-operate. That happens on some
     * OEM builds and after a keystore reset, and it must not take the app down
     * on launch — nor fall back to writing an API key in plain text. When it
     * happens the key is held for this run only, and [persistent] is false so
     * the UI can say so.
     */
    private val prefs: SharedPreferences? = try {
        EncryptedSharedPreferences.create(
            context,
            "secrets",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        null
    }

    val persistent: Boolean get() = prefs != null

    private var sessionKey: String? = null
    private var sessionProvider: String? = null

    fun apiKey(): String? =
        (prefs?.getString(KEY, null) ?: sessionKey)?.takeIf { it.isNotBlank() }

    fun hasApiKey(): Boolean = apiKey() != null

    fun provider(): Provider = Provider.parse(prefs?.getString(PROVIDER, null) ?: sessionProvider)

    fun setApiKey(value: String, provider: Provider) {
        if (prefs != null) {
            prefs.edit().putString(KEY, value).putString(PROVIDER, provider.name).apply()
        } else {
            sessionKey = value
            sessionProvider = provider.name
        }
    }

    fun clearApiKey() {
        prefs?.edit()?.remove(KEY)?.remove(PROVIDER)?.apply()
        sessionKey = null
        sessionProvider = null
    }

    private companion object {
        const val KEY = "api_key"
        const val PROVIDER = "api_provider"
    }
}

/**
 * Which service answers the questions.
 *
 * Both are pay-as-you-go on your own key. Gemini also has a free tier that
 * comfortably covers a few questions a day, so the choice mostly comes down to
 * which account you already have credit on.
 */
enum class Provider(val label: String, val keyHint: String, val console: String) {
    ANTHROPIC("Claude", "sk-ant-…", "console.anthropic.com"),
    GEMINI("Gemini", "AIza…", "aistudio.google.com/apikey");

    companion object {
        fun parse(value: String?): Provider = entries.firstOrNull { it.name == value } ?: ANTHROPIC

        /** Both services issue keys with a recognisable prefix. */
        fun guessFrom(key: String): Provider? = when {
            key.startsWith("sk-ant-") -> ANTHROPIC
            key.startsWith("AIza") -> GEMINI
            else -> null
        }
    }
}

/**
 * Answers questions about the user's own spending.
 *
 * The phone holds the data, so rather than giving the model tools to query with,
 * the figures it needs are worked out first and sent as a compact summary. That
 * keeps it to one request per question, and means only totals and merchant names
 * ever leave the device — never a full transaction history.
 */
class Assistant(
    private val dao: TransactionDao,
    private val commitmentDao: CommitmentDao,
    private val actions: Actions,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ask(
        apiKey: String,
        provider: Provider,
        history: List<ChatMessage>,
        currency: String,
    ): ChatMessage = withContext(Dispatchers.IO) {
        val system = SYSTEM_PROMPT + "\n\n" + buildFacts(currency)
        val turns = history.filterNot { it.isError }

        // Anything the assistant did, collected so the reply can say so plainly
        // even if the model forgets to mention it.
        val done = mutableListOf<String>()
        var outcomes = emptyList<ToolOutcome>()

        repeat(MAX_ROUNDS) {
            val request = when (provider) {
                Provider.ANTHROPIC -> anthropicRequest(apiKey, system, turns, outcomes)
                Provider.GEMINI -> geminiRequest(apiKey, system, turns, outcomes)
            }

            val parsed = http.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw Exception(friendlyError(provider, response.code, payload))
                }
                json.parseToJsonElement(payload).jsonObject
            }

            val calls = when (provider) {
                Provider.ANTHROPIC -> anthropicCalls(parsed)
                Provider.GEMINI -> geminiCalls(parsed)
            }

            if (calls.isEmpty()) {
                val text = when (provider) {
                    Provider.ANTHROPIC -> readAnthropic(parsed)
                    Provider.GEMINI -> readGemini(parsed)
                }
                return@withContext ChatMessage(
                    "assistant",
                    buildString {
                        append(text.ifBlank { if (done.isEmpty()) "No answer came back." else "" })
                        if (done.isNotEmpty()) {
                            if (isNotEmpty()) append("\n\n")
                            append(done.joinToString("\n") { "• $it" })
                        }
                    }.trim(),
                    changed = done.isNotEmpty(),
                )
            }

            // Carried out without stopping to ask, which is the point of being
            // able to talk to it. Each one is undoable afterwards.
            outcomes = calls.map { call ->
                val result = actions.execute(call)
                if (call.name != "list_commitments") done += result
                ToolOutcome(call, result)
            }
        }

        ChatMessage(
            "assistant",
            (done.takeIf { it.isNotEmpty() }?.joinToString("\n") { "• $it" }
                ?: "That took more steps than expected — nothing further was changed."),
            changed = done.isNotEmpty(),
        )
    }

    private fun anthropicRequest(
        apiKey: String,
        system: String,
        turns: List<ChatMessage>,
        outcomes: List<ToolOutcome>,
    ): Request {
        val body = buildJsonObject {
            put("model", ANTHROPIC_MODEL)
            put("max_tokens", 1024)
            put("system", system)
            put("tools", buildJsonArray {
                Actions.DECLARATIONS.forEach { declaration ->
                    val tool = declaration.jsonObject
                    add(buildJsonObject {
                        put("name", tool["name"]!!)
                        put("description", tool["description"]!!)
                        put("input_schema", tool["parameters"]!!)
                    })
                }
            })
            put("messages", buildJsonArray {
                turns.forEach { message ->
                    add(buildJsonObject {
                        put("role", if (message.role == "user") "user" else "assistant")
                        put("content", message.text)
                    })
                }
                if (outcomes.isNotEmpty()) {
                    // The assistant's tool requests, then their results, in the
                    // shape the API expects them back.
                    add(buildJsonObject {
                        put("role", "assistant")
                        put("content", buildJsonArray {
                            outcomes.forEach { outcome ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", outcome.call.id ?: outcome.call.name)
                                    put("name", outcome.call.name)
                                    put("input", outcome.call.arguments)
                                })
                            }
                        })
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            outcomes.forEach { outcome ->
                                add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", outcome.call.id ?: outcome.call.name)
                                    put("content", outcome.result)
                                })
                            }
                        })
                    })
                }
            })
        }

        return Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun geminiRequest(
        apiKey: String,
        system: String,
        turns: List<ChatMessage>,
        outcomes: List<ToolOutcome>,
    ): Request {
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject { put("function_declarations", Actions.DECLARATIONS) })
            })
            putJsonObject("system_instruction") {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            }
            put("contents", buildJsonArray {
                turns.forEach { message ->
                    add(buildJsonObject {
                        // Gemini calls the assistant "model", not "assistant".
                        put("role", if (message.role == "user") "user" else "model")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", message.text) })
                        })
                    })
                }
                if (outcomes.isNotEmpty()) {
                    add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            outcomes.forEach { outcome ->
                                add(buildJsonObject {
                                    putJsonObject("functionCall") {
                                        put("name", outcome.call.name)
                                        put("args", outcome.call.arguments)
                                    }
                                })
                            }
                        })
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            outcomes.forEach { outcome ->
                                add(buildJsonObject {
                                    putJsonObject("functionResponse") {
                                        put("name", outcome.call.name)
                                        putJsonObject("response") {
                                            put("result", outcome.result)
                                        }
                                    }
                                })
                            }
                        })
                    })
                }
            })
        }

        // The key travels in a header rather than the query string, so it cannot
        // be left behind in a proxy log or a crash report URL.
        return Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    /** The tool requests in a reply, if it made any. */
    private fun anthropicCalls(parsed: JsonObject): List<ToolCall> =
        parsed["content"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "tool_use" }
            .mapNotNull { block ->
                val name = block["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                ToolCall(
                    id = block["id"]?.jsonPrimitive?.content,
                    name = name,
                    arguments = block["input"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            }

    private fun geminiCalls(parsed: JsonObject): List<ToolCall> =
        parsed["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            .mapNotNull { part ->
                val call = part.jsonObject["functionCall"]?.jsonObject ?: return@mapNotNull null
                val name = call["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                ToolCall(
                    id = null,
                    name = name,
                    arguments = call["args"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            }

    private fun readAnthropic(parsed: JsonObject): String {
        // A refusal has to be checked before the content is read: the stop
        // reason is the model declining, not an answer to show as one.
        if (parsed["stop_reason"]?.jsonPrimitive?.content == "refusal") {
            throw Exception("The assistant declined to answer that one.")
        }

        return parsed["content"]?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            ?.joinToString("\n")
            .orEmpty()
    }

    private fun readGemini(parsed: JsonObject): String {
        val candidate = parsed["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw Exception(
                // No candidates at all means the prompt itself was refused.
                parsed["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content
                    ?.let { "Gemini blocked that question ($it)." }
                    ?: "Gemini sent no answer back."
            )

        if (candidate["finishReason"]?.jsonPrimitive?.content == "SAFETY") {
            throw Exception("Gemini declined to answer that one.")
        }

        return candidate["content"]?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            ?.joinToString("\n")
            .orEmpty()
    }

    /**
     * The figures the model is allowed to see: this month and last, the category
     * breakdown, and the largest merchants. No dates, no counterparties, no
     * account details, nothing identifying.
     */
    private suspend fun buildFacts(currency: String): String {
        val thisMonth = LocalDate.now().withDayOfMonth(1)
        val lastMonth = thisMonth.minusMonths(1)

        suspend fun window(start: LocalDate): String {
            val end = start.plusMonths(1).minusDays(1)
            val income = dao.incomeBetween(currency, start, end).firstValue()
            val spend = dao.spendBetween(currency, start, end).firstValue()
            val categories = dao.spendByCategory(currency, start, end).firstValue()
                .take(10)
                .joinToString("; ") { "${it.category} ${fmt(it.totalMinor, currency)}" }
            return buildString {
                appendLine("  in ${fmt(income, currency)}, out ${fmt(spend, currency)}")
                if (categories.isNotEmpty()) appendLine("  by category: $categories")
            }
        }

        val merchants = dao.topMerchants(currency, thisMonth, thisMonth.plusMonths(1).minusDays(1), 8)
            .firstValue()
            .joinToString("; ") { "${it.merchant} ${fmt(it.totalMinor, currency)} (${it.count}x)" }

        val earliest = dao.earliest()
        val latest = dao.latest()

        // What the user has already promised to pay. Without this the assistant
        // will happily tell someone they can afford something they cannot.
        val commitments = commitmentDao.activeNow().filter { it.currency == currency }
        val plan = Planner.monthPlan(
            currency = currency,
            balanceMinor = dao.balance(currency).firstValue(),
            incomeMinor = dao.incomeBetween(currency, thisMonth, endOf(thisMonth)).firstValue(),
            spentMinor = dao.spendBetween(currency, thisMonth, endOf(thisMonth)).firstValue(),
            commitments = commitments,
            totalOwedMinor = commitmentDao.totalOwed(currency).firstValue(),
        )

        return buildString {
            appendLine("The figures below are the user's real ones. Currency: $currency.")
            appendLine("History covers $earliest to $latest.")
            appendLine("Balance now: ${fmt(plan.balanceMinor, currency)}")
            appendLine("This month (${thisMonth.month}):")
            append(window(thisMonth))
            appendLine("Last month (${lastMonth.month}):")
            append(window(lastMonth))
            if (merchants.isNotEmpty()) appendLine("Biggest merchants this month: $merchants")

            if (commitments.isEmpty()) {
                appendLine(
                    "The user has recorded no debts, loans or regular commitments. If they " +
                        "ask about affording something, say that this is missing rather " +
                        "than assuming there is nothing."
                )
            } else {
                appendLine("Committed every month, already promised:")
                commitments.forEach { c ->
                    append("  ${c.name} (${c.kind.label}) ${fmt(c.amountMinor, currency)}")
                    c.dayOfMonth?.let { append(", taken on day $it") }
                    c.monthsRemaining?.let { append(", $it payments left") }
                    appendLine()
                }
                appendLine("  total ${fmt(plan.committedMinor, currency)} a month")
                if (plan.totalOwedMinor > 0) {
                    appendLine("  still owed overall: ${fmt(plan.totalOwedMinor, currency)}")
                }
            }

            appendLine(
                "Safe to spend right now — balance minus what is still due to leave this " +
                    "month — is ${fmt(plan.safeToSpendMinor, currency)}, over " +
                    "${plan.daysLeft} remaining days."
            )
        }
    }

    private fun fmt(minor: Long, currency: String) = Money.format(minor, currency)

    private fun endOf(month: LocalDate) = month.plusMonths(1).minusDays(1)

    private fun friendlyError(provider: Provider, code: Int, payload: String): String {
        val detail = runCatching {
            json.parseToJsonElement(payload).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull()

        return when (code) {
            401, 403 -> "That ${provider.label} key was rejected. Check it in Settings."
            404 -> "That model isn't available on your ${provider.label} key. ${detail.orEmpty()}".trim()
            429 -> "Rate limited by ${provider.label} — wait a moment and ask again."
            in 500..599 -> "${provider.label} is having trouble. Try again shortly."
            // A billing problem arrives as a 400 with the reason in the body,
            // so the message from the service is more use than anything here.
            else -> detail ?: "The request failed ($code)."
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()

        // The cheapest model on each side that does this job well, so a small
        // amount of credit lasts a long time. Gemini's Flash models also have a
        // free tier that covers a few questions a day without any billing.
        const val ANTHROPIC_MODEL = "claude-haiku-4-5"
        const val GEMINI_MODEL = "gemini-2.5-flash"

        /**
         * How many times the model may call tools before the turn ends. Enough
         * for "add these four instalments", short enough that a loop cannot run
         * up a bill or churn the database.
         */
        const val MAX_ROUNDS = 6

        val SYSTEM_PROMPT = """
            You help someone understand their own spending. You are given their
            real figures below.

            Answer from those figures only. If they do not cover the question,
            say so plainly rather than estimating — a made-up number is worse
            than "I can't tell from this".

            When they ask whether they can afford something, work from what is
            safe to spend rather than the balance, and say what you subtracted.
            When they ask about debts or paying something off, use the payments
            and the amounts still owed, and be straight about how long it takes.

            You can also change their records: add or edit debts, instalments,
            subscriptions and bills, set cash on hand, set budgets, add a
            transaction, and refile a merchant into a category.

            When they ask for a change, make it. Do not ask permission and do
            not offer to do it — they have said they want it done. Every change
            is recorded and can be undone with one tap, so acting is cheap and
            asking is the annoying part.

            Two things to be careful about. Change only what they asked for,
            nothing tidier alongside it. And if what they want is ambiguous in a
            way that changes the number — which of two similar commitments, or
            an amount you would be guessing — ask that one question rather than
            picking for them.

            Say what you did in plain terms afterwards.

            Be brief and concrete. Use the amounts you were given, keep the
            currency they are in, and skip the preamble. No pep talks.
        """.trimIndent()
    }
}

/** Reads the current value of a Flow that a DAO query exposes. */
private suspend fun <T> Flow<T>.firstValue(): T = first()
