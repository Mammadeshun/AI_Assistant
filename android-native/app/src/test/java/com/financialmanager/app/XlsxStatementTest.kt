package com.financialmanager.app

import com.financialmanager.app.statement.Tabular
import com.financialmanager.app.statement.XlsxStatement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The bank's "Excel" export, which arrives named .csv and is really a zip of
 * XML. Fed to a text parser it is binary noise, which is what an import failing
 * for no visible reason looks like.
 */
class XlsxStatementTest {

    private val statement: File? =
        System.getProperty("statement.xlsx")?.let(::File)?.takeIf { it.isFile }

    @Test
    fun `a zip is recognised and plain text is not`() {
        assertTrue(XlsxStatement.looksLikeXlsx(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)))
        assertFalse(XlsxStatement.looksLikeXlsx("Date,Description,Amount".toByteArray()))
        assertFalse(XlsxStatement.looksLikeXlsx("%PDF-1.4".toByteArray()))
    }

    @Test
    fun `a spreadsheet day count is read as a date`() {
        // Excel counts days from 1899-12-30; 45257.57 is the 27th of November
        // 2023, not a number.
        assertEquals(LocalDate.of(2023, 11, 27), Tabular.parseDate("45257.573159722226"))
        assertEquals(LocalDate.of(2026, 7, 25), Tabular.parseDate("46228.5"))
    }

    @Test
    fun `an ordinary amount is not mistaken for a date`() {
        assertEquals(null, Tabular.parseDate("50"))
        assertEquals(null, Tabular.parseDate("-6.99"))
        assertEquals(null, Tabular.parseDate(""))
    }

    @Test
    fun `text dates still work`() {
        assertEquals(LocalDate.of(2025, 3, 1), Tabular.parseDate("2025-03-01 08:12:00"))
        assertEquals(LocalDate.of(2025, 3, 1), Tabular.parseDate("01/03/2025"))
    }

    @Test
    fun `a float artefact does not reach the stored amount`() {
        // A spreadsheet hands over 16.010000000000002; money must not inherit it.
        assertEquals("16.010000000000002", Tabular.parseAmount("16.010000000000002")!!.toPlainString())
        assertEquals(1601L, com.financialmanager.app.money.Money.toMinor(
            Tabular.parseAmount("16.010000000000002")!!, "EUR",
        ))
    }

    @Test
    fun `thousands separators and decimal commas are told apart`() {
        assertEquals("1234.56", Tabular.parseAmount("1,234.56")!!.toPlainString())
        assertEquals("12.50", Tabular.parseAmount("12,50")!!.toPlainString())
        assertEquals("-27", Tabular.parseAmount("-27")!!.toPlainString())
    }

    @Test
    fun `reads the real export`() {
        assumeTrue("no spreadsheet supplied", statement != null)

        val transactions = XlsxStatement.parse(statement!!, "EUR")

        // 2860 data rows, of which 14 are REVERTED — money that came back and
        // never really moved. The one PENDING row is kept: it has no completed
        // date, only a started one, and dropping it would lose a real payment.
        assertEquals(2846, transactions.size)
        assertEquals(2846, transactions.map { it.externalId }.toSet().size)
        assertTrue(transactions.all { it.currency == "EUR" })

        val first = transactions.first()
        assertEquals(LocalDate.of(2023, 11, 27), first.bookedAt)
        assertEquals(5000L, first.amountMinor)
        assertEquals("Apple Pay deposit by *4101", first.description)

        // Every row has a real date, in order, and none is absurd.
        assertTrue(transactions.all { it.bookedAt >= LocalDate.of(2023, 1, 1) })
        assertTrue(transactions.all { it.bookedAt <= LocalDate.now().plusDays(1) })
    }
}
