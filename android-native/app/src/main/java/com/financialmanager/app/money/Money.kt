package com.financialmanager.app.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Money is stored as whole minor units — cents, pence — never as a floating
 * point number. 0.1 + 0.2 is not 0.3 in binary floating point, and a finance
 * app that drifts by a cent is a finance app nobody trusts.
 *
 * Amounts are never converted between currencies. Totals are grouped per
 * currency instead, because inventing an exchange rate would make the numbers
 * look tidier and be wrong.
 */
object Money {

    /** Currencies whose minor unit is not 1/100. */
    private val EXPONENTS = mapOf(
        "JPY" to 0, "KRW" to 0, "VND" to 0, "CLP" to 0, "ISK" to 0, "HUF" to 0,
        "BHD" to 3, "KWD" to 3, "OMR" to 3, "TND" to 3, "JOD" to 3,
    )

    fun exponent(currency: String): Int = EXPONENTS[currency.uppercase()] ?: 2

    /** "-42.50" in EUR becomes -4250. */
    fun toMinor(amount: String, currency: String): Long {
        val exp = exponent(currency)
        return BigDecimal(amount.trim())
            .setScale(exp, RoundingMode.HALF_UP)
            .movePointRight(exp)
            .toLong()
    }

    fun toMinor(amount: BigDecimal, currency: String): Long {
        val exp = exponent(currency)
        return amount.setScale(exp, RoundingMode.HALF_UP).movePointRight(exp).toLong()
    }

    fun fromMinor(minor: Long, currency: String): BigDecimal =
        BigDecimal(minor).movePointLeft(exponent(currency))

    /**
     * Formats for display, using the phone's locale for grouping and separators
     * but always the transaction's own currency symbol.
     */
    fun format(minor: Long, currency: String, signed: Boolean = false): String {
        val exp = exponent(currency)
        val format = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            runCatching { this.currency = Currency.getInstance(currency.uppercase()) }
            minimumFractionDigits = exp
            maximumFractionDigits = exp
        }
        val body = format.format(fromMinor(kotlin.math.abs(minor), currency))
        return when {
            signed && minor < 0 -> "−$body"   // a real minus sign, not a hyphen
            signed -> "+$body"
            minor < 0 -> "-$body"
            else -> body
        }
    }

    /** Compact form for chart axes: 2,480.00 becomes 2.5K. */
    fun formatShort(minor: Long, currency: String): String {
        val value = fromMinor(kotlin.math.abs(minor), currency).toDouble()
        val symbol = runCatching { Currency.getInstance(currency.uppercase()).symbol }
            .getOrDefault(currency)
        val sign = if (minor < 0) "-" else ""
        return when {
            value >= 1_000_000 -> "$sign$symbol%.1fM".format(value / 1_000_000)
            value >= 1_000 -> "$sign$symbol%.1fK".format(value / 1_000)
            else -> "$sign$symbol%.0f".format(value)
        }
    }
}
