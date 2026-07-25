package com.financialmanager.app.statement

import com.financialmanager.app.money.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a table of cells into transactions, whatever produced the table.
 *
 * Shared by the CSV reader and the spreadsheet reader, because a bank's "CSV"
 * and its "Excel" export are the same columns in different packaging, and the
 * awkward parts — which column is the date, whether the amount is one column or
 * two — should only be solved once.
 */
object Tabular {

    private val DATE_FIELDS = listOf(
        "completed date", "started date", "date", "booking date", "value date",
    )
    private val DESCRIPTION_FIELDS =
        listOf("description", "reference", "details", "narrative", "name")
    private val AMOUNT_FIELDS = listOf("amount", "value")
    private val PAID_OUT_FIELDS = listOf("paid out", "debit", "withdrawal", "money out")
    private val PAID_IN_FIELDS = listOf("paid in", "credit", "deposit", "money in")
    private val CURRENCY_FIELDS = listOf("currency", "ccy")
    private val FEE_FIELDS = listOf("fee")
    private val STATE_FIELDS = listOf("state", "status")

    private val DATE_FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
        "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MM-yyyy",
        "d MMM yyyy", "d MMMM yyyy",
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    /**
     * Excel counts days from this date. The epoch is nominally 1900-01-01, but
     * the format also believes 1900 was a leap year, and 1899-12-30 is the
     * offset that makes real dates come out right.
     */
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    fun parseRows(
        rows: List<List<String>>,
        defaultCurrency: String,
        idPrefix: String,
    ): List<ParsedTransaction> {
        if (rows.isEmpty()) throw StatementError("There are no rows in that file.")

        val header = rows.first().map { it.trim().lowercase() }
        fun column(candidates: List<String>) =
            candidates.firstNotNullOfOrNull { name -> header.indexOf(name).takeIf { it >= 0 } }

        // Every date column, in order of preference, rather than only the first
        // that exists. A pending transaction has a start date and no completed
        // date, and looking at one column would silently drop it.
        val dateColumns = DATE_FIELDS.mapNotNull { name ->
            header.indexOf(name).takeIf { it >= 0 }
        }
        if (dateColumns.isEmpty()) throw StatementError(
            "No date column found. Columns seen: ${rows.first().joinToString(", ")}"
        )
        val amountAt = column(AMOUNT_FIELDS)
        val paidOutAt = column(PAID_OUT_FIELDS)
        val paidInAt = column(PAID_IN_FIELDS)
        if (amountAt == null && paidOutAt == null && paidInAt == null) {
            throw StatementError(
                "No amount column found (expected 'Amount', or 'Paid Out'/'Paid In'). " +
                    "Columns seen: ${rows.first().joinToString(", ")}"
            )
        }
        val descriptionAt = column(DESCRIPTION_FIELDS)
        val currencyAt = column(CURRENCY_FIELDS)
        val feeAt = column(FEE_FIELDS)
        val stateAt = column(STATE_FIELDS)

        val out = mutableListOf<ParsedTransaction>()
        for (row in rows.drop(1)) {
            fun cell(index: Int?) = index?.let { row.getOrNull(it) }?.trim().orEmpty()

            // Footers, totals and blank rows have nothing date-shaped in them.
            val bookedAt = dateColumns.firstNotNullOfOrNull { parseDate(cell(it)) } ?: continue

            val state = cell(stateAt).lowercase()
            if (state in setOf("reverted", "declined", "failed")) continue

            val currency = cell(currencyAt).uppercase().take(3).ifEmpty { defaultCurrency }

            val amount = if (amountAt != null) {
                parseAmount(cell(amountAt)) ?: continue
            } else {
                val paidIn = parseAmount(cell(paidInAt)) ?: BigDecimal.ZERO
                val paidOut = parseAmount(cell(paidOutAt)) ?: BigDecimal.ZERO
                paidIn.abs().subtract(paidOut.abs())
            }
            val fee = parseAmount(cell(feeAt)) ?: BigDecimal.ZERO

            // A fee is charged on top of the amount, so it always makes the
            // transaction cost more regardless of which way the money went.
            val amountMinor = Money.toMinor(amount, currency) - Money.toMinor(fee.abs(), currency)

            val description = cell(descriptionAt).ifEmpty { "Imported transaction" }
            out += ParsedTransaction(
                externalId = "",
                bookedAt = bookedAt,
                amountMinor = amountMinor,
                currency = currency,
                description = description.take(400),
                merchant = description.take(200),
            )
        }

        if (out.isEmpty()) {
            throw StatementError(
                "No transactions could be read from that file. ${rows.size - 1} rows were " +
                    "found but none had a readable date and amount."
            )
        }
        return PdfStatement.assignOccurrenceIds(out, idPrefix)
    }

    fun parseDate(value: String): LocalDate? {
        if (value.isBlank()) return null

        for (format in DATE_FORMATS) {
            runCatching { return LocalDate.parse(value, format) }
        }
        runCatching { return LocalDate.parse(value.take(10)) }

        // A spreadsheet stores a date as a day count, so "45257.57" is a real
        // date rather than a number. The range keeps ordinary amounts out: it
        // spans roughly 1954 to 2093.
        val serial = value.toDoubleOrNull()
        if (serial != null && serial >= 20_000 && serial <= 71_000) {
            return EXCEL_EPOCH.plusDays(serial.toLong())
        }
        return null
    }

    /**
     * Reads an amount as a decimal rather than a float.
     *
     * Spreadsheets hand over values like "16.010000000000002" — the artefact of
     * a binary float — and money must not inherit that.
     */
    fun parseAmount(value: String): BigDecimal? {
        if (value.isBlank()) return BigDecimal.ZERO

        val negative = value.startsWith("(") && value.endsWith(")")
        var cleaned = value.trim().trim('(', ')')
        for (symbol in listOf("€", "£", "$", " ", " ")) cleaned = cleaned.replace(symbol, "")

        // A comma is a thousands separator here and a decimal point there. With
        // exactly two digits after it and no dot present, it is a decimal point.
        cleaned = if (Regex("^-?\\d{1,3}(,\\d{3})+(\\.\\d+)?$").matches(cleaned)) {
            cleaned.replace(",", "")
        } else {
            cleaned.replace(",", ".")
        }

        if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return BigDecimal.ZERO
        val amount = cleaned.toBigDecimalOrNull() ?: return null
        return if (negative) amount.negate() else amount
    }
}
