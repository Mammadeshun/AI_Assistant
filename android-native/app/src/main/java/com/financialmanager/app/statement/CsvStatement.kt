package com.financialmanager.app.statement

/**
 * Reads a genuine text CSV: Revolut's own export, and the generic
 * date/description/amount shape most other banks produce.
 *
 * Only the splitting lives here — deciding what the columns mean is shared with
 * the spreadsheet reader in [Tabular].
 */
object CsvStatement {

    fun parse(bytes: ByteArray, defaultCurrency: String = "EUR"): List<ParsedTransaction> {
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) throw StatementError("That file is empty.")

        val separator = listOf(',', ';', '\t').maxByOrNull { candidate ->
            lines.first().count { it == candidate }
        } ?: ','

        return Tabular.parseRows(lines.map { splitRow(it, separator) }, defaultCurrency, "csv")
    }

    /** Splits one row, respecting quoted cells that contain the separator. */
    fun splitRow(line: String, separator: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < line.length) {
            val char = line[index]
            when {
                // "" inside a quoted cell is a literal quote.
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"'); index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == separator && !inQuotes -> { cells += current.toString(); current.clear() }
                else -> current.append(char)
            }
            index++
        }
        cells += current.toString()
        return cells
    }
}
