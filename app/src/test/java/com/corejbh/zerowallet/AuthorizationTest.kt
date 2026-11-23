package com.corejbh.zerowallet

import com.corejbh.zerowallet.core.Authorization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate in front of every privileged action.
 *
 * Regression cover for the eighth review: the sheets used to take the device lock's callback
 * as a plain `(String, (Boolean) -> Unit) -> Unit` and write the action into the lambda body,
 * so the boolean the prompt handed back was never read. Cancelling the prompt - or a phone
 * with no screen lock at all - ran the action anyway: a channel funded, a backup restored, a
 * wallet wiped, all without the user proving it was them.
 */
class AuthorizationTest {

    /** A prompt that answers with whatever the test decides, and counts how often it ran. */
    private class FakePrompt(private val answer: () -> Boolean?) {
        var asked = 0
        var lastReason: String? = null

        fun request(): (String, (Boolean) -> Unit) -> Unit = { reason, onResult ->
            asked++
            lastReason = reason
            answer()?.let(onResult)
        }
    }

    private fun counter(): Pair<() -> Int, () -> Unit> {
        var runs = 0
        return ({ runs }) to { runs++ }
    }

    @Test
    fun aGrantedPromptRunsTheActionOnce() {
        val prompt = FakePrompt { true }
        val (runs, action) = counter()
        var denied = 0

        Authorization(prompt.request()).guarded("Authenticate to wipe this wallet.", { denied++ }, action)

        assertEquals(1, runs())
        assertEquals(0, denied)
        assertEquals(1, prompt.asked)
        assertEquals("Authenticate to wipe this wallet.", prompt.lastReason)
    }

    @Test
    fun aCancelledPromptRunsNothingAndSaysSo() {
        val prompt = FakePrompt { false }
        val (runs, action) = counter()
        var denied = 0

        Authorization(prompt.request()).guarded("Authenticate to wipe this wallet.", { denied++ }, action)

        assertEquals(0, runs())
        assertEquals(1, denied)
    }

    @Test
    fun aPhoneWithNoScreenLockRunsNothing() {
        // WalletApp answers false itself when there is no lock to ask with; the action behind
        // the prompt must not run in that case either.
        val prompt = FakePrompt { false }
        val (runs, action) = counter()

        Authorization(prompt.request()).guarded("Authenticate to fund this channel.", {}, action)

        assertEquals(0, runs())
    }

    @Test
    fun aPromptThatNeverAnswersRunsNeitherSide() {
        val prompt = FakePrompt { null }
        val (runs, action) = counter()
        var denied = 0

        Authorization(prompt.request()).guarded("Authenticate to send.", { denied++ }, action)

        assertEquals(0, runs())
        assertEquals(0, denied)
        // It did ask; it is the answer that has not arrived yet.
        assertEquals(1, prompt.asked)
    }

    @Test
    fun anAuthorizationWithNothingBehindItApprovesNothing() {
        val (runs, action) = counter()
        var denied = 0

        Authorization.NONE.guarded("Authenticate to show the recovery phrase.", { denied++ }, action)

        assertEquals(0, runs())
        assertEquals(1, denied)
    }

    @Test
    fun theReasonReachesThePromptUnchanged() {
        var seen: String? = null
        val authorization = Authorization { reason, onResult ->
            seen = reason
            onResult(true)
        }

        authorization.guarded("Authenticate to save an encrypted channel backup.") { }

        assertTrue(seen == "Authenticate to save an encrypted channel backup.")
    }
}
