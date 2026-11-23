package com.corejbh.zerowallet.core

import com.corejbh.zerowallet.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The wallet layer's identity half: the phrase this device holds, the flows that create,
 * restore or reveal it, and the wipe that takes it away again.
 *
 * The phrase itself lives in [SecureStore], sealed by a Keystore key. Nothing here writes a
 * plain copy anywhere, and nothing here decides whether a prompt is needed: a seal that
 * cannot run without the device lock comes back as [SeedSealRequired], and the screen that
 * is creating or restoring the wallet asks for the lock and calls again. A read that needs
 * the lock comes back as "no seed" and leaves the wallet on its lock screen.
 */
internal class WalletLayer(
    private val core: WalletCore,
    private val runtime: NodeRuntime
) {

    /**
     * Reads the saved settings once and, when a wallet is stored, starts its node.
     *
     * A wallet whose key is bound to the device lock is not opened here on the app's first
     * frame: the read says it needs an authentication, and the wallet shows its lock screen
     * with no balance or history behind it.
     */
    fun bootstrap() {
        synchronized(this) {
            if (core.bootstrapped) return
            core.bootstrapped = true
        }
        core.prefs.migrateLegacyEsploraDefault()
        val network = core.prefs.network
        // A store left one rename away by an interrupted channel-backup import is put back
        // before anything reads the chain's directory. The start path does the same under the
        // node lock; doing it here as well means the state the screen shows first already
        // matches the wallet it will open.
        runtime.recoverNodeDirs(network)
        // A node the user stopped stays stopped even after Android killed the process.
        core.lifecycle.restoreManualStop(core.prefs.nodeStoppedByUser)

        val read = core.secureStore.readSeed()
        val seed = (read as? SecureStore.SeedRead.Ok)?.mnemonic
        if (seed == null && read !is SecureStore.SeedRead.Absent) {
            when (read) {
                SecureStore.SeedRead.NeedsAuthentication -> {
                    // A wallet is here and it is this device's own: it is simply waiting for
                    // the user. Nothing is started, and no balance or history is shown.
                    core.update {
                        it.copy(
                            network = network,
                            esploraUrl = core.prefs.esploraUrl(network),
                            rgsEnabled = core.prefs.rapidGossipSync && network.rgsSupported,
                            lockOnOpen = core.prefs.lockOnOpen,
                            screenProtection = core.prefs.screenProtection,
                            bootstrapped = true,
                            seedPresent = true,
                            setupRequired = false,
                            seedLocked = true,
                            backedUp = core.prefs.seedBackedUp,
                            seedProtection = core.protectionStatus()
                        )
                    }
                }
                SecureStore.SeedRead.Invalidated -> core.reportInvalidatedKey()
                else -> {
                    // A wallet exists but the Keystore key could not decrypt it. Never
                    // overwrite it: the only safe way forward is restoring the recovery
                    // phrase.
                    core.update {
                        it.copy(
                            network = network,
                            esploraUrl = core.prefs.esploraUrl(network),
                            lockOnOpen = core.prefs.lockOnOpen,
                            screenProtection = core.prefs.screenProtection,
                            bootstrapped = true,
                            seedPresent = false,
                            setupRequired = true,
                            unlockError = true,
                            seedProtection = core.protectionStatus(),
                            error = "The stored recovery phrase could not be unlocked on this device."
                        )
                    }
                    // There is no node to start, but the card still shows a fiat figure.
                    runtime.startMarketLoop()
                }
            }
            return
        }
        core.update {
            it.copy(
                network = network,
                esploraUrl = core.prefs.esploraUrl(network),
                rgsEnabled = core.prefs.rapidGossipSync && network.rgsSupported,
                lockOnOpen = core.prefs.lockOnOpen,
                screenProtection = core.prefs.screenProtection,
                bootstrapped = true,
                seedPresent = seed != null,
                backedUp = core.prefs.seedBackedUp,
                setupRequired = seed == null,
                seedProtection = core.protectionStatus(),
                lastChannelBackupEpochSecs = core.prefs.lastChannelBackupEpochSecs,
                lastChannelBackupChannels = core.prefs.lastChannelBackupChannels
            )
        }
        if (seed != null) {
            // Show the numbers this wallet had on its last sync while the node comes up, so
            // opening it never reads ₿0. The first real sync replaces them.
            core.update { WalletCache.applyTo(it, core.prefs.cachedWallet(network)) }
            runtime.startNode(seed)
        } else {
            // No wallet yet: nothing to sync, but the price loop still has a screen to serve.
            runtime.startMarketLoop()
        }
    }

    /**
     * Creates a new wallet from SecureRandom entropy and returns the words.
     *
     * The wallet transition runs under [WalletCore.nodeMutex], the way a restore does:
     * without it two taps, or a create crossing a start or a wipe, could both find "no
     * wallet" here, both seal a phrase and both bring a node up on a different one - leaving
     * a node store that belongs to neither of them.
     *
     * The start itself is queued after the lock is released rather than awaited under it. The
     * words are the only copy of the wallet until the user has written them down, so they
     * have to reach the screen even when the chain source is slow and the first start takes
     * its time; a restore can wait, because the user already has its phrase. The start
     * carries the epoch it was created under, so one a wipe or a restore overtook before it
     * got the lock cannot bring a node up on the phrase that used to be here.
     */
    suspend fun createWallet(wordCount: Int = 12): String = withContext(Dispatchers.IO) {
        val (mnemonic, epoch) = core.nodeMutex.withLock {
            check(!core.secureStore.hasSeed()) { "A wallet already exists on this device" }
            val generated = Bip39.randomMnemonic(wordCount)
            // Sealing the phrase means a Keystore operation, which is slow enough to be felt
            // on the main thread and must not run there. It either stores the phrase or says
            // the user has to authenticate first - never a plain text or weaker-key copy.
            sealSeed(generated)
            val created = core.walletEpoch.incrementAndGet()
            core.prefs.seedBackedUp = false
            core.prefs.forgetNodeIdentity()
            core.prefs.walletCreatedAtEpochSecs = System.currentTimeMillis() / 1000L
            // A new wallet starts with nothing; an older snapshot must not show through, on
            // this chain or on any other one it was left under.
            core.prefs.clearAllCachedWallets()
            core.update {
                it.copy(
                    seedPresent = true,
                    backedUp = false,
                    setupRequired = false,
                    seedProtection = core.protectionStatus()
                )
            }
            Pair(generated, created)
        }
        runtime.startNode(mnemonic, epoch)
        mnemonic
    }

    /**
     * Restores an existing 12/24 word wallet.
     *
     * The node that may be running belongs to the wallet being replaced, and its store holds
     * that wallet's channels and keys. So the phrase is not simply swapped in underneath a
     * live node: the node is stopped under [WalletCore.nodeMutex] first, every chain's node
     * data is deleted (nothing from the old wallet may be read back into the new one), and
     * only then is the new phrase saved and the node started from it.
     */
    suspend fun restoreWallet(mnemonic: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = Bip39.normalize(mnemonic)
            val unknown = Bip39.firstUnknownWord(normalized)
            if (unknown != null) {
                throw IllegalArgumentException("Unknown BIP-39 word: $unknown")
            }
            if (!Bip39.isValidMnemonic(normalized)) {
                throw IllegalArgumentException("That recovery phrase is not valid (word count or checksum)")
            }
            core.nodeMutex.withLock {
                // Checked before anything on this phone is touched. The node store of the
                // wallet being replaced is deleted a few lines down, and a seal that turned
                // out to need an authentication afterwards would leave the user with neither
                // the old wallet nor the new one.
                checkSealPossible()
                core.lifecycle.onUserStart()
                core.prefs.nodeStoppedByUser = false
                runtime.stopNodeLocked(keepState = false, nextStarting = true)
                core.clearNodeData()
                sealSeed(normalized)
                // A different wallet: any start still waiting for the lock was asked for by
                // the one this phrase replaces.
                core.walletEpoch.incrementAndGet()
                core.prefs.seedBackedUp = true
                core.prefs.forgetNodeIdentity()
                core.prefs.walletCreatedAtEpochSecs = System.currentTimeMillis() / 1000L
                // The phrase may belong to another wallet than the one this snapshot was taken from,
                // and that is true of every chain's snapshot, not only the active one.
                core.prefs.clearAllCachedWallets()
                core.update {
                    it.copy(
                        seedPresent = true,
                        backedUp = true,
                        setupRequired = false,
                        seedLocked = false,
                        unlockError = false,
                        seedProtection = core.protectionStatus()
                    )
                }
                runtime.startNodeLocked(normalized)
                // The wallet is restored even if the start failed; its error is on the state.
                Unit
            }
        }
    }

    /**
     * The recovery phrase, for the backup screen.
     *
     * It is never taken from a copy this process already had: the stored ciphertext is opened
     * here, every time, and the Keystore answers only while its short window is open. The
     * screen proves it is the user with the device lock first ([Authorization]), and that is
     * the authentication this read rides on; if the window has closed again in between, the
     * words are not shown and the call says why.
     */
    fun revealSeed(): String? {
        val seed = when (val read = core.secureStore.readSeed()) {
            is SecureStore.SeedRead.Ok -> read.mnemonic
            SecureStore.SeedRead.NeedsAuthentication -> null
            SecureStore.SeedRead.Invalidated -> {
                core.reportInvalidatedKey()
                null
            }
            else -> null
        }
        if (seed == null) {
            core.say("The phrase could not be opened. Confirm it is you again and try once more.")
        }
        return seed
    }

    fun confirmSeedBackedUp() {
        core.prefs.seedBackedUp = true
        core.update { it.copy(backedUp = true) }
    }

    /**
     * Opens the wallet after the device lock was proved, and starts its node.
     *
     * The read is tried again: a wallet whose key is bound to the lock never shows balances
     * or history before this point.
     */
    fun unlockSeed() {
        core.scope.launch {
            when (val read = core.secureStore.readSeed()) {
                is SecureStore.SeedRead.Ok -> {
                    core.update {
                        it.copy(
                            seedLocked = false,
                            seedPresent = true,
                            setupRequired = false,
                            seedProtection = core.protectionStatus(),
                            error = null
                        )
                    }
                    runtime.requestStart(read.mnemonic)
                }
                SecureStore.SeedRead.NeedsAuthentication ->
                    core.say("Authentication was not completed. Unlock again to open the wallet.")
                SecureStore.SeedRead.Invalidated -> core.reportInvalidatedKey()
                SecureStore.SeedRead.Absent -> {
                    core.update {
                        it.copy(seedLocked = false, seedPresent = false, setupRequired = true)
                    }
                }
                is SecureStore.SeedRead.Failed -> core.reportBrokenWallet(
                    read.detail?.let { "The wallet could not be opened: $it" }
                        ?: "The wallet could not be opened on this device. Restore it with its recovery phrase."
                )
            }
        }
    }

    /**
     * Deletes this wallet from the phone, and answers when it is actually gone.
     *
     * It is suspended rather than fired off: the settings sheet closes only after the answer
     * arrives, so a wipe that failed (a node that would not stop, a Keystore that refused to
     * delete its key) is still on a screen that can say so instead of looking identical to one
     * that worked. Everything the wallet left on disk goes with it - the node store, the
     * debug log, the sealed copies of exported channel backups, the payment notes and every
     * setting - and the phrase is removed from [SecureStore] together with the Keystore keys
     * that sealed it.
     *
     * @return success once the wallet is gone; a failure carrying the reason it could not be.
     */
    suspend fun wipeWallet(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            core.lifecycle.onUserStop()
            core.nodeMutex.withLock {
                val problems = mutableListOf<String>()
                runtime.stopNodeLocked(keepState = false)
                core.clearNodeData()
                if (core.nodeDataRemains()) {
                    problems += "the node data could not be deleted"
                }
                // The LDK log lines name peers, channels and addresses, and the cache keeps
                // copies of exported backups. Neither is the wallet's money, but both are
                // the wallet's business, so a wipe takes them too.
                runCatching { File(core.appContext.filesDir, "logs").deleteRecursively() }
                runCatching { File(core.appContext.cacheDir, "backups").deleteRecursively() }
                core.walletEpoch.incrementAndGet()
                runCatching { core.secureStore.clear() }
                    .onFailure { problems += "the recovery phrase could not be deleted: ${it.message}" }
                runCatching { SecretStore.deleteKey() }
                runCatching { core.notes.clear() }
                runCatching { core.prefs.clear() }
                if (core.secureStore.hasSeed()) {
                    // Never announce a wipe that left the words behind: the whole point of the
                    // action is that this phone no longer holds the wallet.
                    problems += "the recovery phrase is still stored"
                }
                core.replaceState(
                    WalletState(
                        setupRequired = true,
                        seedPresent = false,
                        bootstrapped = true,
                        seedProtection = core.protectionStatus(),
                        lockOnOpen = core.prefs.lockOnOpen,
                        screenProtection = core.prefs.screenProtection
                    )
                )
                if (problems.isNotEmpty()) {
                    throw IllegalStateException(
                        "The wallet could not be wiped completely: ${problems.joinToString("; ")}. " +
                            "Restore it from your recovery phrase, or try again."
                    )
                }
            }
        }
    }

    /**
     * Seals the phrase in the Keystore, or fails in a way the caller can act on.
     *
     * [SecureStore.SeedSeal.NeedsAuthentication] becomes [SeedSealRequired], which the setup
     * screen turns into a device-lock prompt and a second attempt. Nothing is stored on that
     * path, and nothing is stored with a weaker key either: a wallet whose phrase cannot be
     * sealed properly is not created at all.
     */
    private fun sealSeed(mnemonic: String) {
        when (val seal = core.secureStore.saveSeed(mnemonic)) {
            SecureStore.SeedSeal.Sealed -> Unit
            SecureStore.SeedSeal.NeedsAuthentication -> throw SeedSealRequired()
            is SecureStore.SeedSeal.Failed -> throw IllegalStateException(
                seal.detail?.let { "The recovery phrase could not be sealed on this phone: $it" }
                    ?: "The recovery phrase could not be sealed on this phone."
            )
        }
    }

    /** [sealSeed] without writing anything, for a flow that destroys data before it seals. */
    private fun checkSealPossible() {
        when (val check = core.secureStore.canSeal()) {
            SecureStore.SeedSeal.Sealed -> Unit
            SecureStore.SeedSeal.NeedsAuthentication -> throw SeedSealRequired()
            is SecureStore.SeedSeal.Failed -> throw IllegalStateException(
                check.detail?.let { "This phone cannot seal a recovery phrase: $it" }
                    ?: "This phone cannot seal a recovery phrase."
            )
        }
    }
}
