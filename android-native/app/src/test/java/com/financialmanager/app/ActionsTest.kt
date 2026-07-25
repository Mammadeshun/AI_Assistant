package com.financialmanager.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financialmanager.app.ai.Actions
import com.financialmanager.app.ai.ToolCall
import com.financialmanager.app.data.CommitmentKind
import com.financialmanager.app.data.FinanceDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the assistant is allowed to change, and whether it can be put back.
 *
 * The assistant writes without asking, so undo is the only thing standing
 * between a misheard sentence and a wrong number in someone's finances. These
 * check it actually restores rather than merely claiming to.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActionsTest {

    private lateinit var db: FinanceDatabase
    private lateinit var actions: Actions

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            FinanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        actions = Actions(db, db.changes()) { "EUR" }
    }

    @After
    fun tearDown() = db.close()

    private fun call(name: String, json: String) =
        ToolCall(null, name, Json.parseToJsonElement(json) as JsonObject)

    private suspend fun run(name: String, json: String) = actions.execute(call(name, json))

    @Test
    fun `adds a commitment and records how to undo it`() = runTest {
        val reply = run("add_commitment", """{"name":"Klarna","monthlyAmount":24.99,"kind":"INSTALMENT","dayOfMonth":15}""")
        assertTrue(reply, reply.contains("Klarna"))

        val saved = db.commitments().activeNow().single()
        assertEquals("Klarna", saved.name)
        assertEquals(2499L, saved.amountMinor)
        assertEquals(CommitmentKind.INSTALMENT, saved.kind)
        assertEquals(15, saved.dayOfMonth)

        val change = db.changes().recent().first().single()
        assertTrue(change.summary, change.summary.contains("Klarna"))

        assertTrue(actions.undo(change))
        assertTrue(db.commitments().activeNow().isEmpty())
    }

    @Test
    fun `undoing an edit restores every field, not just the amount`() = runTest {
        run("add_commitment", """{"name":"Gym","monthlyAmount":30,"kind":"SUBSCRIPTION","dayOfMonth":3}""")
        run("update_commitment", """{"name":"Gym","monthlyAmount":45,"dayOfMonth":9,"newName":"Gym Plus"}""")

        val changed = db.commitments().activeNow().single()
        assertEquals("Gym Plus", changed.name)
        assertEquals(4500L, changed.amountMinor)

        val edit = db.changes().recent().first().first()
        assertTrue(actions.undo(edit))

        val restored = db.commitments().activeNow().single()
        assertEquals("Gym", restored.name)
        assertEquals(3000L, restored.amountMinor)
        assertEquals(3, restored.dayOfMonth)
        assertEquals(CommitmentKind.SUBSCRIPTION, restored.kind)
    }

    @Test
    fun `a deleted commitment comes back whole`() = runTest {
        run("add_commitment", """{"name":"Netflix","monthlyAmount":13.99,"dayOfMonth":8}""")
        run("remove_commitment", """{"name":"Netflix"}""")
        assertTrue(db.commitments().activeNow().isEmpty())

        val removal = db.changes().recent().first().first()
        assertTrue(actions.undo(removal))

        val restored = db.commitments().activeNow().single()
        assertEquals("Netflix", restored.name)
        assertEquals(1399L, restored.amountMinor)
        assertEquals(8, restored.dayOfMonth)
    }

    @Test
    fun `cash is set and put back to what it was`() = runTest {
        run("set_cash", """{"amount":50}""")
        assertEquals(5000L, db.cash().amountNow("EUR"))

        run("set_cash", """{"amount":20}""")
        assertEquals(2000L, db.cash().amountNow("EUR"))

        // Undo restores the previous figure, not zero.
        val second = db.changes().recent().first().first()
        assertTrue(actions.undo(second))
        assertEquals(5000L, db.cash().amountNow("EUR"))
    }

    @Test
    fun `a commitment is found without an exact name`() = runTest {
        run("add_commitment", """{"name":"Klarna - Zara order","monthlyAmount":10}""")
        val reply = run("update_commitment", """{"name":"klarna","monthlyAmount":12}""")

        assertTrue(reply, reply.contains("12"))
        assertEquals(1200L, db.commitments().activeNow().single().amountMinor)
    }

    @Test
    fun `editing something that is not there says so instead of creating it`() = runTest {
        val reply = run("update_commitment", """{"name":"Nothing like this","monthlyAmount":5}""")

        assertTrue(reply, reply.contains("no commitment"))
        assertTrue(db.commitments().activeNow().isEmpty())
    }

    @Test
    fun `an invented category is refused rather than silently created`() = runTest {
        val reply = run("set_budget", """{"category":"Yachts","monthlyLimit":100}""")

        assertTrue(reply, reply.contains("no category"))
        assertTrue(db.budgets().all().first().isEmpty())
    }

    @Test
    fun `a missing amount is reported rather than guessed at`() = runTest {
        val reply = run("add_commitment", """{"name":"Something"}""")

        assertTrue(reply, reply.contains("amount is needed"))
        assertTrue(db.commitments().activeNow().isEmpty())
    }

    @Test
    fun `an unknown tool name is refused`() = runTest {
        assertTrue(run("delete_everything", "{}").contains("no tool"))
    }

    @Test
    fun `an amount written the european way is read correctly`() = runTest {
        // Models hand back "€1.234,56" often enough to matter, and reading it
        // as 1.23 would be a hundredfold error in someone's plan.
        run("add_commitment", """{"name":"Loan","monthlyAmount":"€1.234,56"}""")
        assertEquals(123456L, db.commitments().activeNow().single().amountMinor)
    }

    @Test
    fun `a transaction is added and removed again by undo`() = runTest {
        run("add_transaction", """{"description":"Coffee","amount":3.50,"isSpending":true,"date":"2026-07-01"}""")

        val added = db.transactions().searchNow("Coffee").single()
        assertEquals(-350L, added.amountMinor)

        val change = db.changes().recent().first().first()
        assertTrue(actions.undo(change))
        assertTrue(db.transactions().searchNow("Coffee").isEmpty())
    }

    @Test
    fun `refiling a merchant moves its transactions and can be reversed`() = runTest {
        run("add_transaction", """{"description":"Bar Centrale","amount":2,"isSpending":true}""")
        val before = db.transactions().searchNow("Bar Centrale").single().category

        run("recategorise_merchant", """{"merchant":"Bar Centrale","category":"Entertainment"}""")
        assertEquals("Entertainment", db.transactions().searchNow("Bar Centrale").single().category)

        val change = db.changes().recent().first().first()
        assertTrue(actions.undo(change))
        assertEquals(before, db.transactions().searchNow("Bar Centrale").single().category)
    }

    @Test
    fun `an undone change stops being offered`() = runTest {
        run("set_cash", """{"amount":10}""")
        val change = db.changes().recent().first().single()

        actions.undo(change)

        assertTrue(db.changes().recent().first().isEmpty())
        assertNull(db.changes().recent().first().firstOrNull())
    }
}
