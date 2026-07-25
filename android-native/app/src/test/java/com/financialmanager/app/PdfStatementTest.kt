package com.financialmanager.app

import com.financialmanager.app.statement.PdfStatement
import com.financialmanager.app.statement.StatementError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Statement parsing, against the shapes a real Revolut statement contains.
 * The mojibake in these lines is copied from an actual statement's text layer —
 * it is the whole reason this parser exists.
 */
class PdfStatementTest {

    private val statement = listOf(
        "Date Description Money out Money in Balance",
        "27-Nov-23 Payment from EmployerTransfer €1,500.00 €1,500.00",
        "28-Nov-23 Tesco StoresMerchant -€42.50 €1,457.50",
        "29-Nov-23 Card Delivery FeeFees -€4.99 €1,452.51",
        "Total €1,500.00 €47.49",
    )

    @Test
    fun `reads a statement table`() {
        val transactions = PdfStatement.parseLines(statement.asSequence())

        assertEquals(3, transactions.size)
        assertEquals(LocalDate.of(2023, 11, 27), transactions[0].bookedAt)
        assertEquals(150_000L, transactions[0].amountMinor)
        assertEquals("EUR", transactions[0].currency)
        assertEquals("Payment from Employer", transactions[0].description)
        assertEquals(-4250L, transactions[1].amountMinor)
    }

    @Test
    fun `transaction type is stripped from the description`() {
        // "Fees" has to win over "Fee", or the description keeps a stray "s".
        assertEquals("Card Delivery Fee", PdfStatement.parseLines(statement.asSequence())[2].description)
    }

    @Test
    fun `every transaction type is stripped`() {
        // A type the list misses gets silently glued to the merchant name, so
        // each spelling seen in a real statement is covered.
        for (suffix in listOf(
            "Merchant", "Others", "Transfer", "Top up", "Top-Up", "Fees", "Fee",
            "Exchange", "Cashback", "Card", "ATM",
        )) {
            val parsed = PdfStatement.parseLines(
                sequenceOf("01-Dec-23 Payment from Employer$suffix €10.00 €10.00")
            )
            assertEquals(suffix, "Payment from Employer", parsed[0].description)
        }
    }

    @Test
    fun `summary and header rows are ignored`() {
        assertTrue(PdfStatement.parseLines(statement.asSequence()).none { "Total" in it.description })
    }

    @Test
    fun `hash dates are carried forward and flagged`() {
        val lines = statement + "######## SpotifyMerchant -€10.99 €1,441.52"
        val transactions = PdfStatement.parseLines(lines.asSequence())

        val unreadable = transactions.last()
        assertEquals(LocalDate.of(2023, 11, 29), unreadable.bookedAt)   // from the row above
        assertTrue(unreadable.dateEstimated)
        assertEquals(1, transactions.count { it.dateEstimated })
    }

    @Test
    fun `a hash date before any real date is dropped`() {
        val transactions = PdfStatement.parseLines(
            sequenceOf(
                "######## SpotifyMerchant -€10.99 €1.00",
                "01-Dec-23 Tesco StoresMerchant -€5.00 €1.00",
            )
        )
        assertEquals(listOf("Tesco Stores"), transactions.map { it.description })
    }

    @Test(expected = StatementError::class)
    fun `a statement with nothing readable says so`() {
        PdfStatement.parseLines(sequenceOf("Statement for account ending 1234", "Page 1 of 63"))
    }

    @Test
    fun `amount is the first money column not the balance`() {
        // Amount and balance frequently run together with no separating space.
        val transactions = PdfStatement.parseLines(
            sequenceOf("01-Dec-23 SpotifyMerchant -€10.99€1,441.52€0.00")
        )
        assertEquals(-1099L, transactions[0].amountMinor)
    }

    @Test
    fun `currency comes from the symbol`() {
        val transactions = PdfStatement.parseLines(
            sequenceOf("01-Dec-23 Amazon UKMerchant -£12.00 £30.00")
        )
        assertEquals("GBP", transactions[0].currency)
        assertEquals(-1200L, transactions[0].amountMinor)
    }

    @Test
    fun `identical rows on one day are kept apart`() {
        // Two identical coffees in one day are two transactions, not one.
        val lines = listOf(
            "05-Dec-23 Bar CentraleMerchant -€1.50 €10.00",
            "05-Dec-23 Bar CentraleMerchant -€1.50 €8.50",
        )
        val transactions = PdfStatement.parseLines(lines.asSequence())

        assertEquals(2, transactions.map { it.externalId }.toSet().size)
        // ...and re-reading the same statement recognises them rather than doubling.
        assertEquals(
            transactions.map { it.externalId },
            PdfStatement.parseLines(lines.asSequence()).map { it.externalId },
        )
    }

    @Test
    fun `mojibake currency symbols are repaired`() {
        // "Il Caff\u00e8 all'Universit\u00e0" and two euro signs, exactly as the
        // PDF's text layer hands them over.
        val line = "09-Jan-25 Il Caff\u00c3\u00a8 all'Universit\u00c3 Merchant " +
            "-\u00e2\u201a\u00ac5.50 \u00e2\u201a\u00ac591.73"
        val transactions = PdfStatement.parseLines(sequenceOf(line))

        assertEquals(-550L, transactions[0].amountMinor)
        assertEquals("EUR", transactions[0].currency)
    }

    @Test
    fun `accents survive the repair`() {
        // A trailing "à" reaches us as "Ã" plus a space, its no-break space
        // having been flattened during extraction; it still has to come back.
        assertEquals(
            "Il Caff\u00e8 all'Universit\u00e0",
            PdfStatement.repairEncoding("Il Caff\u00c3\u00a8 all'Universit\u00c3 "),
        )
        assertEquals("Tigot\u00e0 Merchant", PdfStatement.repairEncoding("Tigot\u00c3  Merchant"))
    }

    @Test
    fun `repair survives a character it cannot decode`() {
        // One unrecoverable character must not cost the rest of the line — the
        // euro sign still has to come back.
        val repaired = PdfStatement.repairEncoding("Bar \u0141 \u00e2\u201a\u00ac5.50")
        assertTrue(repaired, repaired.contains("\u20ac5.50"))
    }

    @Test
    fun `clean text is left alone`() {
        assertEquals("Tesco Stores \u20ac5.00", PdfStatement.repairEncoding("Tesco Stores \u20ac5.00"))
    }

    @Test
    fun `merchant names that are not mojibake are untouched`() {
        assertEquals("Caf\u00e8 Roma", PdfStatement.repairEncoding("Caf\u00e8 Roma"))
    }

    @Test
    fun `a lost decimal point does not become the next column`() {
        // PDFBox drops the decimal point in some rows, rendering EUR 13.93 as
        // "13 93". Matching the next money column instead would read the running
        // balance as the amount, turning a payment into income — which is what
        // happened to a real row before this was fixed.
        val transactions = PdfStatement.parseLines(
            sequenceOf("16-Jul-26 PayPal EuropeOthers -\u20ac13 93 \u20ac551.99 \u20ac0.00")
        )
        assertEquals(1, transactions.size)
        assertEquals(-1393L, transactions[0].amountMinor)
    }

    @Test
    fun `space separated thousands are not mistaken for a lost decimal point`() {
        val transactions = PdfStatement.parseLines(
            sequenceOf("01-Dec-23 Rent PaymentTransfer -\u20ac1 234.56 \u20ac10.00")
        )
        assertEquals(-123_456L, transactions[0].amountMinor)
    }

    @Test
    fun `a type word with a dropped character is still stripped`() {
        // Overlapping glyphs cost the odd character during extraction.
        val cases = mapOf(
            "Tarantola AlbertoM chant" to "Tarantola Alberto",
            "Quadriphone PaviaMerch nt" to "Quadriphone Pavia",
            "Transfer from Masoud BidabadiOther" to "Transfer from Masoud Bidabadi",
        )
        for ((raw, expected) in cases) {
            val parsed = PdfStatement.parseLines(sequenceOf("01-Dec-23 $raw -\u20ac5.00 \u20ac1.00"))
            assertEquals(raw, expected, parsed[0].description)
        }
    }

    @Test
    fun `a merchant name is not mistaken for a mangled type word`() {
        // The fuzzy pass must not start eating real names off the end.
        for (merchant in listOf("Da Giulio", "Bar Centrale", "Interest Free Shop", "Cardiff Bakery")) {
            val parsed = PdfStatement.parseLines(sequenceOf("01-Dec-23 ${merchant}Merchant -\u20ac5.00 \u20ac1.00"))
            assertEquals(merchant, parsed[0].description)
        }
    }
}
