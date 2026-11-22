package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.PendingAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The callback a prompt still owes an answer to.
 *
 * Regression cover for the eighth review: the send sheet is busy from the moment its prompt
 * opens, and backgrounding the app while the biometric dialog was up made AppLock supersede
 * the attempt without ever calling back. The system's own error for the cancelled prompt
 * belonged to a stale token and was dropped, so the sheet stayed busy with no prompt on screen
 * and its button did nothing for the rest of the session.
 */
class PendingAuthTest {

    @Test
    fun anAbandonedPromptTellsItsCallerItEnded() {
        val auth = PendingAuth()
        val answers = mutableListOf<Boolean>()
        auth.begin { answers.add(it) }

        assertTrue(auth.isWaiting)
        assertTrue(auth.abandon())

        assertEquals(listOf(false), answers)
        assertFalse(auth.isWaiting)
    }

    @Test
    fun anAttemptIsAnsweredOnlyOnce() {
        val auth = PendingAuth()
        val answers = mutableListOf<Boolean>()
        auth.begin { answers.add(it) }

        assertTrue(auth.abandon())
        // The system's error for the prompt we already cancelled arrives after this; the
        // caller must not hear a second, contradicting answer.
        assertFalse(auth.abandon())

        assertEquals(listOf(false), answers)
    }

    @Test
    fun aSettledPromptOwesNothing() {
        val auth = PendingAuth()
        val answers = mutableListOf<Boolean>()
        auth.begin { answers.add(it) }
        auth.settle()

        assertFalse(auth.abandon())
        assertTrue(answers.isEmpty())
    }

    @Test
    fun aReplacedAttemptIsTheOnlyOneOwed() {
        val auth = PendingAuth()
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        // A second prompt replaces the first; AppLock abandons the first before it begins the
        // second, and the replacement is the one that still has to be answered.
        auth.begin { first.add(it) }
        auth.abandon()
        auth.begin { second.add(it) }

        assertTrue(auth.isWaiting)
        assertTrue(auth.abandon())

        assertEquals(listOf(false), first)
        assertEquals(listOf(false), second)
    }

    @Test
    fun nothingIsOwedBeforeAPromptStarts() {
        val auth = PendingAuth()
        assertFalse(auth.isWaiting)
        assertFalse(auth.abandon())
    }
}
