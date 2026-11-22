package com.jbitmstr.purewallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the tenth review's fixes, for the parts a JVM test cannot execute.
 *
 * There is no Android Keystore and no Activity on the unit-test JVM, so the rules that live
 * inside `SecureStore`, `NodeManager`, `AnAppLock` prompt and two Compose sheets are pinned by
 * reading the code: what a repair path must call, what must no longer be a fallback, and the
 * order two statements have to appear in. The behaviour behind each is covered where it can be
 * moved into plain Kotlin (`NodeDirs`, `HeldRequest`, `AmountAgreement`, the BIP-21 parser).
 */
class TenthReviewSourceTest {

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            listOf(File(dir, relative), File(dir, "app/$relative")).firstOrNull { it.isFile }
                ?.let { return it.readText() }
            dir = dir?.parentFile
        }
        throw AssertionError("Could not find $relative from ${File("").absolutePath}")
    }

    private val secureStore = "src/main/java/com/jbitmstr/purewallet/core/SecureStore.kt"

    /**
     * The body of one declaration, from its `fun name(` to the brace that closes it.
     *
     * A declaration can hold several blocks (`try`/`catch`, an `if` chain), so the end is the
     * first closing brace at the declaration's own indentation: everything nested inside is
     * indented further, and this source is formatted that way throughout.
     */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start < 0) throw AssertionError("$signature is gone")
        val end = text.indexOf("\n    }\n", start)
        if (end < 0) throw AssertionError("$signature has no end")
        return text.substring(start, end + 7)
    }

    // ----------------------------------------------------------- SecureStore: a dead key

    @Test
    fun theSealCheckReplacesAKeyTheOldLockKilled() {
        // An invalidated key cannot be brought back by any authentication, so answering
        // "authenticate" for it blocked a restore on that phone forever.
        val canSeal = body(source(secureStore), "fun canSeal()")
        assertTrue("canSeal has to notice the invalidated key", canSeal.contains("SealProbe.Invalidated ->"))
        assertTrue("and make a new one", canSeal.contains("replaceInvalidatedKey()"))
        val replacement = body(source(secureStore), "private fun replaceInvalidatedKey()")
        assertTrue("which regenerates it", replacement.contains("regenerate()"))
    }

    @Test
    fun aKeyThatDiedIsReplacedOnTheNextSealToo() {
        val keyForSeal = body(source(secureStore), "private fun keyForSeal()")
        assertTrue(
            "a seal is the one moment the phrase is in hand, so the dead key is replaced there",
            keyForSeal.contains("KeyPermanentlyInvalidatedException") &&
                keyForSeal.contains("replaceInvalidatedKey()")
        )
    }

    // ------------------------------------------------------- SecureStore: no weak stand-in

    @Test
    fun aRefusedBoundKeyIsNotSilentlyReplacedByAnUnboundOne() {
        val text = source(secureStore)
        val generate = body(text, "private fun generate()")
        assertFalse(
            "an unbound key is no longer generated as a fallback when the bound one is refused",
            generate.contains("getOrElse { generateKey(authBound = false) }")
        )
        assertTrue("the refusal is reported instead", generate.contains("throw KeyBindingRefused"))
        assertTrue("and it is a type of its own", text.contains("class KeyBindingRefused"))
        // The one remaining unbound key is for a phone with no screen lock at all.
        assertTrue(
            "a phone without a lock still gets a key, and is told about it",
            generate.contains("REASON_NO_SCREEN_LOCK")
        )
    }

    // ------------------------------------------- the node layer: the endpoint restart

    /** Where the node's start, stop and settings live since the 1.2.0 layer split. */
    private val nodeRuntime = "src/main/java/com/jbitmstr/purewallet/core/NodeRuntime.kt"

    /** Where the encrypted channel backup lives since the 1.2.0 layer split. */
    private val backupLayer = "src/main/java/com/jbitmstr/purewallet/core/BackupLayer.kt"

    @Test
    fun changingTheEndpointReadsTheKeystoreOffTheCallersThreadAndUnderTheLock() {
        val text = source(nodeRuntime)
        val setEsplora = body(text, "fun setEsploraUrl(")
        assertTrue("the restart runs in a coroutine", setEsplora.contains("core.scope.launch {"))
        assertTrue("under the node lock", setEsplora.contains("core.nodeMutex.withLock {"))
        assertTrue("and it is checked against the wallet it was asked for", setEsplora.contains("core.walletEpoch.get()"))

        // The seed read must be inside the launched block, never before it: it was a Keystore
        // call on the settings sheet's (main) thread.
        val launchAt = setEsplora.indexOf("core.scope.launch {")
        val seedAt = setEsplora.indexOf("core.seedOrNull()")
        assertTrue("no seed read before the coroutine", seedAt > launchAt)

        val lockAt = setEsplora.indexOf("core.nodeMutex.withLock {")
        assertTrue("no seed read outside the lock", seedAt > lockAt)
        assertTrue(
            "and the restart is still offered only when the check says so",
            setEsplora.contains("esploraChangeNeedsRestart(before, after, running, seed != null)")
        )
    }

    @Test
    fun aStartRepairsAnImportThatWasInterrupted() {
        val text = source(nodeRuntime)
        val start = body(text, "suspend fun startNodeLocked(")
        assertTrue("the start puts a missing store back", start.contains("recoverNodeDirs(network)"))
        val recover = body(text, "fun recoverNodeDirs(")
        assertTrue("from the newest displaced folder", recover.contains("NodeDirs.newestPrevious"))
        assertTrue(
            "and clears the fragments of every half-written import",
            recover.contains("NodeDirs.incomingDirs")
        )
        assertTrue(
            "the import names its folders per chain",
            source(backupLayer).contains("NodeDirs.incoming(network.id, stamp)") &&
                source(backupLayer).contains("NodeDirs.previous(network.id, stamp)")
        )
    }

    @Test
    fun aBackupExportWithoutARunningNodeStillNamesTheWallet() {
        val text = source(backupLayer)
        val export = body(text, "suspend fun exportChannelBackup()")
        assertTrue("the identity is read for the manifest", export.contains("backupIdentity(network)"))
        assertTrue(
            "and it is read before the node is stopped",
            export.indexOf("backupIdentity(network)") < export.indexOf("stopNodeLocked")
        )
        val identity = body(text, "private fun backupIdentity(")
        assertTrue("it falls back to what this device last recorded", identity.contains("prefs.lastNodeId"))
        assertTrue("and to its channel count", identity.contains("prefs.lastKnownChannelCount"))
        assertFalse("an empty node id is no longer written", export.contains("nodeId = _state.value.nodeId,"))
    }

    @Test
    fun anOlderBackupFormatIsNamedInTheRestoreReview() {
        val text = source(backupLayer)
        assertTrue(
            "the file's format is passed to the check",
            text.contains("formatVersion = file.inputStream().use { ChannelBackup.formatOf(it) }")
        )
        assertTrue(
            "twice: opening and importing",
            text.split("formatVersion = file.inputStream().use { ChannelBackup.formatOf(it) }").size == 3
        )
    }

    @Test
    fun noRecordIsEverReadBackFromPlainText() {
        // 1.1.18 kept a one-time marker that let `Prefs` and `PaymentNotes` hand back values an
        // older build wrote unsealed. That window is what 1.2.0 removes: a value this app did
        // not seal is not a balance, a height or a note, and there is no flag left to make one.
        val sources = listOf(
            "src/main/java/com/jbitmstr/purewallet/core/Prefs.kt",
            "src/main/java/com/jbitmstr/purewallet/core/PaymentNotes.kt",
            "src/main/java/com/jbitmstr/purewallet/core/SecretStore.kt"
        ).associateWith { source(it) }
        sources.forEach { (file, text) ->
            assertFalse("$file still knows a plaintext migration", text.contains("migratePlainText"))
            assertFalse("$file still takes an allowPlainText flag", text.contains("allowPlainText"))
            assertFalse("$file still names the migration marker", text.contains("KEY_PLAIN_TEXT_MIGRATED"))
        }
        assertTrue(
            "a value without the seal marker is refused where it is opened",
            sources.getValue("src/main/java/com/jbitmstr/purewallet/core/SecretStore.kt")
                .contains("A stored value has no seal marker and was refused")
        )
    }

    // ------------------------------------------------------------- the two sheets

    @Test
    fun theChannelsSheetChecksThePeerBeforeAskingForTheLock() {
        val text = source("src/main/java/com/jbitmstr/purewallet/ui/sheets/ChannelsSheet.kt")
        val open = body(text, "fun openChannel()")
        val peerAt = open.indexOf("PaymentInputParser.parsePeer")
        val guardedAt = open.indexOf("authorization.guarded(")
        assertTrue("the peer is parsed", peerAt >= 0)
        assertTrue("before the prompt, not after it", guardedAt > peerAt)
        assertFalse("and the raw text is not sent on unchecked", open.contains("vm.openChannel(peerUri.trim()"))
    }

    @Test
    fun theSendSheetSignsAtTheRateTheConfirmationShowed() {
        val text = source("src/main/java/com/jbitmstr/purewallet/ui/sheets/SendSheet.kt")
        assertTrue(
            "the dialog passes the rate it was drawn for",
            text.contains("dispatch(confirmed.target, confirmed.amount, confirmed.rateSatPerVb)")
        )
        val dispatch = body(text, "fun dispatch(")
        assertTrue("which the dispatch uses", dispatch.contains("confirmedRate"))
        assertTrue("and hands to the payment", dispatch.contains("vm.pay(current, amount, viaLightning, rateToSign)"))
    }

    // ---------------------------------------------------------------- the app lock

    @Test
    fun theLockAnswersAHeldRequestInsteadOfOverwritingIt() {
        val text = source("src/main/java/com/jbitmstr/purewallet/core/AppLock.kt")
        assertTrue("a request held for the next resume is a queue of one", text.contains("HeldRequest<Pending>"))
        assertTrue("which answers the displaced request", text.contains("held.replace(Pending("))
        assertFalse(
            "the old assignment that dropped it is gone",
            text.contains("pending = Pending(title, subtitle, onResult)")
        )
        assertTrue("and disposing answers it", body(text, "fun dispose()").contains("held.clear"))
    }
}
