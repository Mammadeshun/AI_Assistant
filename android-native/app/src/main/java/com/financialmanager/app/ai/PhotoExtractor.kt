package com.financialmanager.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.financialmanager.app.data.CommitmentKind
import com.financialmanager.app.money.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * A commitment read out of a photo, before the user has agreed to it.
 *
 * Deliberately separate from [com.financialmanager.app.data.Commitment]: nothing
 * a model extracts from a picture goes into the user's finances until they have
 * looked at it. Screenshots get misread, and a wrong instalment plan quietly
 * added is worse than no feature at all.
 */
data class ProposedCommitment(
    val name: String,
    val kind: CommitmentKind,
    val monthlyAmountMinor: Long,
    val currency: String,
    val dayOfMonth: Int?,
    val remainingMinor: Long?,
    val instalmentsLeft: Int?,
    val note: String?,
)

data class Extraction(
    val proposals: List<ProposedCommitment>,
    /** What the model says it saw, shown alongside so the user can judge it. */
    val summary: String,
)

/**
 * Reads instalment plans and bills out of a photo.
 *
 * Pointing a camera at a Klarna screen beats typing four dates and four amounts,
 * and this is the kind of job a vision model is genuinely good at. The result is
 * always a proposal: the user confirms each line before anything is saved.
 */
class PhotoExtractor(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun extract(
        apiKey: String,
        provider: Provider,
        uri: Uri,
        defaultCurrency: String,
        /** Whatever the user typed alongside the picture, if anything. */
        hint: String? = null,
    ): Extraction = withContext(Dispatchers.IO) {
        val image = readScaledJpeg(uri)
        val encoded = Base64.encodeToString(image, Base64.NO_WRAP)

        val request = when (provider) {
            Provider.ANTHROPIC -> anthropicRequest(apiKey, encoded, defaultCurrency, hint)
            Provider.GEMINI -> geminiRequest(apiKey, encoded, defaultCurrency, hint)
        }

        val body = http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception(readError(provider, response.code, payload))
            }
            payload
        }

        parse(extractText(provider, body), defaultCurrency)
    }

    /**
     * Scales the picture down before sending it.
     *
     * A modern phone camera produces something like 12 megapixels; base64'd that
     * is tens of megabytes over mobile data, for no gain — the text in a
     * screenshot is perfectly legible at 1600px.
     */
    internal fun readScaledJpeg(uri: Uri): ByteArray {
        // The measuring pass returns null from decodeStream on purpose — with
        // inJustDecodeBounds it only fills in the dimensions. So whether the
        // image opened has to be judged by the stream and the size it reported,
        // never by the decode result.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw Exception("That file isn't an image this phone can read.")
        }

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > MAX_EDGE * 2) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = openStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw Exception("That image could not be decoded.")

        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun openStream(uri: Uri) = context.contentResolver.openInputStream(uri)
        ?: throw Exception(
            "That image could not be opened. If it came from a cloud album, save it to " +
                "the phone first and pick it from there."
        )

    private fun anthropicRequest(
        apiKey: String,
        image: String,
        currency: String,
        hint: String?,
    ): Request {
        val body = buildJsonObject {
            put("model", "claude-haiku-4-5")
            put("max_tokens", 2048)
            put("system", prompt(currency, hint))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", image)
                            }
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Read this and return the JSON described.")
                        })
                    })
                })
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
        image: String,
        currency: String,
        hint: String?,
    ): Request {
        val body = buildJsonObject {
            putJsonObject("system_instruction") {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", prompt(currency, hint)) })
                })
            }
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "image/jpeg")
                                put("data", image)
                            }
                        })
                        add(buildJsonObject {
                            put("text", "Read this and return the JSON described.")
                        })
                    })
                })
            })
            // Asking for JSON directly saves unwrapping a code fence, though the
            // parser copes either way.
            putJsonObject("generationConfig") {
                put("response_mime_type", "application/json")
                put("temperature", 0)
            }
        }

        return Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun extractText(provider: Provider, payload: String): String {
        val parsed = json.parseToJsonElement(payload).jsonObject
        return when (provider) {
            Provider.ANTHROPIC -> parsed["content"]?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?.joinToString("\n")
            Provider.GEMINI -> parsed["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?.joinToString("\n")
        }.orEmpty().ifBlank { throw Exception("Nothing came back from the image.") }
    }

    /**
     * Turns the model's reply into proposals.
     *
     * Written defensively on purpose: this is the one place where text a model
     * produced becomes numbers about someone's money, so anything unparseable is
     * dropped rather than guessed at.
     */
    fun parse(text: String, defaultCurrency: String): Extraction {
        // Models wrap JSON in a code fence about half the time, whatever they
        // were asked for.
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val root = runCatching { json.parseToJsonElement(cleaned).jsonObject }.getOrNull()
            ?: throw Exception("The reply wasn't readable as JSON.")

        val summary = root["summary"]?.jsonPrimitive?.contentOrNull().orEmpty()

        val proposals = root["commitments"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val name = item["name"]?.jsonPrimitive?.contentOrNull()?.trim()
            if (name.isNullOrBlank()) return@mapNotNull null

            val currency = item["currency"]?.jsonPrimitive?.contentOrNull()
                ?.trim()?.uppercase()?.takeIf { it.length == 3 } ?: defaultCurrency

            val monthly = decimal(item["monthlyAmount"]) ?: return@mapNotNull null
            if (monthly.signum() <= 0) return@mapNotNull null

            ProposedCommitment(
                name = name.take(80),
                kind = CommitmentKind.entries
                    .firstOrNull { it.name == item["kind"]?.jsonPrimitive?.contentOrNull()?.uppercase() }
                    ?: CommitmentKind.INSTALMENT,
                monthlyAmountMinor = Money.toMinor(monthly, currency),
                currency = currency,
                dayOfMonth = item["dayOfMonth"]?.jsonPrimitive?.contentOrNull()
                    ?.toIntOrNull()?.takeIf { it in 1..31 },
                remainingMinor = decimal(item["remainingTotal"])
                    ?.takeIf { it.signum() > 0 }
                    ?.let { Money.toMinor(it, currency) },
                instalmentsLeft = item["instalmentsLeft"]?.jsonPrimitive?.contentOrNull()
                    ?.toIntOrNull()?.takeIf { it > 0 },
                note = item["note"]?.jsonPrimitive?.contentOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        if (proposals.isEmpty()) {
            // A distinct type, because "that is a photo of a cat" is a perfectly
            // good answer and should not be shown in the red of a failure.
            throw NothingFound(
                summary.ifBlank {
                    "Nothing that looks like a payment plan was found in that picture."
                }
            )
        }
        return Extraction(proposals, summary)
    }

    private fun decimal(element: kotlinx.serialization.json.JsonElement?): BigDecimal? {
        val raw = element?.jsonPrimitive?.contentOrNull()?.trim() ?: return null
        // Replies arrive with currency symbols and either country's thousands
        // separator, so this goes through the same money parsing the statement
        // readers use rather than a second, weaker copy.
        return com.financialmanager.app.statement.Tabular.parseAmount(raw)
    }

    private fun readError(provider: Provider, code: Int, payload: String): String {
        val detail = runCatching {
            json.parseToJsonElement(payload).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        return when (code) {
            401, 403 -> "That ${provider.label} key was rejected."
            429 -> "Rate limited by ${provider.label} — try again in a moment."
            else -> detail ?: "Reading the image failed ($code)."
        }
    }

    private fun prompt(currency: String, hint: String?) = """
        You read a photo or screenshot of a payment plan, loan, bill or
        subscription, and return what it commits the person to paying.

        Return only JSON, in this shape:
        {
          "summary": "one sentence on what the picture shows",
          "commitments": [
            {
              "name": "Klarna - Zara",
              "kind": "INSTALMENT",
              "monthlyAmount": 24.99,
              "currency": "$currency",
              "dayOfMonth": 15,
              "remainingTotal": 74.97,
              "instalmentsLeft": 3,
              "note": "anything useful and short"
            }
          ]
        }

        kind is one of DEBT, INSTALMENT, SUBSCRIPTION, BILL.
        monthlyAmount is what leaves the account each month, as a number.
        remainingTotal is everything still owed across all remaining payments.
        Use $currency unless the picture clearly shows another currency.

        Only report what the picture actually shows. Leave a field out rather
        than guessing it, and return an empty commitments list if there is no
        payment plan in the image. Do not invent amounts or dates.
        ${
        hint?.takeIf { it.isNotBlank() }?.let {
            "\n        The person sent this with the message: \"$it\". Take it into " +
                "account, and answer it in the summary."
        } ?: ""
    }
    """.trimIndent()

    /** Thrown when the picture was read fine and simply had no plan in it. */
    class NothingFound(message: String) : Exception(message)

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        const val MAX_EDGE = 1600
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
