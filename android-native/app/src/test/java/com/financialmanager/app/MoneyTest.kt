package com.financialmanager.app

import com.financialmanager.app.money.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {

    @Test
    fun `decimal strings become whole minor units`() {
        assertEquals(-4250L, Money.toMinor("-42.50", "EUR"))
        assertEquals(150_000L, Money.toMinor("1500.00", "EUR"))
        assertEquals(1L, Money.toMinor("0.01", "GBP"))
    }

    @Test
    fun `rounding is half up rather than bankers`() {
        assertEquals(3L, Money.toMinor("0.025", "EUR"))
    }

    @Test
    fun `currencies without two decimal places are handled`() {
        // A yen has no subdivision; 500 yen is 500 minor units, not 50,000.
        assertEquals(500L, Money.toMinor("500", "JPY"))
        assertEquals(0, Money.exponent("JPY"))
        assertEquals(3, Money.exponent("KWD"))
    }

    @Test
    fun `minor units round trip back to decimals`() {
        assertEquals("-42.50", Money.fromMinor(-4250, "EUR").toPlainString())
        assertEquals("500", Money.fromMinor(500, "JPY").toPlainString())
    }

    @Test
    fun `addition never drifts`() {
        // The reason for integers: 0.1 + 0.2 is not 0.3 in binary floating point.
        val total = (1..10).sumOf { Money.toMinor("0.10", "EUR") }
        assertEquals(100L, total)
    }
}
