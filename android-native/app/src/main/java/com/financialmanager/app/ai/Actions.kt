package com.financialmanager.app.ai

import com.financialmanager.app.categorise.Categoriser
import com.financialmanager.app.data.CashHolding
import com.financialmanager.app.data.ChangeDao
import com.financialmanager.app.data.ChangeRecord
import com.financialmanager.app.data.Commitment
import com.financialmanager.app.data.CommitmentKind
import com.financialmanager.app.data.FinanceDatabase
import com.financialmanager.app.data.TransactionRow
import com.financialmanager.app.money.Money
import com.financialmanager.app.statement.Tabular
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** One thing the assistant asked to do, and what came back. */
data class ToolCall(val id: String?, val name: String, val arguments: JsonObject)

data class ToolOutcome(val call: ToolCall, val result: String)

/**
 * Everything the assistant is allowed to change.
 *
 * The list is deliberately short and specific. There is no "run this SQL" and no
 * way to wipe the database: a model that misunderstands "clear that up" should
 * be able to get one commitment wrong, not delete a year of history.
 *
 * Every change writes a plain-language record and the instructions to reverse
 * it, so a wrong edit costs one tap rather than a re-import.
 */
class Actions(
    private val db: FinanceDatabase,
    private val changes: ChangeDao,
    private val currency: () -> String,
) {

    suspend fun execute(call: ToolCall): String = try {
        when (call.name) {
            "add_commitment" -> addCommitment(call.arguments)
            "update_commitment" -> updateCommitment(call.arguments)
            "remove_commitment" -> removeCommitment(call.arguments)
            "set_cash" -> setCash(call.arguments)
            "set_budget" -> setBudget(call.arguments)
            "add_transaction" -> addTransaction(call.arguments)
            "recategorise_merchant" -> recategoriseMerchant(call.arguments)
            "list_commitments" -> listCommitments()
            else -> "There is no tool called ${call.name}."
        }
    } catch (error: Throwable) {
        // Handed back to the model rather than thrown: it can then say what went
        // wrong, or try a different way, instead of the chat dying.
        "That didn't work: ${error.message}"
    }

    /* --- commitments -------------------------------------------------- */

    private suspend fun addCommitment(args: JsonObject): String {
        val name = args.text("name") ?: return "A name is needed."
        val monthly = args.money("monthlyAmount")
            ?: return "A monthly amount is needed, as a number."
        val currency = args.text("currency")?.uppercase()?.takeIf { it.length == 3 } ?: currency()

        val commitment = Commitment(
            name = name,
            kind = args.kind(),
            amountMinor = Money.toMinor(monthly, currency),
            currency = currency,
            dayOfMonth = args.int("dayOfMonth")?.takeIf { it in 1..31 },
            remainingMinor = args.money("remainingTotal")?.let { Money.toMinor(it, currency) },
            note = args.text("note"),
        )
        val id = db.commitments().insert(commitment)

        changes.record(
            ChangeRecord(
                summary = "Added ${commitment.name}, " +
                    "${Money.format(commitment.amountMinor, currency)} a month",
                inverse = inverse("delete_commitment") { put("id", id) },
            )
        )
        return "Added ${commitment.name} at ${Money.format(commitment.amountMinor, currency)} a month."
    }

    private suspend fun updateCommitment(args: JsonObject): String {
        val name = args.text("name") ?: return "Which commitment? Give its name."
        val existing = findCommitment(name)
            ?: return "There is no commitment called \"$name\". Use list_commitments to see them."

        val currency = existing.currency
        val updated = existing.copy(
            name = args.text("newName") ?: existing.name,
            kind = if (args.contains("kind")) args.kind() else existing.kind,
            amountMinor = args.money("monthlyAmount")
                ?.let { Money.toMinor(it, currency) } ?: existing.amountMinor,
            dayOfMonth = args.int("dayOfMonth")?.takeIf { it in 1..31 } ?: existing.dayOfMonth,
            remainingMinor = args.money("remainingTotal")
                ?.let { Money.toMinor(it, currency) } ?: existing.remainingMinor,
            active = args.bool("active") ?: existing.active,
        )
        db.commitments().update(updated)

        changes.record(
            ChangeRecord(
                summary = "Changed ${existing.name}",
                // The whole previous row, so undo restores exactly what was there.
                inverse = inverse("restore_commitment") { put("json", encode(existing)) },
            )
        )
        return "Updated ${updated.name}: ${Money.format(updated.amountMinor, currency)} a month" +
            (updated.dayOfMonth?.let { ", on day $it" } ?: "") + "."
    }

    private suspend fun removeCommitment(args: JsonObject): String {
        val name = args.text("name") ?: return "Which commitment? Give its name."
        val existing = findCommitment(name) ?: return "There is no commitment called \"$name\"."

        db.commitments().delete(existing)
        changes.record(
            ChangeRecord(
                summary = "Removed ${existing.name}",
                inverse = inverse("restore_commitment") { put("json", encode(existing)) },
            )
        )
        return "Removed ${existing.name}."
    }

    private suspend fun listCommitments(): String {
        val all = db.commitments().activeNow()
        if (all.isEmpty()) return "Nothing is recorded yet."
        return all.joinToString("; ") { commitment ->
            buildString {
                append("${commitment.name}: ")
                append(Money.format(commitment.amountMinor, commitment.currency))
                append(" a month (${commitment.kind.name})")
                commitment.dayOfMonth?.let { append(", day $it") }
                commitment.monthsRemaining?.let { append(", $it payments left") }
            }
        }
    }

    /* --- cash, budgets, transactions ---------------------------------- */

    private suspend fun setCash(args: JsonObject): String {
        val amount = args.money("amount") ?: return "An amount is needed."
        val currency = currency()
        val previous = db.cash().amountNow(currency) ?: 0L
        val minor = Money.toMinor(amount, currency)

        if (minor <= 0) db.cash().clear(currency)
        else db.cash().set(CashHolding(currency, minor))

        changes.record(
            ChangeRecord(
                summary = "Cash set to ${Money.format(minor, currency)}",
                inverse = inverse("set_cash_minor") { put("amountMinor", previous) },
            )
        )
        return "Cash on hand is now ${Money.format(minor, currency)}."
    }

    private suspend fun setBudget(args: JsonObject): String {
        val category = args.text("category") ?: return "Which category?"
        val known = Categoriser.DEFAULTS.firstOrNull { it.name.equals(category, ignoreCase = true) }
            ?: return "There is no category called \"$category\". They are: " +
                Categoriser.DEFAULTS.joinToString(", ") { it.name }

        val limit = args.money("monthlyLimit") ?: return "A monthly limit is needed."
        val currency = currency()
        val previous = db.budgets().all().first().firstOrNull { it.category == known.name }

        val minor = Money.toMinor(limit, currency)
        if (minor <= 0) db.budgets().delete(known.name)
        else db.budgets().upsert(com.financialmanager.app.data.Budget(known.name, minor, currency))

        changes.record(
            ChangeRecord(
                summary = "Budget for ${known.name} set to ${Money.format(minor, currency)}",
                inverse = inverse("set_budget_minor") {
                    put("category", known.name)
                    put("amountMinor", previous?.limitMinor ?: 0L)
                },
            )
        )
        return "${known.name} budget is now ${Money.format(minor, currency)} a month."
    }

    private suspend fun addTransaction(args: JsonObject): String {
        val description = args.text("description") ?: return "A description is needed."
        val amount = args.money("amount") ?: return "An amount is needed, as a number."
        val currency = currency()
        val date = args.text("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()

        val spending = args.bool("isSpending") ?: true
        val minor = Money.toMinor(amount, currency).let { if (spending) -it else it }
        val verdict = Categoriser.categorise(description, description, minor)

        val row = TransactionRow(
            externalId = "assistant:${System.currentTimeMillis()}",
            bookedAt = date,
            amountMinor = minor,
            currency = currency,
            description = description,
            merchant = description,
            category = args.text("category")
                ?.let { wanted ->
                    Categoriser.DEFAULTS.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.name
                } ?: verdict.category,
            isTransfer = verdict.isTransfer,
        )
        db.transactions().insertAll(listOf(row))

        changes.record(
            ChangeRecord(
                summary = "Added ${row.description}, ${Money.format(minor, currency)}",
                inverse = inverse("delete_transaction") { put("externalId", row.externalId) },
            )
        )
        return "Added ${row.description} on $date for ${Money.format(minor, currency)}."
    }

    private suspend fun recategoriseMerchant(args: JsonObject): String {
        val merchant = args.text("merchant") ?: return "Which merchant?"
        val category = args.text("category") ?: return "Which category?"
        val known = Categoriser.DEFAULTS.firstOrNull { it.name.equals(category, ignoreCase = true) }
            ?: return "There is no category called \"$category\"."

        val matches = db.transactions().searchNow(merchant)
        if (matches.isEmpty()) return "Nothing matches \"$merchant\"."

        val previous = matches.map { it.id to it.category }
        matches.forEach { db.transactions().setCategory(it.id, known.name) }

        changes.record(
            ChangeRecord(
                summary = "Filed ${matches.size} ${merchant} transactions under ${known.name}",
                inverse = inverse("restore_categories") {
                    put("pairs", previous.joinToString(",") { "${it.first}:${it.second}" })
                },
            )
        )
        return "Moved ${matches.size} transactions to ${known.name}."
    }

    /* --- undo --------------------------------------------------------- */

    /**
     * Puts back whatever a change did.
     *
     * The inverse was written at the time the change was made, when the old
     * values were still known, rather than being worked out backwards later.
     */
    suspend fun undo(record: ChangeRecord): Boolean {
        val instruction = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(record.inverse) as JsonObject
        }.getOrNull() ?: return false

        val action = instruction.text("action") ?: return false
        runCatching {
            when (action) {
                "delete_commitment" -> instruction.long("id")?.let { id ->
                    db.commitments().activeNow().firstOrNull { it.id == id }
                        ?.let { db.commitments().delete(it) }
                        ?: db.commitments().deleteById(id)
                }
                "restore_commitment" -> instruction.text("json")
                    ?.let { db.commitments().insert(decode(it)) }
                "set_cash_minor" -> {
                    val amount = instruction.long("amountMinor") ?: 0L
                    if (amount <= 0) db.cash().clear(currency())
                    else db.cash().set(CashHolding(currency(), amount))
                }
                "set_budget_minor" -> {
                    val category = instruction.text("category") ?: return false
                    val amount = instruction.long("amountMinor") ?: 0L
                    if (amount <= 0) db.budgets().delete(category)
                    else db.budgets().upsert(
                        com.financialmanager.app.data.Budget(category, amount, currency())
                    )
                }
                "delete_transaction" -> instruction.text("externalId")
                    ?.let { db.transactions().deleteByExternalId(it) }
                "restore_categories" -> instruction.text("pairs")?.split(",")
                    ?.forEach { pair ->
                        val (id, category) = pair.split(":", limit = 2)
                        db.transactions().setCategory(id.toLong(), category)
                    }
                else -> return false
            }
        }.onFailure { return false }

        changes.markUndone(record.id)
        return true
    }

    private suspend fun findCommitment(name: String): Commitment? {
        val all = db.commitments().activeNow() +
            db.commitments().all().first().filterNot { it.active }
        return all.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: all.firstOrNull { it.name.contains(name, ignoreCase = true) }
    }

    private fun encode(commitment: Commitment): String = buildJsonObject {
        put("id", commitment.id)
        put("name", commitment.name)
        put("kind", commitment.kind.name)
        put("amountMinor", commitment.amountMinor)
        put("currency", commitment.currency)
        commitment.dayOfMonth?.let { put("dayOfMonth", it) }
        commitment.remainingMinor?.let { put("remainingMinor", it) }
        commitment.note?.let { put("note", it) }
        put("active", commitment.active)
    }.toString()

    private fun decode(text: String): Commitment {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject
        return Commitment(
            id = json.long("id") ?: 0L,
            name = json.text("name").orEmpty(),
            kind = CommitmentKind.entries
                .firstOrNull { it.name == json.text("kind") } ?: CommitmentKind.BILL,
            amountMinor = json.long("amountMinor") ?: 0L,
            currency = json.text("currency") ?: currency(),
            dayOfMonth = json.int("dayOfMonth"),
            remainingMinor = json.long("remainingMinor"),
            note = json.text("note"),
            active = json.bool("active") ?: true,
        )
    }

    private fun inverse(action: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject { put("action", action); build() }.toString()

    companion object {
        /**
         * What the assistant is told it can do.
         *
         * Kept provider-neutral — both services take the same JSON Schema shape,
         * wrapped differently.
         */
        val DECLARATIONS = buildJsonArray {
            add(tool("add_commitment", "Record a debt, instalment plan, subscription or bill that the user pays every month.", required = listOf("name", "monthlyAmount")) {
                putJsonObject("name") { put("type", "string") }
                putJsonObject("monthlyAmount") { put("type", "number"); put("description", "What leaves the account each month") }
                putJsonObject("kind") {
                    put("type", "string")
                    put("description", "DEBT, INSTALMENT, SUBSCRIPTION or BILL")
                }
                putJsonObject("dayOfMonth") { put("type", "integer") }
                putJsonObject("remainingTotal") { put("type", "number"); put("description", "Everything still owed, for debts and instalment plans") }
                putJsonObject("currency") { put("type", "string") }
                putJsonObject("note") { put("type", "string") }
            })

            add(tool("update_commitment", "Change something already recorded. Find it by name.", required = listOf("name")) {
                putJsonObject("name") { put("type", "string"); put("description", "The commitment to change") }
                putJsonObject("newName") { put("type", "string") }
                putJsonObject("monthlyAmount") { put("type", "number") }
                putJsonObject("kind") { put("type", "string") }
                putJsonObject("dayOfMonth") { put("type", "integer") }
                putJsonObject("remainingTotal") { put("type", "number") }
                putJsonObject("active") { put("type", "boolean"); put("description", "false to pause it") }
            })

            add(tool("remove_commitment", "Delete a recorded commitment entirely.", required = listOf("name")) {
                putJsonObject("name") { put("type", "string") }
            })

            add(tool("list_commitments", "List what is currently recorded, with amounts and days.", required = listOf("amount")) {})

            add(tool("set_cash", "Set how much cash the user is carrying.") {
                putJsonObject("amount") { put("type", "number") }
            })

            add(tool("set_budget", "Set a monthly spending limit for a category. Zero removes it.", required = listOf("category", "monthlyLimit")) {
                putJsonObject("category") { put("type", "string") }
                putJsonObject("monthlyLimit") { put("type", "number") }
            })

            add(tool("add_transaction", "Record a single payment or income the statement does not have.", required = listOf("description", "amount")) {
                putJsonObject("description") { put("type", "string") }
                putJsonObject("amount") { put("type", "number"); put("description", "Always positive") }
                putJsonObject("isSpending") { put("type", "boolean"); put("description", "true for money out, false for money in") }
                putJsonObject("date") { put("type", "string"); put("description", "YYYY-MM-DD, today if left out") }
                putJsonObject("category") { put("type", "string") }
            })

            add(tool("recategorise_merchant", "Move every transaction matching a merchant name into a category.", required = listOf("merchant", "category")) {
                putJsonObject("merchant") { put("type", "string") }
                putJsonObject("category") { put("type", "string") }
            })
        }

        private fun tool(
            name: String,
            description: String,
            required: List<String> = emptyList(),
            properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
        ) = buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties", properties)
                put("required", buildJsonArray { required.forEach { add(it) } })
            }
        }
    }
}

/* --- reading arguments a model produced ------------------------------- */

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }
        ?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

private fun JsonObject.int(key: String): Int? = text(key)?.toDoubleOrNull()?.toInt()

private fun JsonObject.long(key: String): Long? = text(key)?.toDoubleOrNull()?.toLong()

private fun JsonObject.bool(key: String): Boolean? = when (text(key)?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> null
}

/** Amounts arrive as numbers, or as "€12,50" when the model is being helpful. */
private fun JsonObject.money(key: String): java.math.BigDecimal? =
    text(key)?.let { Tabular.parseAmount(it) }?.takeIf { it.signum() >= 0 }

/** An unrecognised kind becomes a bill rather than failing the whole call. */
private fun JsonObject.kind(): CommitmentKind =
    CommitmentKind.entries.firstOrNull { it.name.equals(text("kind"), ignoreCase = true) }
        ?: CommitmentKind.BILL
