package com.jbitmstr.purewallet.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.BackgroundSyncConfig
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Bolt11InvoiceDescription
import org.lightningdevkit.ldknode.Bolt12Invoice
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.EsploraSyncConfig
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.FeeRate
import org.lightningdevkit.ldknode.LogLevel
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.Offer
import org.lightningdevkit.ldknode.OfferAmount
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.lightningdevkit.ldknode.QrPaymentResult
import com.jbitmstr.purewallet.BuildConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One row of the history list. */
data class PaymentRow(
    val id: String,
    val lightning: Boolean,
    val incoming: Boolean,
    val status: String,
    val succeeded: Boolean,
    val amountSats: Long,
    val feeSats: Long,
    val description: String?,
    val timestampSecs: Long,
    val detail: String?
)

/** One row of the channel list. */
data class ChannelRow(
    val userChannelId: String,
    val channelId: String,
    val peer: String,
    val capacitySats: Long,
    val localSats: Long,
    val remoteSats: Long,
    val state: String,
    val ready: Boolean,
    val usable: Boolean,
    val outbound: Boolean,
    val shortChannelId: String?
)

/** One connected or remembered peer. */
data class PeerRow(
    val nodeId: String,
    val address: String,
    val connected: Boolean,
    val persisted: Boolean
)

/** Everything the UI renders. */
data class WalletState(
    val network: ChainNetwork = ChainNetwork.MAINNET,
    val esploraUrl: String = ChainNetwork.MAINNET.defaultEsploraUrl,
    /**
     * The Esplora endpoint the running node is actually reading from. It differs from
     * [esploraUrl] when the saved endpoint did not answer and a public fallback took over.
     */
    val activeEsploraUrl: String? = null,
    val rgsEnabled: Boolean = false,
    val lockOnOpen: Boolean = false,
    val screenProtection: Boolean = true,
    val bootstrapped: Boolean = false,
    val seedPresent: Boolean = false,
    /**
     * What the Keystore reports about the key that opens the stored phrase. Null until the
     * wallet has been read; [SecureStore.SeedProtection.UNBOUND] is shown in Settings because
     * a key that opens without the screen lock is worth knowing about rather than assuming
     * away.
     */
    val seedProtection: SecureStore.SeedProtectionStatus? = null,
    val backedUp: Boolean = false,
    val setupRequired: Boolean = true,
    val unlockError: Boolean = false,
    /**
     * A wallet is stored here, but its key is bound to the device lock and its short window
     * has passed, so this process cannot open it yet. The wallet shows its lock screen until
     * the user authenticates ([NodeManager.unlockSeed]).
     */
    val seedLocked: Boolean = false,
    val starting: Boolean = false,
    val running: Boolean = false,
    val busy: String? = null,
    val nodeId: String = "",
    val alias: String = "",
    val listeningAddresses: List<String> = emptyList(),
    val onchainTotalSats: Long = 0L,
    val onchainSpendableSats: Long = 0L,
    val onchainPendingSats: Long = 0L,
    val lightningBalanceSats: Long = 0L,
    val payments: List<PaymentRow> = emptyList(),
    val channels: List<ChannelRow> = emptyList(),
    val peers: List<PeerRow> = emptyList(),
    val bestHeight: Int? = null,
    val lastSyncEpochSecs: Long? = null,
    val syncing: Boolean = false,
    val fiat: FiatQuote? = null,
    val error: String? = null
) {
    val totalSats: Long get() = onchainTotalSats + lightningBalanceSats
}

data class InvoiceResult(val invoice: String, val paymentHash: String)

/** A ready-to-save channel backup: sealed bytes, a suggested name and what went into it. */
class ChannelBackupExport(
    val bytes: ByteArray,
    val fileName: String,
    val manifest: BackupManifest,
    val fileCount: Int,
    val sizeBytes: Long
)

/** A payment request after the node has resolved its amount and description. */
data class ResolvedTarget(
    val input: PaymentInput,
    val description: String,
    val amountSats: Long?,
    val amountEditable: Boolean,
    /**
     * The Lightning request carried by the same BIP-21 QR as [input], when this wallet can
     * pay it over Lightning. Paying it this way keeps the on-chain address as the fallback
     * the sender offered; the caller decides which route to take.
     */
    val lightning: PaymentInput? = null
) {
    /** True when a BIP-21 QR offers both an on-chain address and a Lightning request. */
    val hasLightningAlternative: Boolean get() = lightning != null

    /**
     * Whether "send the whole on-chain balance" applies to this request.
     *
     * Sweeping the balance replaces whatever amount the request asked for, so it is only
     * offered for a request whose amount is the sender's to choose. A fixed-amount invoice
     * or BIP-21 QR - `amountEditable` false - must be paid for exactly what it says; letting
     * send-all run on one would silently pay a different payment than the one on screen.
     */
    val allowsSendAll: Boolean
        get() = input.kind == PaymentInputKind.ONCHAIN && amountEditable
}

enum class FeePriority(val label: String, val detail: String) {
    NORMAL("Normal", "Usually confirms within ~30 minutes"),
    FAST("Fast", "Usually confirms within ~10 minutes");

    /** The rate this priority pays, always inside the range [FeeEstimates] allows. */
    fun satPerVb(rates: FeeRates?): Long = FeeEstimates.clamp(
        when (this) {
            NORMAL -> rates?.halfHourSatPerVb ?: 8L
            FAST -> rates?.fastestSatPerVb ?: 16L
        }
    )
}

/**
 * Owns the single on-device ldk-node instance: build, start, event loop, wallet state and
 * every real action (invoices, offers, on-chain sends, channels).
 *
 * There is no backend, no LSP and no custodial service in this path: the node talks to
 * the Bitcoin network through Esplora and to Lightning peers directly over TCP.
 */
class NodeManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val secureStore = SecureStore(appContext)
    private val notes = PaymentNotes(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var node: Node? = null
    private var eventJob: Job? = null
    private var fiatJob: Job? = null
    private var bootstrapped = false

    /**
     * The recovery phrase, once this process has opened it.
     *
     * The stored copy is sealed with a Keystore key that, on Android 11+ with a screen lock,
     * only opens shortly after the user authenticated. Reading it again for every action
     * would throw the prompt back at the user mid-payment, so it is kept here for the life of
     * the process (and never written anywhere): the process is the same lifetime the node
     * has, and it goes away with a wipe, a restore or the process being killed.
     */
    @Volatile
    private var openedSeed: String? = null

    /** The chain source the running node was built with, when it is not the saved one. */
    private var activeEsploraUrl: String? = null

    /**
     * Lightning payments this process has sent and is still waiting to hear settled.
     *
     * A Lightning send that returned without an error is in flight, not settled: the node
     * only knows it went out when the `PaymentSuccessful` event arrives, and `PaymentFailed`
     * when it did not. The set makes that state explicit so a payment is never announced as
     * sent before the network says so. Kept only for the life of the process; the history
     * list is the durable record.
     */
    private val pendingLightning: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * What the Keystore says about the stored phrase's key, or null when it cannot be read.
     * Read from the key itself, so a wallet sealed by an older build, by the single-key
     * fallback or before this phone had a screen lock is reported as it actually is.
     */
    private fun protectionStatus(): SecureStore.SeedProtectionStatus? =
        runCatching { secureStore.protectionStatus() }.getOrNull()

    /**
     * Serialises every start and stop. Building a node takes seconds, and a restart, a
     * settings change or a return to the foreground can all ask for one in that window; the
     * lock makes them queue instead of racing or being silently dropped.
     */
    private val nodeMutex = Mutex()

    /** Starts that are running or waiting for [nodeMutex]; at most one is worth queueing. */
    private val queuedStarts = AtomicInteger(0)

    private val lifecycle = NodeLifecycle()

    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ---------------------------------------------------------------- lifecycle

    /**
     * Called when the wallet comes back to the screen.
     *
     * Android freezes or kills the process while the app is in the background, and a start
     * that failed leaves nothing running. There is no foreground service (the wallet shows
     * nothing in the notification bar), so coming back either brings a dead node back — as
     * long as the user did not stop it themselves — or, when it survived, quietly syncs it
     * instead of leaving the numbers from before the screen changed.
     */
    fun onAppForeground() {
        val snapshot = _state.value
        when (lifecycle.foregroundAction(snapshot.seedPresent, snapshot.running, snapshot.starting)) {
            ForegroundAction.REFRESH -> scope.launch { refreshInternal(userInitiated = false) }
            ForegroundAction.START -> {
                val seed = seedOrNull() ?: return
                requestStart(seed)
            }
            ForegroundAction.NONE -> Unit
        }
    }

    /**
     * Called when the app leaves the screen (ON_STOP).
     *
     * The copy of the phrase this process opened goes away with it. Anything that needs the
     * phrase again - showing it, a restart, a channel backup - reopens the stored ciphertext
     * through [seedOrNull], and a key bound to the device lock asks for a fresh
     * authentication there, so backgrounding the app is never a free pass to the words. The
     * node keeps running: hiding the screen is not stopping the Lightning node.
     */
    fun onAppBackground() {
        openedSeed = null
    }

    /**
     * The seed for this process, opening the stored copy once if it is not open yet.
     *
     * Returns null when the wallet cannot be opened without the user: [readSeed] reports
     * that through [SecureStore.SeedRead.NeedsAuthentication], and the state is set so the
     * wallet shows its lock screen instead of pretending there is no wallet. Every caller
     * already has a "no seed, do nothing" branch, so a locked wallet simply does nothing
     * until [unlockSeed] brings it back.
     */
    private fun seedOrNull(): String? {
        openedSeed?.let { return it }
        return when (val read = secureStore.readSeed()) {
            is SecureStore.SeedRead.Ok -> read.mnemonic.also { openedSeed = it }
            SecureStore.SeedRead.NeedsAuthentication -> {
                _state.update {
                    it.copy(seedPresent = true, setupRequired = false, seedLocked = true, bootstrapped = true)
                }
                null
            }
            SecureStore.SeedRead.Invalidated -> {
                reportInvalidatedKey()
                null
            }
            else -> null
        }
    }

    /**
     * Called once the user proved it is them in front of [SecureStore]'s device-lock prompt.
     *
     * The read is tried again, and on success the wallet opens and its node starts: a wallet
     * whose key is bound to the lock never shows balances or history before this point.
     */
    fun unlockSeed() {
        scope.launch {
            when (val read = secureStore.readSeed()) {
                is SecureStore.SeedRead.Ok -> {
                    openedSeed = read.mnemonic
                    _state.update {
                        it.copy(
                            seedLocked = false,
                            seedPresent = true,
                            setupRequired = false,
                            seedProtection = protectionStatus(),
                            error = null
                        )
                    }
                    requestStart(read.mnemonic)
                }
                SecureStore.SeedRead.NeedsAuthentication ->
                    _messages.tryEmit("Authentication was not completed. Unlock again to open the wallet.")
                SecureStore.SeedRead.Invalidated -> reportInvalidatedKey()
                SecureStore.SeedRead.Absent -> {
                    openedSeed = null
                    _state.update {
                        it.copy(seedLocked = false, seedPresent = false, setupRequired = true)
                    }
                }
                is SecureStore.SeedRead.Failed -> reportBrokenWallet(
                    read.detail?.let { "The wallet could not be opened: $it" }
                        ?: "The wallet could not be opened on this device. Restore it with its recovery phrase."
                )
            }
        }
    }

    /**
     * The Keystore key died with the phone's previous screen lock, and the ciphertext cannot
     * be opened by anything on this device any more. That is not a lost wallet: the phrase
     * still restores it, so say exactly what happened and send the user to the restore flow
     * rather than to a blank setup screen.
     */
    private fun reportInvalidatedKey() = reportBrokenWallet(
        "This wallet's key was invalidated when the phone's screen lock was removed or " +
            "reset. Restore the wallet with its recovery phrase."
    )

    /**
     * The stored wallet cannot be opened on this device any more. Never overwrite it: the
     * only safe way forward is restoring the recovery phrase, so the setup screen opens in
     * its restore mode with the reason on it.
     */
    private fun reportBrokenWallet(message: String) {
        openedSeed = null
        _state.update {
            it.copy(
                seedLocked = false,
                seedPresent = false,
                setupRequired = true,
                unlockError = true,
                error = message
            )
        }
    }

    fun bootstrap() {
        synchronized(this) {
            if (bootstrapped) return
            bootstrapped = true
        }
        prefs.migrateLegacyEsploraDefault()
        val network = prefs.network
        // A node the user stopped stays stopped even after Android killed the process.
        lifecycle.restoreManualStop(prefs.nodeStoppedByUser)

        val read = secureStore.readSeed()
        val seed = (read as? SecureStore.SeedRead.Ok)?.mnemonic
        if (seed == null && read !is SecureStore.SeedRead.Absent) {
            when (read) {
                SecureStore.SeedRead.NeedsAuthentication -> {
                    // A wallet is here and it is this device's own: it is simply waiting for
                    // the user. Nothing is started, and no balance or history is shown.
                    _state.update {
                        it.copy(
                            network = network,
                            esploraUrl = prefs.esploraUrl(network),
                            rgsEnabled = prefs.rapidGossipSync,
                            lockOnOpen = prefs.lockOnOpen,
                            screenProtection = prefs.screenProtection,
                            bootstrapped = true,
                            seedPresent = true,
                            setupRequired = false,
                            seedLocked = true,
                            backedUp = prefs.seedBackedUp,
                            seedProtection = protectionStatus()
                        )
                    }
                }
                SecureStore.SeedRead.Invalidated -> reportInvalidatedKey()
                else -> {
                    // A wallet exists but the Keystore key could not decrypt it. Never
                    // overwrite it: the only safe way forward is restoring the recovery
                    // phrase.
                    _state.update {
                        it.copy(
                            network = network,
                            esploraUrl = prefs.esploraUrl(network),
                            lockOnOpen = prefs.lockOnOpen,
                            screenProtection = prefs.screenProtection,
                            bootstrapped = true,
                            seedPresent = false,
                            setupRequired = true,
                            unlockError = true,
                            seedProtection = protectionStatus(),
                            error = "The stored recovery phrase could not be unlocked on this device."
                        )
                    }
                    startFiatLoop()
                }
            }
            return
        }
        openedSeed = seed
        _state.update {
            it.copy(
                network = network,
                esploraUrl = prefs.esploraUrl(network),
                rgsEnabled = prefs.rapidGossipSync,
                lockOnOpen = prefs.lockOnOpen,
                screenProtection = prefs.screenProtection,
                bootstrapped = true,
                seedPresent = seed != null,
                backedUp = prefs.seedBackedUp,
                setupRequired = seed == null,
                seedProtection = protectionStatus()
            )
        }
        if (seed != null) {
            // Show the numbers this wallet had on its last sync while the node comes up, so
            // opening it never reads ₿0. The first real sync replaces them.
            _state.update { WalletCache.applyTo(it, prefs.cachedWallet(network)) }
            startNode(seed)
        } else {
            startFiatLoop()
        }
    }

    /** Creates a new wallet from SecureRandom entropy and returns the words. */
    suspend fun createWallet(wordCount: Int = 12): String = withContext(Dispatchers.IO) {
        check(!secureStore.hasSeed()) { "A wallet already exists on this device" }
        val mnemonic = Bip39.randomMnemonic(wordCount)
        // Sealing the phrase means generating a Keystore key pair, which is slow enough to
        // be felt on the main thread and must not run there.
        secureStore.saveSeed(mnemonic)
        openedSeed = mnemonic
        prefs.seedBackedUp = false
        prefs.walletCreatedAtEpochSecs = System.currentTimeMillis() / 1000L
        // A new wallet starts with nothing; an older snapshot must not show through.
        prefs.clearCachedWallet(prefs.network)
        _state.update {
            it.copy(
                seedPresent = true,
                backedUp = false,
                setupRequired = false,
                seedProtection = protectionStatus()
            )
        }
        startNode(mnemonic)
        mnemonic
    }

    /**
     * Restores an existing 12/24 word wallet.
     *
     * The node that may be running belongs to the wallet being replaced, and its store holds
     * that wallet's channels and keys. So the phrase is not simply swapped in underneath a
     * live node: the node is stopped under [nodeMutex] first, every chain's node data is
     * deleted (nothing from the old wallet may be read back into the new one), and only then
     * is the new phrase saved and the node started from it.
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
            nodeMutex.withLock {
                lifecycle.onUserStart()
                prefs.nodeStoppedByUser = false
                stopNodeLocked(keepState = false, nextStarting = true)
                clearNodeData()
                secureStore.saveSeed(normalized)
                openedSeed = normalized
                prefs.seedBackedUp = true
                prefs.walletCreatedAtEpochSecs = System.currentTimeMillis() / 1000L
                // The phrase may belong to another wallet than the one this snapshot was taken from.
                prefs.clearCachedWallet(prefs.network)
                _state.update {
                    it.copy(
                        seedPresent = true,
                        backedUp = true,
                        setupRequired = false,
                        seedLocked = false,
                        unlockError = false,
                        seedProtection = protectionStatus()
                    )
                }
                startNodeLocked(normalized)
                // The wallet is restored even if the start failed; its error is on the state.
                Unit
            }
        }
    }

    /**
     * The recovery phrase, for the backup screen.
     *
     * It comes from the copy this process opened; a wallet that is still locked asks for the
     * device lock instead of handing out an empty phrase.
     */
    fun revealSeed(): String? {
        val seed = seedOrNull()
        if (seed == null) _messages.tryEmit("Unlock the wallet to see its recovery phrase")
        return seed
    }

    fun confirmSeedBackedUp() {
        prefs.seedBackedUp = true
        _state.update { it.copy(backedUp = true) }
    }

    // ------------------------------------------------------- channel backup

    /**
     * Packs this device's node store into an encrypted `.zwbackup`, sealed with the recovery
     * phrase, ready for the file picker (Google Drive is one of its destinations).
     *
     * The node is stopped only while its files are copied: a running node keeps rewriting
     * channel state, and a half-written monitor is worse than no backup. It is started again
     * before this returns, so the picker does not leave the wallet offline.
     */
    suspend fun exportChannelBackup(): Result<ChannelBackupExport> = withContext(Dispatchers.IO) {
        runCatching {
            nodeMutex.withLock {
                val mnemonic = seedOrNull()
                    ?: throw IllegalStateException("This device has no recovery phrase to seal a backup with")
                val network = prefs.network
                val wasRunning = node != null
                _state.update { it.copy(busy = "Packing channel backup") }
                try {
                    stopNodeLocked(keepState = true)
                    val files = ChannelBackup.collectFiles(networkDir(network))
                    if (files.isEmpty()) {
                        throw IllegalStateException("There is no node data to back up yet")
                    }
                    val manifest = BackupManifest(
                        network = network.id,
                        createdAtEpochSecs = System.currentTimeMillis() / 1000L,
                        nodeId = _state.value.nodeId,
                        channelCount = _state.value.channels.size,
                        appVersion = BuildConfig.VERSION_NAME,
                        wordCount = Bip39.wordCount(mnemonic)
                    )
                    val archive: ByteArray
                    try {
                        archive = ChannelBackup.buildArchive(manifest, files)
                        // The archive is the node's plaintext store in memory; only the
                        // sealed copy below may outlive this block.
                        val sealed = try {
                            ChannelBackup.encrypt(mnemonic, archive)
                        } finally {
                            archive.fill(0)
                        }
                        ChannelBackupExport(
                            bytes = sealed,
                            fileName = ChannelBackup.suggestedFileName(network.id, manifest.createdAtEpochSecs),
                            manifest = manifest,
                            fileCount = files.size,
                            sizeBytes = sealed.size.toLong()
                        )
                    } finally {
                        // Every file that was read off disk is plaintext too.
                        files.forEach { it.bytes.fill(0) }
                    }
                } finally {
                    _state.update { it.copy(busy = null) }
                    if (wasRunning) startNodeLocked(mnemonic)
                }
            }
        }
    }

    /**
     * Replaces this device's node store with the one inside [bytes].
     *
     * The file is opened with the recovery phrase already on this device, so a backup made
     * from a different phrase fails here before anything is touched. The old store is moved
     * aside rather than deleted.
     *
     * Restoring the data and starting a node on it are two steps, and only the first decides
     * whether the old store comes back. A backup that could not be read, or could not be
     * written in place, is rolled back before anything is committed. Once the restored data
     * is in place it stays: a start that failed because the Esplora endpoint timed out, the
     * phone is offline or the Lightning port is taken says nothing about the backup, and
     * throwing a good restore away for it used to leave the user with neither the backup nor
     * the store it replaced. The node is simply started again when the network is back.
     */
    suspend fun importChannelBackup(bytes: ByteArray): Result<BackupManifest> = withContext(Dispatchers.IO) {
        runCatching {
            nodeMutex.withLock {
                val mnemonic = seedOrNull()
                    ?: throw IllegalStateException("Restore your recovery phrase first, then import the backup")
                _state.update { it.copy(busy = "Opening channel backup") }
                val payload = try {
                    // The decrypted archive is the node's plaintext store as well: read it
                    // once and wipe the bytes rather than leaving them in the heap.
                    val archive = ChannelBackup.decrypt(mnemonic, bytes)
                    try {
                        ChannelBackup.readArchive(archive)
                    } finally {
                        archive.fill(0)
                    }
                } finally {
                    _state.update { it.copy(busy = null) }
                }
                val network = ChainNetwork.fromId(payload.manifest.network)
                if (network != prefs.network) {
                    throw IllegalStateException(
                        "This backup was saved on ${network.title}. Switch to ${network.title} in Settings first."
                    )
                }

                stopNodeLocked(keepState = false)
                val dir = networkDir(network)
                val stamp = System.currentTimeMillis()
                val incoming = File(appContext.filesDir, "ldk-node/incoming-$stamp").apply { mkdirs() }
                val previous = File(appContext.filesDir, "ldk-node/previous-$stamp")
                try {
                    try {
                        ChannelBackup.writeFiles(incoming, payload.files)
                        // The files are on disk, so the archive's copy is no longer needed.
                    } finally {
                        payload.files.forEach { it.bytes.fill(0) }
                    }
                    if (dir.exists() && !dir.renameTo(previous)) {
                        throw IllegalStateException("The current node data could not be moved aside")
                    }
                    if (!incoming.renameTo(dir)) {
                        throw IllegalStateException("The backup could not be put in place")
                    }
                } catch (t: Throwable) {
                    // Nothing was committed: the backup itself could not be written over the
                    // store that was here, so put that store back.
                    incoming.deleteRecursively()
                    if (!dir.exists() && previous.exists()) previous.renameTo(dir)
                    throw t
                }

                // From here the restored data is the wallet's store. Only a failure that
                // points at that data itself puts the previous store back.
                val startFailure = startNodeLocked(mnemonic)
                if (startFailure != null) {
                    if (EsploraFallback.isChainSourceFailure(startFailure)) {
                        previous.deleteRecursively()
                        throw IllegalStateException(
                            "The channel backup was restored, but the node could not start yet: " +
                                "${describe(startFailure)} Your restored data is in place - start the " +
                                "node again when the connection is back."
                        )
                    }
                    // The node refused the store the backup carried: keep the one that was
                    // here rather than leaving a wallet that cannot open its own data.
                    stopNodeLocked(keepState = false)
                    dir.deleteRecursively()
                    if (previous.exists()) previous.renameTo(dir)
                    startNodeLocked(mnemonic)
                    throw IllegalStateException(
                        "The node could not start from this backup. The data that was on this phone was kept."
                    )
                }
                previous.deleteRecursively()
                _messages.tryEmit("Channel backup restored")
                payload.manifest
            }
        }
    }

    private fun startNode(mnemonic: String) {
        requestStart(mnemonic)
    }

    /**
     * Builds and starts the node; returns once it is running or has failed.
     *
     * Always a full replace: an existing node is stopped first, so a restart and a first
     * start take the same path. Callers already holding [nodeMutex] call this directly.
     *
     * @return null when the node runs, otherwise the failure it ended with. The caller that
     * restores a channel backup needs it: whether the data it just put in place is kept or
     * rolled back depends on whether the start failed because the chain source is unreachable
     * or because the store itself could not be read.
     */
    private suspend fun startNodeLocked(mnemonic: String): Throwable? {
        try {
            stopNodeLocked(keepState = true, nextStarting = true)
            _state.update { it.copy(starting = true, busy = "Starting node", error = null) }

            val network = prefs.network
            val savedEsplora = prefs.esploraUrl(network)
            val dir = networkDir(network).apply { mkdirs() }
            val logFile = NodeLog.file(appContext).apply { parentFile?.mkdirs() }
            // Keep the on-device log bounded. The node is stopped here, so the file is
            // not being written while it is renamed. A release build writes no log at all,
            // so a file left by an earlier debug build is removed instead of kept.
            if (BuildConfig.DEBUG) {
                runCatching { NodeLog.rotateIfNeeded(logFile) }
            } else {
                runCatching { NodeLog.deleteAll(logFile) }
            }

            val started = startWithFallback(mnemonic, network, dir, logFile)
            val built = started.node
            node = built
            activeEsploraUrl = started.esploraUrl
            prefs.setLastWorkingEsplora(network, started.esploraUrl)

            val status = runCatching { built.status() }.getOrNull()
            val listening = runCatching { built.listeningAddresses() }.getOrNull()
            _state.update {
                it.copy(
                    network = network,
                    esploraUrl = savedEsplora,
                    activeEsploraUrl = started.esploraUrl,
                    running = true,
                    starting = false,
                    busy = null,
                    error = null,
                    nodeId = runCatching { built.nodeId() }.getOrDefault(""),
                    alias = runCatching { built.nodeAlias() }.getOrNull() ?: NODE_ALIAS,
                    listeningAddresses = listening ?: emptyList(),
                    bestHeight = status?.currentBestBlock?.height?.toInt()
                        ?.takeIf { height -> height > 0 } ?: it.bestHeight
                )
            }
            if (started.esploraUrl != savedEsplora) {
                _messages.tryEmit(EsploraFallback.switchedMessage(savedEsplora, started.esploraUrl))
            }
            startEventLoop(built)
            startFiatLoop()
            // Already under [nodeMutex] here: the refreshing variant that takes it would
            // deadlock against this very start.
            refreshLocked(userInitiated = false)
            return null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // The exception text can name the endpoint and the peers it was talking to, so it
            // is only written where a developer is watching. A release build keeps the user's
            // message and nothing else.
            if (BuildConfig.DEBUG) Log.e(TAG, "Node start failed", t)
            runCatching { node?.stop() }
            runCatching { node?.destroy() }
            node = null
            activeEsploraUrl = null
            _state.update {
                it.copy(
                    running = false,
                    starting = false,
                    busy = null,
                    activeEsploraUrl = null,
                    error = describe(t)
                )
            }
            return t
        } finally {
            // The node either runs or carries an error by now; never leave the UI stuck on
            // "Starting".
            if (_state.value.starting) _state.update { it.copy(starting = false) }
        }
    }

    /**
     * Starts the node on the first endpoint and listening setup that works.
     *
     * Two things can refuse a start and both are retried rather than reported as a dead
     * wallet:
     *
     * - **The chain source.** A feerate update that times out (`FeerateEstimationUpdateTimeout`)
     *   fails the whole start even though another public Esplora is a second away, so the
     *   saved endpoint is followed by the one that last worked here and the chain's other
     *   public endpoints. Endpoints the user entered themselves are never swapped for public
     *   ones; that start fails with a message instead of moving their addresses elsewhere.
     * - **The Lightning port.** Port 9735 is what makes inbound Lightning reachable, but it is
     *   also the one thing a phone can refuse: another node may hold it, or the OS may not
     *   allow the bind. The same endpoint is then tried once more without a listening address,
     *   so on-chain sync, sending and already-open channels keep working.
     *
     * A chain-source failure is not cured by dropping the port, so when another endpoint is
     * left to try it is tried next with the port rather than twice without it.
     */
    private fun startWithFallback(
        mnemonic: String,
        network: ChainNetwork,
        dir: File,
        logFile: File
    ): StartedNode {
        val saved = prefs.esploraUrl(network)
        val candidates = EsploraFallback.candidates(
            configured = saved,
            lastWorking = prefs.lastWorkingEsplora(network),
            fallbacks = network.publicEsploraUrls,
            allowFallbacks = EsploraFallback.isPublicEndpoint(saved, network.publicEsploraUrls)
        )
        var lastFailure: Throwable? = null
        for ((candidateIndex, esploraUrl) in candidates.withIndex()) {
            for ((index, address) in lifecycle.startAttempts().withIndex()) {
                try {
                    val built = buildAndStart(mnemonic, network, dir, logFile, address, esploraUrl)
                    if (index > 0) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Node started without a listening address")
                        _messages.tryEmit(
                            "Port 9735 is not available, so the node is running without a public " +
                                "listening address. Sending and receiving over open channels still work."
                        )
                    }
                    return StartedNode(built, esploraUrl)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    lastFailure = t
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Node start on $esploraUrl (${address ?: "no listening address"}) failed", t)
                    }
                    val moreEndpoints = candidateIndex < candidates.lastIndex
                    // A chain source that will not answer is not fixed by dropping the port.
                    if (moreEndpoints && EsploraFallback.isChainSourceFailure(t)) break
                    if (!lifecycle.shouldRetryWithoutListening(address)) break
                }
            }
        }
        val failure = lastFailure ?: IllegalStateException("The node could not be started")
        throw if (EsploraFallback.isChainSourceFailure(failure)) {
            NodeStartFailure(EsploraFallback.exhaustedMessage(candidates, failure))
        } else {
            failure
        }
    }

    /** Builds one node and starts it, or leaves nothing behind when it cannot start. */
    private fun buildAndStart(
        mnemonic: String,
        network: ChainNetwork,
        dir: File,
        logFile: File,
        listeningAddress: String?,
        esploraUrl: String
    ): Node {
        val builder = Builder()
        builder.setStorageDirPath(dir.absolutePath)
        builder.setNetwork(network.ldk)
        builder.setChainSourceEsplora(
            esploraUrl,
            EsploraSyncConfig(BackgroundSyncConfig(30uL, 20uL, 300uL))
        )
        builder.setEntropyBip39Mnemonic(Bip39.normalize(mnemonic), null)
        if (prefs.rapidGossipSync) {
            builder.setGossipSourceRgs(RGS_URL)
        } else {
            builder.setGossipSourceP2p()
        }
        if (listeningAddress != null) {
            // The alias goes in only on the attempt that binds the Lightning port, and it goes
            // in with it. ldk-node's `Builder::build()` runs `may_announce_channel`, which fails
            // with `MissingListeningAddresses` as soon as `node_alias` is set but
            // `listening_addresses` is not; the builder then returns
            // `BuildError::InvalidListeningAddresses` ("Given listening addresses are
            // invalid.") for a config that only wanted a name. Setting the alias on the
            // no-listening fallback therefore failed every fallback outright.
            //
            // A node that announces nothing needs no alias, so the fallback start sets
            // neither: it runs silently, and that is what lets it come up at all.
            runCatching { builder.setListeningAddresses(listOf(listeningAddress)) }
                .onSuccess { builder.setNodeAlias(NODE_ALIAS) }
        }
        // The node's own log file records addresses, peers and channel ids in plain text, and
        // nothing on a phone is needed to read it back. It is therefore written only in a
        // debug build; a release build starts the node with no filesystem logger at all, so
        // no such file is created or appended to.
        if (BuildConfig.DEBUG) {
            runCatching { builder.setFilesystemLogger(logFile.absolutePath, LogLevel.INFO) }
        }

        val built = builder.build()
        try {
            built.start()
        } catch (t: Throwable) {
            // A node that failed to come up still holds its storage files and its sockets.
            runCatching { built.stop() }
            runCatching { built.destroy() }
            throw t
        }
        return built
    }

    private fun startEventLoop(n: Node) {
        eventJob?.cancel()
        eventJob = scope.launch {
            while (isActive) {
                val event = try {
                    n.nextEventAsync()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    if (!isActive) break
                    delay(1_000)
                    continue
                }
                handleEvent(event)
                runCatching { n.eventHandled() }
            }
        }
    }

    private fun handleEvent(event: Event) {
        when (event) {
            is Event.PaymentReceived -> {
                _messages.tryEmit("Payment received")
                scheduleRefresh()
            }
            is Event.PaymentClaimable -> scheduleRefresh()
            is Event.PaymentSuccessful -> {
                // A Lightning payment is only now settled; the send screen called it
                // "sending" until this event arrived.
                pendingLightning.remove(event.paymentId)
                _messages.tryEmit("Payment sent")
                scheduleRefresh()
            }
            is Event.PaymentFailed -> {
                pendingLightning.remove(event.paymentId)
                _messages.tryEmit("Payment failed")
                scheduleRefresh()
            }
            is Event.ChannelReady -> {
                _messages.tryEmit("Channel ready")
                scheduleRefresh()
            }
            is Event.ChannelPending -> scheduleRefresh()
            is Event.ChannelClosed -> {
                _messages.tryEmit("Channel closed")
                scheduleRefresh()
            }
            else -> Unit
        }
    }

    private fun scheduleRefresh() {
        scope.launch {
            delay(1_500)
            refreshInternal(userInitiated = false)
        }
    }

    fun stopNode() {
        lifecycle.onUserStop()
        prefs.nodeStoppedByUser = true
        scope.launch { stopNodeInternal(keepState = true) }
    }

    fun restartNode() {
        val seed = seedOrNull() ?: return
        requestStart(seed)
    }

    /**
     * Queues one start. A second request while a start is running or waiting is dropped: the
     * node coming up once is what the caller wants, not a restart loop.
     */
    private fun requestStart(mnemonic: String) {
        lifecycle.onUserStart()
        prefs.nodeStoppedByUser = false
        if (queuedStarts.incrementAndGet() > 1) {
            queuedStarts.decrementAndGet()
            return
        }
        // Show "Starting" before the coroutine gets the lock, so a second tap or a second
        // resume sees a node on its way instead of a stopped one.
        _state.update { it.copy(starting = true, busy = "Starting node", error = null) }
        scope.launch {
            var counted = true
            try {
                nodeMutex.withLock {
                    startNodeLocked(mnemonic)
                    // Counted out while the lock is still held: whoever stops the node next
                    // and starts it again is not mistaken for a duplicate of this start.
                    queuedStarts.decrementAndGet()
                    counted = false
                }
            } finally {
                if (counted) queuedStarts.decrementAndGet()
            }
        }
    }

    private suspend fun stopNodeInternal(keepState: Boolean) {
        nodeMutex.withLock { stopNodeLocked(keepState) }
    }

    /** Stops the node; callers already holding [nodeMutex] use this. */
    private suspend fun stopNodeLocked(keepState: Boolean, nextStarting: Boolean = false) {
        val loop = eventJob
        eventJob = null
        loop?.cancel()
        // The event loop holds the node across a blocking `nextEventAsync()`. Destroying the
        // node while that call is still in flight is exactly the native use-after-free this
        // guard exists for, so the loop is given a moment to leave before the node goes.
        // The wait is bounded: a loop that will not stop is still cancelled below, and
        // stopping the node is the only way left to get it back.
        withTimeoutOrNull(EVENT_LOOP_STOP_MILLIS) { loop?.join() }
        val current = node
        node = null
        if (current != null) {
            withContext(Dispatchers.IO) {
                runCatching { current.stop() }
                runCatching { current.destroy() }
            }
            runCatching { NodeLog.rotateIfNeeded(NodeLog.file(appContext)) }
        }
        _state.update {
            it.copy(
                running = false,
                starting = nextStarting,
                syncing = false,
                busy = null,
                activeEsploraUrl = if (nextStarting) it.activeEsploraUrl else null,
                onchainTotalSats = if (keepState) it.onchainTotalSats else 0L,
                lightningBalanceSats = if (keepState) it.lightningBalanceSats else 0L
            )
        }
        if (!nextStarting) activeEsploraUrl = null
    }

    fun wipeWallet() {
        lifecycle.onUserStop()
        scope.launch {
            nodeMutex.withLock {
                stopNodeLocked(keepState = false)
                clearNodeData()
                // The LDK log lines name peers, channels and addresses, and the cache keeps
                // copies of exported backups. Neither is the wallet's money, but both are
                // the wallet's business, so a wipe takes them too.
                runCatching { File(appContext.filesDir, "logs").deleteRecursively() }
                runCatching { File(appContext.cacheDir, "backups").deleteRecursively() }
                openedSeed = null
                runCatching { secureStore.clear() }
                runCatching { SecretStore.deleteKey() }
                runCatching { notes.clear() }
                runCatching { prefs.clear() }
                _state.value = WalletState(
                    setupRequired = true,
                    seedPresent = false,
                    bootstrapped = true,
                    seedProtection = protectionStatus(),
                    lockOnOpen = prefs.lockOnOpen,
                    screenProtection = prefs.screenProtection
                )
            }
        }
    }

    // ------------------------------------------------------------- settings

    fun switchNetwork(network: ChainNetwork) {
        if (network == prefs.network) return
        val seed = seedOrNull()
        scope.launch {
            nodeMutex.withLock {
                stopNodeLocked(keepState = false)
                prefs.network = network
                _state.update {
                    it.copy(
                        network = network,
                        esploraUrl = prefs.esploraUrl(network),
                        activeEsploraUrl = null,
                        payments = emptyList(),
                        channels = emptyList(),
                        peers = emptyList(),
                        onchainTotalSats = 0L,
                        onchainSpendableSats = 0L,
                        onchainPendingSats = 0L,
                        lightningBalanceSats = 0L,
                        nodeId = "",
                        bestHeight = null,
                        lastSyncEpochSecs = null
                    )
                }
                // Show what this chain last looked like, if it was used before.
                _state.update { WalletCache.applyTo(it, prefs.cachedWallet(network)) }
            }
            _messages.tryEmit("Switched to ${network.title}")
            if (seed != null) requestStart(seed)
        }
    }

    /**
     * Saves a new Esplora endpoint and, when the node is already running, restarts it right
     * away. The chain source is fixed when the node is built, so a saved URL only takes
     * effect on the next start: waiting for the user to find "Restart node" left people on
     * the slow endpoint they had just replaced.
     */
    fun setEsploraUrl(url: String?): Result<Unit> {
        val network = prefs.network
        val before = prefs.esploraUrl(network)
        val result = prefs.setEsploraUrl(network, url)
        result.onSuccess {
            val after = prefs.esploraUrl(network)
            _state.update { it.copy(esploraUrl = after) }
            val seed = seedOrNull()
            val running = node != null && !_state.value.starting
            if (esploraChangeNeedsRestart(before, after, running, seed != null)) {
                _messages.tryEmit("Esplora endpoint saved. Restarting the node to use it…")
                scope.launch {
                    nodeMutex.withLock {
                        startNodeLocked(seed!!)
                    }
                    if (_state.value.running) {
                        _messages.tryEmit("Esplora endpoint saved and in use")
                    }
                }
            } else if (before != after) {
                _messages.tryEmit("Esplora endpoint saved. It is used the next time the node starts.")
            } else {
                _messages.tryEmit("Esplora endpoint saved.")
            }
        }.onFailure { failure ->
            _messages.tryEmit(failure.message ?: "That endpoint could not be saved.")
        }
        return result
    }

    fun setRapidGossipSync(enabled: Boolean) {
        prefs.rapidGossipSync = enabled
        _state.update { it.copy(rgsEnabled = enabled) }
        _messages.tryEmit("Gossip source changed. Restart the node to apply it.")
    }

    fun setLockOnOpen(enabled: Boolean) {
        prefs.lockOnOpen = enabled
        _state.update { it.copy(lockOnOpen = enabled) }
    }

    fun setScreenProtection(enabled: Boolean) {
        prefs.screenProtection = enabled
        _state.update { it.copy(screenProtection = enabled) }
    }

    // ------------------------------------------------------------- sync

    fun refresh() {
        scope.launch { refreshInternal(userInitiated = true) }
    }

    /**
     * Reads the wallet from the node under [nodeMutex], so a stop, wipe or restart cannot
     * pull the node out from under the sync (a native use-after-free that crashes the app).
     */
    private suspend fun refreshInternal(userInitiated: Boolean) = withContext(Dispatchers.IO) {
        nodeMutex.withLock { refreshLocked(userInitiated) }
    }

    /** The body of a refresh; callers already hold [nodeMutex]. */
    private suspend fun refreshLocked(userInitiated: Boolean) {
        val n = node ?: return
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true) }
        val network = prefs.network
        try {
            val syncError = runCatching { n.syncWallets() }.exceptionOrNull()
            if (syncError != null && userInitiated) {
                _messages.tryEmit("Sync problem: ${describe(syncError)}")
            }

            val balances = runCatching { n.listBalances() }.getOrNull()
            // A read that failed is not an empty history: keep the rows the wallet already
            // shows instead of blanking them until the next successful read.
            val paymentsRead = runCatching { n.listPayments() }
            val channels = runCatching { n.listChannels() }.getOrDefault(emptyList())
            val peers = runCatching { n.listPeers() }.getOrDefault(emptyList())
            val status = runCatching { n.status() }.getOrNull()

            val pendingOnchain = balances?.pendingBalancesFromChannelClosures
                ?.sumOf { describePendingSweep(it) } ?: 0L
            val rows = paymentsRead.getOrNull()?.map { toRow(it) }?.sortedByDescending { it.timestampSecs }

            _state.update { previous ->
                previous.copy(
                    onchainTotalSats = balances?.totalOnchainBalanceSats?.toLong() ?: previous.onchainTotalSats,
                    onchainSpendableSats = balances?.spendableOnchainBalanceSats?.toLong() ?: previous.onchainSpendableSats,
                    onchainPendingSats = pendingOnchain,
                    lightningBalanceSats = balances?.totalLightningBalanceSats?.toLong() ?: previous.lightningBalanceSats,
                    payments = rows ?: previous.payments,
                    channels = channels.map { toChannelRow(it) },
                    peers = peers.map { PeerRow(it.nodeId, it.address, it.isConnected, it.isPersisted) },
                    bestHeight = status?.currentBestBlock?.height?.toInt()
                        ?.takeIf { height -> height > 0 } ?: previous.bestHeight,
                    lastSyncEpochSecs = if (syncError == null) {
                        System.currentTimeMillis() / 1000L
                    } else {
                        // A sync that failed learned nothing new: the timestamp keeps saying
                        // when the wallet was last actually synced instead of claiming now.
                        previous.lastSyncEpochSecs
                    },
                    running = true,
                    error = null
                )
            }
            if (balances != null) {
                // Real numbers from a sync: they are what the next open shows while it waits.
                prefs.saveCachedWallet(network, WalletCache.snapshotOf(_state.value))
            }
        } finally {
            _state.update { it.copy(syncing = false) }
        }
    }

    // ------------------------------------------------------------- receiving

    suspend fun createBolt11Invoice(amountSats: Long?, description: String): Result<InvoiceResult> =
        runCatching {
            withNode { n ->
                val desc = Bolt11InvoiceDescription.Direct(description.trim())
                val invoice = if (amountSats == null || amountSats <= 0L) {
                    n.bolt11Payment().receiveVariableAmount(desc, invoiceExpirySecs)
                } else {
                    n.bolt11Payment().receive(millisats(amountSats), desc, invoiceExpirySecs)
                }
                val hash = invoice.paymentHash()
                notes.put(hash, description)
                InvoiceResult(invoice = invoice.toString(), paymentHash = hash)
            }
        }

    suspend fun createBolt12Offer(amountSats: Long?, description: String): Result<String> =
        runCatching {
            withNode { n ->
                val trimmed = description.trim()
                val offer = if (amountSats == null || amountSats <= 0L) {
                    n.bolt12Payment().receiveVariableAmount(trimmed, null)
                } else {
                    n.bolt12Payment().receive(millisats(amountSats), trimmed, null, null)
                }
                offer.toString()
            }
        }

    suspend fun newOnchainAddress(): Result<String> = runCatching {
        withNode { n -> n.onchainPayment().newAddress() }
    }

    // ------------------------------------------------------------- sending

    /** Reads the amount and description of a pasted/scanned payment request. */
    suspend fun resolveTarget(input: PaymentInput): Result<ResolvedTarget> = runCatching {
        withNode { n ->
            when (input.kind) {
                PaymentInputKind.BOLT11,
                PaymentInputKind.BOLT12_OFFER,
                PaymentInputKind.BOLT12_INVOICE -> resolveLightning(n, input)
                PaymentInputKind.ONCHAIN -> resolveOnchain(n, input)
                PaymentInputKind.PEER -> throw IllegalArgumentException(
                    "That is a peer address. Use Channels to connect to it."
                )
                PaymentInputKind.UNKNOWN -> throw IllegalArgumentException(
                    "Unrecognized payment request"
                )
            }
        }
    }

    /**
     * Amount and description of a Lightning request, after every check that can be made
     * before the payment screen.
     *
     * A BOLT11 invoice names its network, a BOLT12 invoice names its chain by genesis hash,
     * and a BOLT12 offer lists the chains it accepts. All three are checked here: paying an
     * invoice from another chain cannot succeed, and the node would only discover it after
     * signing.
     */
    private fun resolveLightning(n: Node, input: PaymentInput): ResolvedTarget =
        when (input.kind) {
            PaymentInputKind.BOLT11 -> {
                val raw = input.bolt11 ?: input.raw
                val invoice = Bolt11Invoice.fromStr(raw)
                if (invoice.network() != n.config().network) {
                    throw IllegalArgumentException(
                        "This invoice is for ${networkName(invoice.network())}, and this wallet " +
                            "is on ${prefs.network.title}. Switch networks in Settings to pay it."
                    )
                }
                if (invoice.isExpired()) {
                    throw IllegalArgumentException("This invoice has expired")
                }
                val description = when (val d = invoice.invoiceDescription()) {
                    is Bolt11InvoiceDescription.Direct -> d.description
                    is Bolt11InvoiceDescription.Hash -> ""
                }
                val amountSats = invoice.amountMilliSatoshis()?.toLong()?.let { it / 1000L }
                ResolvedTarget(
                    input = input.copy(bolt11 = raw),
                    description = description,
                    amountSats = amountSats,
                    amountEditable = amountSats == null
                )
            }
            PaymentInputKind.BOLT12_OFFER -> {
                val raw = input.bolt12Offer ?: input.raw
                // The `lno1` prefix alone proves nothing: a truncated or mistyped string
                // starts the same way and would only fail after the payment screen.
                if (!PaymentInputParser.looksLikeBolt12Offer(raw)) {
                    throw IllegalArgumentException("That is not a complete BOLT12 offer")
                }
                val offer = Offer.fromStr(raw)
                if (!offer.supportsChain(n.config().network)) {
                    throw IllegalArgumentException(
                        "This offer is not for ${prefs.network.title}, so this wallet cannot pay it"
                    )
                }
                if (offer.isExpired()) {
                    throw IllegalArgumentException("This offer has expired")
                }
                val fixed = when (val amount = offer.amount()) {
                    is OfferAmount.Bitcoin -> amount.amountMsats.toLong() / 1000L
                    is OfferAmount.Currency -> throw IllegalArgumentException(
                        "This offer is priced in ${amount.iso4217Code} and cannot be paid from here"
                    )
                    null -> null
                }
                ResolvedTarget(
                    input = input.copy(bolt12Offer = raw),
                    description = offer.offerDescription() ?: "",
                    amountSats = fixed,
                    amountEditable = fixed == null
                )
            }
            PaymentInputKind.BOLT12_INVOICE -> {
                val raw = input.bolt12Invoice ?: input.raw
                if (!PaymentInputParser.looksLikeBolt12Invoice(raw)) {
                    throw IllegalArgumentException("That is not a complete BOLT12 invoice")
                }
                val invoice = Bolt12Invoice.fromStr(raw)
                // BOLT12 has no network prefix: the chain is named by the genesis hash the
                // invoice is signed over, and Bitcoin has no other way to tell them apart.
                if (!Bolt12Chain.matches(invoice.chain(), prefs.network)) {
                    throw IllegalArgumentException(
                        "This BOLT12 invoice is for another chain, and this wallet is on " +
                            prefs.network.title
                    )
                }
                if (invoice.isExpired()) {
                    throw IllegalArgumentException("This invoice has expired")
                }
                ResolvedTarget(
                    input = input.copy(bolt12Invoice = raw),
                    description = invoice.invoiceDescription() ?: "",
                    amountSats = invoice.amountMsats().toLong() / 1000L,
                    amountEditable = false
                )
            }
            else -> throw IllegalArgumentException("Unrecognized payment request")
        }

    /**
     * An on-chain request, together with the Lightning request the same QR may carry.
     *
     * BIP-21 has room for both halves: `bitcoin:<address>?amount=...&lightning=<invoice or
     * offer>`. The Lightning half settles at once and costs a fraction of an on-chain fee, so
     * it is used when this wallet can actually pay it - a ready channel with enough outbound
     * balance - and when the two halves agree on the amount. The address stays behind as the
     * other route, and it is used unchanged whenever anything about the Lightning half is off
     * (expired, another chain, a different amount, no channel to pay it with).
     */
    private fun resolveOnchain(n: Node, input: PaymentInput): ResolvedTarget {
        val address = input.address ?: throw IllegalArgumentException("No Bitcoin address found")
        // A test-chain address on mainnet (or the reverse) can only fail, and the failure
        // would come after signing. Refuse it while it can still be fixed, naming the chain
        // the wallet is on.
        val network = prefs.network
        if (!PaymentInputParser.matchesNetwork(address, network.isMainnet)) {
            throw IllegalArgumentException(
                "That is not a ${network.title} address. Check the address or switch networks in Settings."
            )
        }

        val onchainSats = input.amountSats?.let { checkedSats(it) }
        val lightning = input.lightning
            ?.let { PaymentInputParser.parseLightningValue(it) }
            ?.let { parsed -> runCatching { resolveLightning(n, parsed) }.getOrNull() }
        // Two halves that disagree on the amount are not the same payment: the on-chain
        // amount is the one the sender wrote on the QR, so that route is used instead.
        val agreed = when {
            lightning == null -> null
            onchainSats != null && lightning.amountSats != null && lightning.amountSats != onchainSats -> null
            else -> lightning
        }
        val routable = agreed != null && hasLightningOutbound(agreed.amountSats ?: onchainSats)
        val amount = onchainSats ?: agreed?.amountSats?.takeIf { routable }
        return ResolvedTarget(
            input = input,
            description = input.label ?: input.message ?: agreed?.description.orEmpty(),
            amountSats = amount,
            amountEditable = amount == null,
            lightning = if (routable) agreed?.input else null
        )
    }

    /**
     * Whether a Lightning payment of this size can leave this node right now.
     *
     * Only a usable channel's outbound balance can pay, and a payment needs a little room
     * above its amount for routing fees. This is a snapshot for the confirmation screen; the
     * node makes the real decision while the payment runs.
     */
    private fun hasLightningOutbound(amountSats: Long?): Boolean {
        val outbound = _state.value.channels
            .filter { it.usable }
            .sumOf { it.localSats }
        if (outbound <= 0L) return false
        val needed = amountSats ?: return outbound > 1_000L
        return outbound - needed >= 500L
    }

    /** A chain's name for a message the user reads. */
    private fun networkName(network: Network): String = when (network) {
        Network.BITCOIN -> "Bitcoin Mainnet"
        Network.TESTNET -> "Testnet3"
        Network.SIGNET -> "Signet"
        Network.REGTEST -> "Regtest"
    }

    /**
     * Pays a resolved request. Returns a channel-agnostic reference: the payment id for
     * Lightning payments and the transaction id for on-chain payments.
     */
    suspend fun pay(
        target: ResolvedTarget,
        amountSats: Long?,
        viaLightning: Boolean = true,
        feeRateSatPerVb: Long? = null
    ): Result<String> = runCatching {
        withNode { n ->
            val input = target.input
            val lightning = target.lightning?.takeIf { viaLightning }
            if (lightning != null && input.kind == PaymentInputKind.ONCHAIN) {
                // The QR named both: this is the Lightning half of the same payment.
                payLightning(n, lightning, amountSats ?: target.amountSats)
            } else {
                when (input.kind) {
                    PaymentInputKind.BOLT11,
                    PaymentInputKind.BOLT12_OFFER,
                    PaymentInputKind.BOLT12_INVOICE ->
                        payLightning(n, input, amountSats ?: target.amountSats)
                    PaymentInputKind.UNKNOWN -> {
                        when (val result = n.unifiedQrPayment().send(input.raw, null)) {
                            is QrPaymentResult.Onchain -> result.txid
                            is QrPaymentResult.Bolt11 -> markLightningPending(result.paymentId)
                            is QrPaymentResult.Bolt12 -> markLightningPending(result.paymentId)
                        }
                    }
                    PaymentInputKind.ONCHAIN -> {
                        val address = input.address
                            ?: throw IllegalArgumentException("No Bitcoin address found")
                        val sats = amountSats ?: target.amountSats
                            ?: throw IllegalArgumentException("Enter an amount")
                        val rate = feeRateSatPerVb
                            ?: throw IllegalStateException(
                                "The fee rate has not been confirmed yet"
                            )
                        sendOnchainInternal(n, address, checkedSats(sats), rate)
                    }
                    PaymentInputKind.PEER -> throw IllegalArgumentException("Use Channels to connect to this peer")
                }
            }
        }
    }

    /** Sends a BOLT11 invoice or BOLT12 offer/invoice and returns its payment id. */
    private fun payLightning(n: Node, input: PaymentInput, amountSats: Long?): String =
        when (input.kind) {
            PaymentInputKind.BOLT11 -> {
                val invoice = Bolt11Invoice.fromStr(input.bolt11 ?: input.raw)
                val id = if (invoice.amountMilliSatoshis() == null) {
                    val sats = amountSats ?: throw IllegalArgumentException("Enter an amount")
                    n.bolt11Payment().sendUsingAmount(invoice, millisats(sats), null)
                } else {
                    n.bolt11Payment().send(invoice, null)
                }
                markLightningPending(id)
            }
            PaymentInputKind.BOLT12_OFFER -> {
                val offer = Offer.fromStr(input.bolt12Offer ?: input.raw)
                val id = if (offer.amount() == null) {
                    val sats = amountSats ?: throw IllegalArgumentException("Enter an amount")
                    n.bolt12Payment().sendUsingAmount(offer, millisats(sats), null, null, null)
                } else {
                    n.bolt12Payment().send(offer, null, null, null)
                }
                markLightningPending(id)
            }
            PaymentInputKind.BOLT12_INVOICE -> when (val result = n.unifiedQrPayment().send(input.raw, null)) {
                is QrPaymentResult.Onchain -> result.txid
                is QrPaymentResult.Bolt11 -> markLightningPending(result.paymentId)
                is QrPaymentResult.Bolt12 -> markLightningPending(result.paymentId)
            }
            else -> throw IllegalArgumentException("This is not a Lightning payment request")
        }

    /**
     * Records that a Lightning payment has left this node but has not settled, and says so.
     *
     * The send screen and the history list must not read "sent" before the node reports the
     * payment settled ([Event.PaymentSuccessful]); between the two it is in flight, and
     * [Event.PaymentFailed] can still take it back.
     */
    private fun markLightningPending(paymentId: String): String {
        if (paymentId.isNotBlank()) {
            pendingLightning.add(paymentId)
            _messages.tryEmit("Payment sending")
        }
        return paymentId
    }

    suspend fun sendOnchain(
        address: String,
        amountSats: Long?,
        sendAll: Boolean,
        feeRateSatPerVb: Long
    ): Result<String> = runCatching {
        withNode { n ->
            if (sendAll) {
                n.onchainPayment().sendAllToAddress(address, true, sendRate(feeRateSatPerVb))
            } else {
                val sats = amountSats ?: throw IllegalArgumentException("Enter an amount")
                sendOnchainInternal(n, address, checkedSats(sats), feeRateSatPerVb)
            }
        }
    }

    private fun sendOnchainInternal(
        n: Node,
        address: String,
        amountSats: Long,
        feeRateSatPerVb: Long
    ): String = n.onchainPayment().sendToAddress(address, amountSats.toULong(), sendRate(feeRateSatPerVb))

    /**
     * The rate a send uses, as the node takes it.
     *
     * `fromSatPerVbUnchecked` trusts its input: 0 or an enormous number goes straight into
     * the transaction. Every rate therefore passes through [FeeEstimates.clamp] first, so a
     * broken estimate can cost at most [FeeEstimates.MAX_SAT_PER_VB] per vB.
     */
    private fun sendRate(satPerVb: Long): FeeRate =
        FeeRate.fromSatPerVbUnchecked(FeeEstimates.clamp(satPerVb).toULong())

    suspend fun estimateFeeRate(priority: FeePriority): Long? =
        withContext(Dispatchers.IO) {
            estimatedSendRate(priority, Market.fetchFeeRates(currentEsploraUrl(prefs.network)))
        }

    // ------------------------------------------------------------- channels

    suspend fun connectPeer(uri: String): Result<Unit> = runCatching {
        withNode { n ->
            val peer = PaymentInputParser.parsePeer(uri)
                ?: throw IllegalArgumentException("Enter a peer as pubkey@host:port")
            n.connect(peer.peerNodeId!!, peer.peerAddress!!, true)
        }
    }

    suspend fun openChannel(uri: String, amountSats: Long, pushMsat: Long): Result<String> = runCatching {
        withNode { n ->
            val peer = PaymentInputParser.parsePeer(uri)
                ?: throw IllegalArgumentException("Enter a peer as pubkey@host:port")
            val capacity = checkedSats(amountSats, "Enter a channel size in sats")
            if (pushMsat < 0L) throw IllegalArgumentException("The push amount cannot be negative")
            if (pushMsat > Fmt.MAX_SATS * 1000L) {
                throw IllegalArgumentException("That push amount is larger than any channel")
            }

            val connected = runCatching {
                n.listPeers().any { it.nodeId.equals(peer.peerNodeId, ignoreCase = true) && it.isConnected }
            }.getOrDefault(false)
            if (!connected) {
                n.connect(peer.peerNodeId!!, peer.peerAddress!!, true)
            }
            n.openChannel(
                peer.peerNodeId!!,
                peer.peerAddress!!,
                capacity.toULong(),
                pushMsat.takeIf { it > 0L }?.toULong(),
                null
            )
        }
    }

    suspend fun closeChannel(channel: ChannelRow, force: Boolean): Result<Unit> = runCatching {
        withNode { n ->
            if (force) {
                n.forceCloseChannel(channel.userChannelId, channel.peer, "Closed from ZeroWallet")
            } else {
                n.closeChannel(channel.userChannelId, channel.peer)
            }
        }
    }

    // ------------------------------------------------------------- helpers

    /**
     * Runs [block] against the running node while no stop, wipe, import or restart can take
     * that node away, on an IO thread because every one of these calls blocks.
     *
     * Node access without this lock is what makes a native use-after-free: a refresh or a
     * payment that already holds the `Node` while `stopNodeLocked()` destroys it touches
     * freed memory and takes the process down. Callers already holding [nodeMutex] must not
     * call this (the mutex is not re-entrant); they use the `*Locked` helpers instead.
     */
    private suspend fun <T> withNode(block: suspend (Node) -> T): T = nodeMutex.withLock {
        val n = node ?: throw IllegalStateException(
            "The node is not running. Wait for it to start and try again."
        )
        withContext(Dispatchers.IO) { block(n) }
    }

    /**
     * A satoshi amount that can actually be paid: positive and no larger than the 21 million
     * BTC that exist. Anything else is refused here rather than silently wrapping when it is
     * turned into millisatoshis for the node.
     */
    private fun checkedSats(sats: Long, message: String = "Enter an amount in sats"): Long {
        if (sats <= 0L) throw IllegalArgumentException(message)
        if (sats > Fmt.MAX_SATS) {
            throw IllegalArgumentException("That is more than the 21 million BTC that exist")
        }
        return sats
    }

    /** Satoshis to millisatoshis. Safe because [checkedSats] has already capped the value. */
    private fun millisats(sats: Long): ULong = (checkedSats(sats) * 1000L).toULong()

    /**
     * The endpoint fee estimates are read from: the one the node is running on, which is the
     * saved one unless a public fallback took over.
     */
    private fun currentEsploraUrl(network: ChainNetwork): String =
        activeEsploraUrl ?: prefs.esploraUrl(network)

    private fun networkDir(network: ChainNetwork): File =
        File(appContext.filesDir, "ldk-node/${network.id}")

    /** Everything ldk-node writes under `files/`: every chain's store and import leftovers. */
    private fun ldkRoot(): File = File(appContext.filesDir, "ldk-node")

    /** Deletes all node data, leaving no store from a previous wallet behind. */
    private fun clearNodeData() {
        runCatching { ldkRoot().deleteRecursively() }
    }

    private fun startFiatLoop() {
        if (fiatJob?.isActive == true) return
        fiatJob = scope.launch {
            while (isActive) {
                val quote = Market.fetchFiat()
                if (quote != null) {
                    _state.update { it.copy(fiat = quote) }
                }
                delay(60_000L)
            }
        }
    }

    private fun toRow(payment: PaymentDetails): PaymentRow {
        val kind = payment.kind
        val lightning = kind !is PaymentKind.Onchain
        val hash = when (kind) {
            is PaymentKind.Bolt11 -> kind.hash
            is PaymentKind.Bolt11Jit -> kind.hash
            is PaymentKind.Bolt12Offer -> kind.hash
            is PaymentKind.Bolt12Refund -> kind.hash
            is PaymentKind.Spontaneous -> kind.hash
            is PaymentKind.Onchain -> null
        }
        val note = when (kind) {
            is PaymentKind.Bolt12Offer -> kind.payerNote
            is PaymentKind.Bolt12Refund -> kind.payerNote
            else -> notes.get(hash)
        }
        val detail = when (kind) {
            is PaymentKind.Onchain -> when (val status = kind.status) {
                is ConfirmationStatus.Confirmed -> "Confirmed at block ${status.height}"
                ConfirmationStatus.Unconfirmed -> "Unconfirmed"
            }
            else -> null
        }
        return PaymentRow(
            id = payment.id,
            lightning = lightning,
            incoming = payment.direction == PaymentDirection.INBOUND,
            status = payment.status.name,
            succeeded = payment.status == PaymentStatus.SUCCEEDED,
            amountSats = (payment.amountMsat?.toLong() ?: 0L) / 1000L,
            feeSats = (payment.feePaidMsat?.toLong() ?: 0L) / 1000L,
            description = note?.takeIf { it.isNotBlank() },
            timestampSecs = payment.latestUpdateTimestamp.toLong(),
            detail = detail
        )
    }

    private fun toChannelRow(channel: ChannelDetails): ChannelRow {
        val state = when {
            channel.isUsable -> "Ready"
            channel.isChannelReady -> "Open, waiting for funds"
            else -> {
                val confirmations = channel.confirmations?.toInt()
                val required = channel.confirmationsRequired?.toInt()
                if (confirmations != null && required != null) {
                    "Pending ($confirmations/$required confirmations)"
                } else {
                    "Pending"
                }
            }
        }
        return ChannelRow(
            userChannelId = channel.userChannelId,
            channelId = channel.channelId,
            peer = channel.counterpartyNodeId,
            capacitySats = channel.channelValueSats.toLong(),
            localSats = channel.outboundCapacityMsat.toLong() / 1000L,
            remoteSats = channel.inboundCapacityMsat.toLong() / 1000L,
            state = state,
            ready = channel.isChannelReady,
            usable = channel.isUsable,
            outbound = channel.isOutbound,
            shortChannelId = channel.shortChannelId?.toString()
        )
    }

    private fun describePendingSweep(balance: org.lightningdevkit.ldknode.PendingSweepBalance): Long =
        when (balance) {
            is org.lightningdevkit.ldknode.PendingSweepBalance.PendingBroadcast ->
                balance.amountSatoshis.toLong()
            is org.lightningdevkit.ldknode.PendingSweepBalance.BroadcastAwaitingConfirmation ->
                balance.amountSatoshis.toLong()
            is org.lightningdevkit.ldknode.PendingSweepBalance.AwaitingThresholdConfirmations ->
                balance.amountSatoshis.toLong()
        }

    private fun describe(t: Throwable): String {
        if (t is NodeStartFailure) return t.message ?: "The node could not be started"
        val message = t.message?.takeIf { it.isNotBlank() }
        val name = t::class.simpleName ?: "Error"
        return if (message != null) "$name: $message" else name
    }

    companion object {
        private const val TAG = "PureWalletNode"
        private const val NODE_ALIAS = "purewallet"
        private const val RGS_URL = "https://rapidsync.lightningdevkit.org/snapshot/"
        private val invoiceExpirySecs: UInt = 3600u

        /** How long a stopping node waits for its event loop to leave the native call. */
        private const val EVENT_LOOP_STOP_MILLIS = 2_000L
    }
}

/**
 * A saved endpoint only reaches the node when it is built, so a change to a different
 * address on a node that is up and has a seed to start from means a restart is due.
 * A node that is stopped, or a device without a recovery phrase yet, has nothing to restart.
 */
internal fun esploraChangeNeedsRestart(
    before: String,
    after: String,
    nodeRunning: Boolean,
    seedAvailable: Boolean
): Boolean = before != after && nodeRunning && seedAvailable

/**
 * The rate the send screen shows for [priority], or null when the chain source did not
 * answer.
 *
 * A failed estimate returns null rather than a hardcoded rate: the send screen disables the
 * send button while there is no rate, so a phone that is offline (or an endpoint that is
 * down) holds the send instead of quietly paying a made-up fee.
 */
internal fun estimatedSendRate(priority: FeePriority, rates: FeeRates?): Long? =
    rates?.let { priority.satPerVb(it) }

/** A node that came up, and the chain source it came up on. */
private class StartedNode(val node: Node, val esploraUrl: String)

/** A start that failed for a reason the wallet can explain in its own words. */
internal class NodeStartFailure(message: String) : Exception(message)
