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

    /**
     * Builds a minimal .xlsx in memory so the reader can be exercised without a
     * real bank export.
     */
    private fun workbook(sheet: String, sharedStrings: String? = null): File {
        val file = File.createTempFile("test", ".xlsx")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray())
            zip.closeEntry()
            if (sharedStrings != null) {
                zip.putNextEntry(java.util.zip.ZipEntry("xl/sharedStrings.xml"))
                zip.write(sharedStrings.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `reads a small workbook end to end`() {
        val strings = """<?xml version="1.0"?><sst>""" +
            "<si><t>Date</t></si><si><t>Description</t></si><si><t>Amount</t></si>" +
            "<si><t>Esselunga</t></si></sst>"
        val sheet = """<?xml version="1.0"?><worksheet><sheetData>""" +
            """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c>""" +
            """<c r="C1" t="s"><v>2</v></c></row>""" +
            """<row r="2"><c r="A2" s="1"><v>45257</v></c><c r="B2" t="s"><v>3</v></c>""" +
            """<c r="C2"><v>-6.99</v></c></row>""" +
            "</sheetData></worksheet>"

        val transactions = XlsxStatement.parse(workbook(sheet, strings), "EUR")

        assertEquals(1, transactions.size)
        assertEquals(LocalDate.of(2023, 11, 27), transactions[0].bookedAt)
        assertEquals(-699L, transactions[0].amountMinor)
        assertEquals("Esselunga", transactions[0].description)
    }

    @Test
    fun `a missing cell does not shift the columns after it`() {
        // A spreadsheet stores an empty cell by leaving it out entirely, so
        // columns have to come from each cell's reference. Here B2 is absent.
        val strings = """<?xml version="1.0"?><sst>""" +
            "<si><t>Date</t></si><si><t>Description</t></si><si><t>Amount</t></si></sst>"
        val sheet = """<?xml version="1.0"?><worksheet><sheetData>""" +
            """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c>""" +
            """<c r="C1" t="s"><v>2</v></c></row>""" +
            """<row r="2"><c r="A2"><v>45257</v></c><c r="C2"><v>-12.50</v></c></row>""" +
            "</sheetData></worksheet>"

        val transactions = XlsxStatement.parse(workbook(sheet, strings), "EUR")

        // The amount must still be read from column C, not slid into B.
        assertEquals(-1250L, transactions[0].amountMinor)
        assertEquals("Imported transaction", transactions[0].description)
    }

    @Test
    fun `a file that tries to read the phone is refused its entity`() {
        // The parser must not fetch anything a spreadsheet points it at. If the
        // external entity were resolved, the secret would land in the
        // description; the reader has to either ignore it or fail, never
        // include it.
        val secret = File.createTempFile("secret", ".txt").apply { writeText("TOPSECRET") }
        val sheet = """<?xml version="1.0"?>""" +
            """<!DOCTYPE worksheet [<!ENTITY xxe SYSTEM "file://${secret.absolutePath}">]>""" +
            """<worksheet><sheetData>""" +
            """<row r="1"><c r="A1" t="inlineStr"><is><t>Date</t></is></c>""" +
            """<c r="B1" t="inlineStr"><is><t>Description</t></is></c>""" +
            """<c r="C1" t="inlineStr"><is><t>Amount</t></is></c></row>""" +
            """<row r="2"><c r="A2"><v>45257</v></c>""" +
            """<c r="B2" t="inlineStr"><is><t>&xxe;</t></is></c>""" +
            """<c r="C2"><v>-1.00</v></c></row>""" +
            "</sheetData></worksheet>"

        val descriptions = runCatching {
            XlsxStatement.parse(workbook(sheet), "EUR").map { it.description }
        }.getOrDefault(emptyList())

        assertTrue(
            "the file's contents must never reach a transaction: $descriptions",
            descriptions.none { it.contains("TOPSECRET") },
        )
    }
}

/** Which separator is the decimal point, which is grouping. */
class AmountSeparatorTest {

    private fun amount(text: String) = Tabular.parseAmount(text)?.toPlainString()

    @Test
    fun `both separators present means the rightmost is the decimal point`() {
        // The same amount, written for two countries. Reading it backwards turns
        // twelve hundred euros into twelve.
        assertEquals("1234.56", amount("1,234.56"))
        assertEquals("1234.56", amount("1.234,56"))
        assertEquals("1234567.89", amount("1,234,567.89"))
        assertEquals("1234567.89", amount("1.234.567,89"))
    }

    @Test
    fun `a lone separator with two digits after it is a decimal point`() {
        assertEquals("12.50", amount("12,50"))
        assertEquals("12.50", amount("12.50"))
        assertEquals("-6.99", amount("-6.99"))
    }

    @Test
    fun `a lone separator with three digits after it is grouping`() {
        assertEquals("1234", amount("1,234"))
        assertEquals("1234", amount("1.234"))
    }

    @Test
    fun `repeated separators are always grouping`() {
        assertEquals("1234567", amount("1,234,567"))
    }

    @Test
    fun `currency symbols and signs are stripped`() {
        assertEquals("1234.56", amount("€1.234,56"))
        assertEquals("-42.50", amount("-£42.50"))
        assertEquals("-100.00", amount("(100.00)"))
    }

    @Test
    fun `float artefacts survive as exact decimals`() {
        assertEquals("16.010000000000002", amount("16.010000000000002"))
    }

    @Test
    fun `nonsense is refused rather than guessed`() {
        assertEquals(null, amount("about twenty euros"))
        assertEquals("0", amount(""))
    }
}
