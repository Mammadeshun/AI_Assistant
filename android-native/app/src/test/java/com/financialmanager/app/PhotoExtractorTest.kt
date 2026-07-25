package com.financialmanager.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financialmanager.app.ai.PhotoExtractor
import com.financialmanager.app.data.CommitmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Turning a model's reply into commitments.
 *
 * This is the one place where text a model wrote becomes numbers about someone's
 * money, so the parser is held to being strict: anything it cannot read cleanly
 * is dropped rather than guessed at.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhotoExtractorTest {

    private val extractor by lazy {
        PhotoExtractor(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `reads a klarna style plan`() {
        val reply = """
            {"summary": "A Klarna plan for a Zara order, three payments left.",
             "commitments": [
               {"name": "Klarna - Zara", "kind": "INSTALMENT", "monthlyAmount": 24.99,
                "currency": "EUR", "dayOfMonth": 15, "remainingTotal": 74.97,
                "instalmentsLeft": 3, "note": "order 1234"}
             ]}
        """.trimIndent()

        val extraction = extractor.parse(reply, "EUR")
        val plan = extraction.proposals.single()

        assertEquals("Klarna - Zara", plan.name)
        assertEquals(CommitmentKind.INSTALMENT, plan.kind)
        assertEquals(2499L, plan.monthlyAmountMinor)
        assertEquals(7497L, plan.remainingMinor)
        assertEquals(15, plan.dayOfMonth)
        assertEquals(3, plan.instalmentsLeft)
        assertTrue(extraction.summary.contains("Klarna"))
    }

    @Test
    fun `a code fence around the json is tolerated`() {
        // Models wrap JSON in a fence about half the time whatever they are asked.
        val reply = "```json\n{\"commitments\":[{\"name\":\"Gym\",\"monthlyAmount\":30}]}\n```"
        assertEquals(3000L, extractor.parse(reply, "EUR").proposals.single().monthlyAmountMinor)
    }

    @Test
    fun `an amount written with a currency symbol still reads`() {
        val reply = """{"commitments":[{"name":"Loan","monthlyAmount":"€1.234,56"}]}"""
        // Symbols and separators are stripped rather than losing the line.
        assertEquals(123456L, extractor.parse(reply, "EUR").proposals.single().monthlyAmountMinor)
    }

    @Test
    fun `a line without a usable amount is dropped rather than guessed`() {
        val reply = """
            {"commitments":[
              {"name":"Readable","monthlyAmount":10.00},
              {"name":"No amount"},
              {"name":"Unreadable amount","monthlyAmount":"about twenty euros"},
              {"name":"Zero","monthlyAmount":0},
              {"monthlyAmount":5.00}
            ]}
        """.trimIndent()

        val proposals = extractor.parse(reply, "EUR").proposals
        assertEquals(listOf("Readable"), proposals.map { it.name })
    }

    @Test
    fun `an unknown kind falls back to instalment rather than failing`() {
        val reply = """{"commitments":[{"name":"X","kind":"MORTGAGE_THING","monthlyAmount":5}]}"""
        assertEquals(CommitmentKind.INSTALMENT, extractor.parse(reply, "EUR").proposals.single().kind)
    }

    @Test
    fun `the currency in the picture wins over the default`() {
        val reply = """{"commitments":[{"name":"UK loan","monthlyAmount":50,"currency":"gbp"}]}"""
        assertEquals("GBP", extractor.parse(reply, "EUR").proposals.single().currency)
    }

    @Test
    fun `a nonsense currency falls back to the account's own`() {
        val reply = """{"commitments":[{"name":"X","monthlyAmount":5,"currency":"pounds"}]}"""
        assertEquals("EUR", extractor.parse(reply, "EUR").proposals.single().currency)
    }

    @Test
    fun `an impossible day of the month is left empty`() {
        val reply = """{"commitments":[{"name":"X","monthlyAmount":5,"dayOfMonth":47}]}"""
        assertNull(extractor.parse(reply, "EUR").proposals.single().dayOfMonth)
    }

    @Test(expected = Exception::class)
    fun `a picture with no plan in it is reported rather than invented`() {
        extractor.parse("""{"summary":"A photo of a cat.","commitments":[]}""", "EUR")
    }

    @Test(expected = Exception::class)
    fun `a reply that is not json is refused`() {
        extractor.parse("I had trouble reading that image, sorry!", "EUR")
    }
}
