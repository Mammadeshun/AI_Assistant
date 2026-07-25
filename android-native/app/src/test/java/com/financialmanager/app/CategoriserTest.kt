package com.financialmanager.app

import com.financialmanager.app.categorise.Categoriser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriserTest {

    private fun categoryOf(merchant: String, amountMinor: Long = -500) =
        Categoriser.categorise(merchant, merchant, amountMinor).category

    @Test
    fun `uk high street is recognised`() {
        assertEquals("Groceries", categoryOf("TESCO STORES"))
        assertEquals("Subscriptions", categoryOf("Netflix"))
        assertEquals("Transport", categoryOf("TFL Travel Charge"))
    }

    @Test
    fun `netflix is not transport`() {
        // "tfl" hides inside "neTFLix", which a substring match would file under
        // Transport. Whole-word matching is the whole point.
        assertEquals("Subscriptions", categoryOf("NETFLIX.COM"))
    }

    @Test
    fun `italian merchants are recognised`() {
        // A statement from an Italian account is mostly names a UK keyword list
        // has never heard of.
        val expected = mapOf(
            "Eurospin" to "Groceries",
            "Esselunga" to "Groceries",
            "Trenitalia" to "Transport",
            "ATM - Azienda Trasporti Milanesi" to "Transport",
            "Bar Sottovento" to "Restaurants & Cafés",
            "Farmacia Formaggia" to "Health & Fitness",
            "iliad" to "Bills & Utilities",
            "Tigotà" to "Shopping",
        )
        for ((merchant, category) in expected) {
            assertEquals(merchant, category, categoryOf(merchant))
        }
    }

    @Test
    fun `the milan transport operator is not a cash machine`() {
        // "ATM" is Milan's bus company as well as a hole in the wall.
        assertEquals("Transport", categoryOf("ATM - Azienda Trasporti Milanesi"))
        assertEquals("Cash & ATM", categoryOf("Cash withdrawal at ATM"))
    }

    @Test
    fun `payments to people are transfers not shopping`() {
        // The counterparty is a person; merchant keywords must not be matched
        // against their name.
        val result = Categoriser.categorise("To Md Shamsuddin", "Md Shamsuddin", -2000)
        assertEquals("Transfers", result.category)
        assertTrue(result.isTransfer)
    }

    @Test
    fun `short chain names only match the whole merchant`() {
        assertEquals("Groceries", categoryOf("MD"))
        assertNotEquals("Groceries", categoryOf("Md Shamsuddin"))
    }

    @Test
    fun `unknown income and spending fall back sensibly`() {
        assertEquals("Other Income", categoryOf("Mystery credit", amountMinor = 5000))
        assertEquals("Uncategorised", categoryOf("ZZQ payment", amountMinor = -1234))
    }
}
