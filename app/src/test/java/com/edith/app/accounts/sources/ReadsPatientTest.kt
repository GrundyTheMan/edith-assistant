package com.edith.app.accounts.sources

import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Provider
import com.edith.app.core.error.Outcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C38: a source that is slow by nature (Jarvis, through Outlook) gets its own, longer limit; others keep the short one. */
class ReadsPatientTest {
    private val quick = Account("q", Provider.Microsoft, "q@example.com", "quick")
    private val slow = Account("s", Provider.Jarvis, "BEN-PC", "Work")

    private class Plain
    private class Patiently(override val patienceMs: Long) : Patient

    @Test fun `a patient source is given its own limit, a plain one the short limit`() = runBlocking {
        val r = Reads.readAll(
            listOf(Readable(quick, AccountState.Connected, Plain()), Readable(slow, AccountState.Connected, Patiently(2_000))),
            timeoutMs = 50,
        ) { delay(300); Outcome.Success("late") }
        assertEquals(listOf("Work"), r.results.map { it.first.label })
        assertEquals("quick", r.failures.single().account.label)
    }

    @Test fun `a patient source that still takes too long is named, with the limit it had`() = runBlocking {
        val r = Reads.readAll(listOf(Readable(slow, AccountState.Connected, Patiently(1_000))), timeoutMs = 50) { delay(5_000); Outcome.Success("never") }
        val line = r.failures.single().line
        assertTrue(line, line.contains("within 1 seconds") && line.contains("Outlook"))
    }
}
