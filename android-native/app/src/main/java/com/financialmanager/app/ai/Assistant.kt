package com.financialmanager.app.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.financialmanager.app.data.TransactionDao
import com.financialmanager.app.money.Money
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

data class ChatMessage(val role: String, val text: String, val isError: Boolean = false)

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
class Assistant(private val dao: TransactionDao) {

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

        val request = when (provider) {
            Provider.ANTHROPIC -> anthropicRequest(apiKey, system, turns)
            Provider.GEMINI -> geminiRequest(apiKey, system, turns)
        }

        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception(friendlyError(provider, response.code, payload))
            }

            val parsed = json.parseToJsonElement(payload).jsonObject
            val text = when (provider) {
                Provider.ANTHROPIC -> readAnthropic(parsed)
                Provider.GEMINI -> readGemini(parsed)
            }
            ChatMessage("assistant", text.ifBlank { "No answer came back." })
        }
    }

    private fun anthropicRequest(apiKey: String, system: String, turns: List<ChatMessage>): Request {
        val body = buildJsonObject {
            put("model", ANTHROPIC_MODEL)
            put("max_tokens", 1024)
            put("system", system)
            put("messages", buildJsonArray {
                turns.forEach { message ->
                    add(buildJsonObject {
                        put("role", if (message.role == "user") "user" else "assistant")
                        put("content", message.text)
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

    private fun geminiRequest(apiKey: String, system: String, turns: List<ChatMessage>): Request {
        val body = buildJsonObject {
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

        return buildString {
            appendLine("The figures below are the user's real ones. Currency: $currency.")
            appendLine("History covers $earliest to $latest.")
            appendLine("This month (${thisMonth.month}):")
            append(window(thisMonth))
            appendLine("Last month (${lastMonth.month}):")
            append(window(lastMonth))
            if (merchants.isNotEmpty()) appendLine("Biggest merchants this month: $merchants")
        }
    }

    private fun fmt(minor: Long, currency: String) = Money.format(minor, currency)

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

        val SYSTEM_PROMPT = """
            You help someone understand their own spending. You are given their
            real figures below.

            Answer from those figures only. If they do not cover the question,
            say so plainly rather than estimating — a made-up number is worse
            than "I can't tell from this".

            Be brief and concrete. Use the amounts you were given, keep the
            currency they are in, and skip the preamble.
        """.trimIndent()
    }
}

/** Reads the current value of a Flow that a DAO query exposes. */
private suspend fun <T> Flow<T>.firstValue(): T = first()
