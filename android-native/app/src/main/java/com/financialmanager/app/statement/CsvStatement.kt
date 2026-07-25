package com.financialmanager.app.statement

import com.financialmanager.app.money.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads Revolut's CSV export, and the generic date/description/amount shape most
 * other banks produce.
 *
 * Kept as a fallback for the PDF: some statements extract badly, and some banks
 * only offer CSV.
 */
object CsvStatement {

    private val DATE_FIELDS = listOf(
        "completed date", "started date", "date", "booking date", "value date",
    )
    private val DESCRIPTION_FIELDS = listOf("description", "reference", "details", "narrative", "name")
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

    fun parse(bytes: ByteArray, defaultCurrency: String = "EUR"): List<ParsedTransaction> {
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) throw StatementError("That file is empty.")

        val separator = listOf(',', ';', '\t').maxByOrNull { candidate ->
            lines.first().count { it == candidate }
        } ?: ','

        val header = splitRow(lines.first(), separator).map { it.trim().lowercase() }
        fun column(candidates: List<String>) =
            candidates.firstNotNullOfOrNull { name -> header.indexOf(name).takeIf { it >= 0 } }

        val dateAt = column(DATE_FIELDS)
            ?: throw StatementError("No date column found. Columns seen: ${header.joinToString(", ")}")
        val amountAt = column(AMOUNT_FIELDS)
        val paidOutAt = column(PAID_OUT_FIELDS)
        val paidInAt = column(PAID_IN_FIELDS)
        if (amountAt == null && paidOutAt == null && paidInAt == null) {
            throw StatementError("No amount column found (expected 'Amount', or 'Paid Out'/'Paid In').")
        }
        val descriptionAt = column(DESCRIPTION_FIELDS)
        val currencyAt = column(CURRENCY_FIELDS)
        val feeAt = column(FEE_FIELDS)
        val stateAt = column(STATE_FIELDS)

        val out = mutableListOf<ParsedTransaction>()
        for (line in lines.drop(1)) {
            val cells = splitRow(line, separator)
            fun cell(index: Int?) = index?.let { cells.getOrNull(it) }?.trim().orEmpty()

            val bookedAt = parseDate(cell(dateAt)) ?: continue   // skips footers and totals

            val state = cell(stateAt).lowercase()
            if (state in setOf("reverted", "declined", "failed")) continue

            val currency = cell(currencyAt).uppercase().take(3).ifEmpty { defaultCurrency }

            val amount = if (amountAt != null) {
                parseAmount(cell(amountAt)) ?: continue
            } else {
                val paidIn = parseAmount(cell(paidInAt)) ?: 0.0
                val paidOut = parseAmount(cell(paidOutAt)) ?: 0.0
                kotlin.math.abs(paidIn) - kotlin.math.abs(paidOut)
            }
            val fee = parseAmount(cell(feeAt)) ?: 0.0

            val amountMinor = Money.toMinor(amount.toBigDecimal(), currency) -
                Money.toMinor(kotlin.math.abs(fee).toBigDecimal(), currency)

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

        if (out.isEmpty()) throw StatementError("No transactions could be read from that file.")
        return PdfStatement.assignOccurrenceIds(out, "csv")
    }

    /** Splits one CSV row, respecting quoted cells that contain the separator. */
    private fun splitRow(line: String, separator: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"'); index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == separator && !inQuotes -> {
                    cells += current.toString(); current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        cells += current.toString()
        return cells
    }

    private fun parseDate(value: String): LocalDate? {
        if (value.isBlank()) return null
        for (format in DATE_FORMATS) {
            runCatching { return LocalDate.parse(value, format) }
        }
        return runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    }

    private fun parseAmount(value: String): Double? {
        if (value.isBlank()) return 0.0
        val negative = value.startsWith("(") && value.endsWith(")")
        var cleaned = value.trim('(', ')')
        for (symbol in listOf("€", "£", "$", " ", " ", ",")) cleaned = cleaned.replace(symbol, "")
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return 0.0
        val amount = cleaned.toDoubleOrNull() ?: return null
        return if (negative) -amount else amount
    }
}
