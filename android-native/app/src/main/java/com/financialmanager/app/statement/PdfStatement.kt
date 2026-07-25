package com.financialmanager.app.statement

import com.financialmanager.app.money.Money
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** One row read out of a statement, before it becomes a stored transaction. */
data class ParsedTransaction(
    val externalId: String,
    val bookedAt: LocalDate,
    val amountMinor: Long,
    val currency: String,
    val description: String,
    val merchant: String,
    /** The PDF printed this row's date as ######## and it was taken from the row above. */
    val dateEstimated: Boolean = false,
)

class StatementError(message: String) : Exception(message)

/**
 * Reads the transaction tables out of a Revolut PDF statement.
 *
 * The PDF is what Revolut hands you by default — the CSV is buried behind a
 * format choice — so this is the path most people actually take.
 *
 * Two quirks of those files drive most of the code here. The euro sign and any
 * accented merchant name survive text extraction as mojibake ("â‚¬", "Il
 * CaffÃ¨"), and some dates render as ########, the spreadsheet symptom of a
 * column too narrow to print, baked into the PDF by whatever produced it. Those
 * rows still carry a good merchant and amount, so they are kept and dated from
 * the row above rather than thrown away.
 */
object PdfStatement {

    // A date (or ######## where it wouldn't fit), then the rest of the row.
    private val ROW = Regex("""^(\d{1,2}-[A-Za-z]{3}-\d{2}|#{3,})\s+(.+)$""")

    // Amounts print as -€56.00 or €2,630.29, and frequently run into the next
    // column with no space: €2,630.29€0.00
    private val MONEY = Regex("""(-?)([€$£])\s?([\d,]+\.\d{2})""")

    private val SYMBOL_CURRENCY = mapOf("€" to "EUR", "£" to "GBP", "$" to "USD")

    // Revolut appends the transaction type to the description with no separating
    // space ("Farmacia FormaggiaMerchant"), so it has to come off the end.
    // Longest first, or "Fee" strips before "Fees" and leaves a stray "s".
    private val ROW_TYPES = listOf(
        "Merchant", "Transfer", "Cashback", "Exchange", "Interest", "Others",
        "Top-Up", "Top-up", "Top up", "Top Up", "Card", "Fees", "Fee", "ATM",
    ).sortedByDescending { it.length }

    private val SKIP_PREFIXES = listOf(
        "Total", "Opening balance", "Closing balance", "Date Description",
    )

    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH)

    // Every mangled sequence starts with one of these three characters.
    private val MOJIBAKE_MARKERS = listOf('\u00c2', '\u00c3', '\u00e2')

    // A lead character, then whatever continues the mangled sequence. Written as
    // escaped code points rather than literal characters, so the pattern cannot
    // itself be corrupted by however this file is encoded in transit.
    private val MOJIBAKE_RUN = Regex(
        "[\u00c2\u00c3\u00e2]" +
            "[\u0080-\u00ff\u0152\u0153\u0160\u0161\u0178\u017d\u017e" +
            "\u0192\u02c6\u02dc\u2013\u2014\u2018-\u201e\u2020-\u2022" +
            "\u2026\u2030\u2039\u203a\u20ac\u2122]{1,3}"
    )

    // A no-break space is the one continuation character text extraction
    // rewrites, flattening it to a plain space and breaking its sequence.
    private val NBSP_EATEN = Regex("([\u00c2\u00c3]) ")

    /**
     * Undoes UTF-8-read-as-Windows-1252 mangling, one line at a time.
     *
     * The clean round-trip over the whole line handles almost everything. When a
     * single character defeats it, the mangled runs are decoded individually
     * instead, so one unrecoverable character costs only itself rather than the
     * whole line — and with it the currency symbol the row is parsed by.
     */
    fun repairEncoding(text: String): String {
        if (MOJIBAKE_MARKERS.none { text.contains(it) }) return text

        val restored = NBSP_EATEN.replace(text) { it.groupValues[1] + "\u00a0" }
        decodeRoundTrip(restored)?.let { return it }

        return MOJIBAKE_RUN.replace(restored) { decodeRoundTrip(it.value) ?: it.value }
    }

    /**
     * The cp1252 bytes of [text], reinterpreted as UTF-8 — or null when [text]
     * is not encodable, or the result is not valid UTF-8.
     *
     * The encoder is written out by hand because Android is not required to ship
     * the windows-1252 charset, and ISO-8859-1 is not a substitute: it has no
     * mapping for the 0x80-0x9F range, which is exactly where the bytes that
     * make up a mangled euro sign live.
     */
    private fun decodeRoundTrip(text: String): String? {
        val bytes = ByteArray(text.length)
        for ((index, char) in text.withIndex()) {
            val byte = when (val code = char.code) {
                in 0x00..0x7f, in 0xa0..0xff -> code
                else -> CP1252_HIGH[char] ?: return null
            }
            bytes[index] = byte.toByte()
        }

        val decoded = String(bytes, Charsets.UTF_8)
        return if (decoded.contains('\uFFFD')) null else decoded
    }

    /** The 27 characters where cp1252 differs from ISO-8859-1. */
    private val CP1252_HIGH: Map<Char, Int> = mapOf(
        '\u20ac' to 0x80, '\u201a' to 0x82, '\u0192' to 0x83, '\u201e' to 0x84,
        '\u2026' to 0x85, '\u2020' to 0x86, '\u2021' to 0x87, '\u02c6' to 0x88,
        '\u2030' to 0x89, '\u0160' to 0x8a, '\u2039' to 0x8b, '\u0152' to 0x8c,
        '\u017d' to 0x8e, '\u2018' to 0x91, '\u2019' to 0x92, '\u201c' to 0x93,
        '\u201d' to 0x94, '\u2022' to 0x95, '\u2013' to 0x96, '\u2014' to 0x97,
        '\u02dc' to 0x98, '\u2122' to 0x99, '\u0161' to 0x9a, '\u203a' to 0x9b,
        '\u0153' to 0x9c, '\u017e' to 0x9e, '\u0178' to 0x9f,
    )

    private fun parseDate(token: String): LocalDate? =
        try {
            LocalDate.parse(token, DATE_FORMAT)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun cleanDescription(text: String): String {
        var description = text.trim()
        for (rowType in ROW_TYPES) {
            if (description.endsWith(rowType)) {
                description = description.dropLast(rowType.length).trim()
                break
            }
        }
        return description.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * Turns extracted text lines into transactions.
     *
     * Split out from reading the PDF so the table logic can be tested without a
     * binary fixture.
     */
    fun parseLines(lines: Sequence<String>, defaultCurrency: String = "EUR"): List<ParsedTransaction> {
        val transactions = mutableListOf<ParsedTransaction>()
        var lastDate: LocalDate? = null

        for (rawLine in lines) {
            val line = repairEncoding(rawLine).trim()
            if (line.isEmpty() || SKIP_PREFIXES.any { line.startsWith(it) }) continue

            val row = ROW.find(line) ?: continue
            val rest = row.groupValues[2]

            val amount = MONEY.find(rest) ?: continue

            val token = row.groupValues[1]
            val dateUnreadable = token.startsWith("#")
            val bookedAt = if (dateUnreadable) lastDate else parseDate(token)
            if (bookedAt == null) continue          // nothing sensible to date it by
            if (!dateUnreadable) lastDate = bookedAt

            val currency = SYMBOL_CURRENCY[amount.groupValues[2]] ?: defaultCurrency
            val value = amount.groupValues[3].replace(",", "")
            val signed = if (amount.groupValues[1] == "-") "-$value" else value

            val description = cleanDescription(rest.substring(0, amount.range.first))
            if (description.isEmpty()) continue

            transactions += ParsedTransaction(
                externalId = "",                    // assigned below, once repeats can be counted
                bookedAt = bookedAt,
                amountMinor = Money.toMinor(signed, currency),
                currency = currency,
                description = description.take(400),
                merchant = description.take(200),
                dateEstimated = dateUnreadable,
            )
        }

        if (transactions.isEmpty()) {
            throw StatementError(
                "No transactions could be found in that statement. If it isn't a " +
                    "Revolut statement, try exporting a CSV from your bank instead."
            )
        }

        return assignOccurrenceIds(transactions, "pdf")
    }

    /**
     * Gives statement rows an identity of their own.
     *
     * A statement can legitimately list the same purchase twice in one day — two
     * identical coffees — and hashing the content alone treats them as one row,
     * losing the second on import. Numbering the repeats within their day keeps
     * them apart, without making the id depend on anything that changes between
     * exports, so re-importing an overlapping statement still recognises what it
     * already has.
     */
    fun assignOccurrenceIds(
        transactions: List<ParsedTransaction>,
        prefix: String,
    ): List<ParsedTransaction> {
        val seen = mutableMapOf<String, Int>()
        return transactions.map { txn ->
            val basis = listOf(
                txn.bookedAt.toString(),
                txn.amountMinor.toString(),
                txn.currency,
                txn.description.lowercase().split(Regex("\\s+")).joinToString(" "),
            ).joinToString("|")

            val occurrence = seen.getOrDefault(basis, 0)
            seen[basis] = occurrence + 1
            txn.copy(externalId = "$prefix:${sha256("$basis|$occurrence").take(24)}")
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
