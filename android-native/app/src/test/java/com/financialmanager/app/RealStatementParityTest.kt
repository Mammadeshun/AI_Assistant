package com.financialmanager.app

import com.financialmanager.app.statement.PdfStatement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks this parser against the text of a real statement, and against what the
 * Python implementation made of the same file.
 *
 * The statement is somebody's actual bank history, so it is not in this
 * repository. Point `-Dstatement.lines=/path/to/lines.txt` at an extracted copy
 * to run it; without that the test skips rather than fails, so CI stays green
 * on a machine that has no such file.
 */
class RealStatementParityTest {

    private val linesFile: File? =
        System.getProperty("statement.lines")?.let(::File)?.takeIf { it.isFile }

    @Test
    fun `matches the python parser on a real statement`() {
        assumeTrue("no statement supplied", linesFile != null)

        val transactions = linesFile!!.readLines().asSequence().let {
            PdfStatement.parseLines(it)
        }

        // The figures the Python implementation produced from the same file.
        assertEquals("row count", 2844, transactions.size)
        assertEquals("distinct ids", 2844, transactions.map { it.externalId }.toSet().size)
        assertEquals("estimated dates", 168, transactions.count { it.dateEstimated })

        val income = transactions.filter { it.amountMinor > 0 }.sumOf { it.amountMinor }
        val spend = transactions.filter { it.amountMinor < 0 }.sumOf { it.amountMinor }
        assertEquals("money in", 4_790_476L, income)
        assertEquals("money out", -4_714_359L, spend)

        assertTrue("all EUR", transactions.all { it.currency == "EUR" })

        // Every mangled character was repaired: none survive into a description.
        val mangled = transactions.filter { txn ->
            listOf('Â', 'Ã', 'â').any { txn.description.contains(it) }
        }
        assertEquals("descriptions still mangled: $mangled", 0, mangled.size)

        // And no transaction type is left glued onto a merchant name.
        val glued = transactions.filter {
            it.description.endsWith("Merchant") || it.description.endsWith("Others") ||
                it.description.endsWith("Top up")
        }
        assertEquals("types still glued on: $glued", 0, glued.size)
    }
}
