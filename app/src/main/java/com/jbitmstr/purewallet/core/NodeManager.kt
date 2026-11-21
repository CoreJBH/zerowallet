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
    val backedUp: Boolean = false,
    val setupRequired: Boolean = true,
    val unlockError: Boolean = false,
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
    val amountEditable: Boolean
)

enum class FeePriority(val label: String, val detail: String) {
    NORMAL("Normal", "Usually confirms within ~30 minutes"),
    FAST("Fast", "Usually confirms within ~10 minutes");

    fun satPerVb(rates: FeeRates?): Long = when (this) {
        NORMAL -> rates?.halfHourSatPerVb ?: 8L
        FAST -> rates?.fastestSatPerVb ?: 16L
    }
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

    /** The chain source the running node was built with, when it is not the saved one. */
    private var activeEsploraUrl: String? = null

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
                val seed = secureStore.loadSeed() ?: return
                requestStart(seed)
            }
            ForegroundAction.NONE -> Unit
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
        val seed = secureStore.loadSeed()
        if (seed == null && secureStore.hasSeed()) {
            // A wallet exists but the Keystore key could not decrypt it. Never overwrite it:
            // the only safe way forward is restoring the recovery phrase.
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
                    error = "The stored recovery phrase could not be unlocked on this device."
                )
            }
            startFiatLoop()
            return
        }
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
                setupRequired = seed == null
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
    fun createWallet(wordCount: Int = 12): String {
        check(!secureStore.hasSeed()) { "A wallet already exists on this device" }
        val mnemonic = Bip39.randomMnemonic(wordCount)
        secureStore.saveSeed(mnemonic)
        prefs.seedBackedUp = false
        prefs.walletCreatedAtEpochSecs = System.currentTimeMillis() / 1000L
        // A new wallet starts with nothing; an older snapshot must not show through.
        prefs.clearCachedWallet(prefs.network)
        _state.update {
            it.copy(seedPresent = true, backedUp = false, setupRequired = false)
        }
        startNode(mnemonic)
        return mnemonic
    }

    /** Restores an existing 12/24 word wallet. */
    fun restoreWallet(mnemonic: String): Result<Unit> {
        val normalized = Bip39.normalize(mnemonic)
        val unknown = Bip39.firstUnknownWord(normalized)
        if (unknown != null) {
            return Result.failure(IllegalArgumentException("Unknown BIP-39 word: $unknown"))
        }
        if (!Bip39.isValidMnemonic(normalized)) {
            return Result.failure(IllegalArgumentException("That recovery phrase is not valid (word count or checksum)"))
        }
        secureStore.saveSeed(normalized)
        prefs.seedBackedUp = true
        prefs.walletCreatedAtEpochSecs = System.currentTimeMillis() / 1000L
        // The phrase may belong to another wallet than the one this snapshot was taken from.
        prefs.clearCachedWallet(prefs.network)
        _state.update { it.copy(seedPresent = true, backedUp = true, setupRequired = false) }
        startNode(normalized)
        return Result.success(Unit)
    }

    fun revealSeed(): String? = secureStore.loadSeed()

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
                val mnemonic = secureStore.loadSeed()
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
                    val archive = ChannelBackup.buildArchive(manifest, files)
                    val sealed = ChannelBackup.encrypt(mnemonic, archive)
                    ChannelBackupExport(
                        bytes = sealed,
                        fileName = ChannelBackup.suggestedFileName(network.id, manifest.createdAtEpochSecs),
                        manifest = manifest,
                        fileCount = files.size,
                        sizeBytes = sealed.size.toLong()
                    )
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
     * aside rather than deleted and is put back if the node cannot start from the backup.
     */
    suspend fun importChannelBackup(bytes: ByteArray): Result<BackupManifest> = withContext(Dispatchers.IO) {
        runCatching {
            nodeMutex.withLock {
                val mnemonic = secureStore.loadSeed()
                    ?: throw IllegalStateException("Restore your recovery phrase first, then import the backup")
                _state.update { it.copy(busy = "Opening channel backup") }
                val payload = try {
                    ChannelBackup.readArchive(ChannelBackup.decrypt(mnemonic, bytes))
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
                    ChannelBackup.writeFiles(incoming, payload.files)
                    if (dir.exists() && !dir.renameTo(previous)) {
                        throw IllegalStateException("The current node data could not be moved aside")
                    }
                    if (!incoming.renameTo(dir)) {
                        throw IllegalStateException("The backup could not be put in place")
                    }
                } catch (t: Throwable) {
                    incoming.deleteRecursively()
                    if (!dir.exists() && previous.exists()) previous.renameTo(dir)
                    throw t
                }

                startNodeLocked(mnemonic)
                if (!_state.value.running) {
                    // The backup did not come up. Put the store that was here back and restart.
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
     */
    private suspend fun startNodeLocked(mnemonic: String) {
        try {
            stopNodeLocked(keepState = true, nextStarting = true)
            _state.update { it.copy(starting = true, busy = "Starting node", error = null) }

            val network = prefs.network
            val savedEsplora = prefs.esploraUrl(network)
            val dir = networkDir(network).apply { mkdirs() }
            val logFile = NodeLog.file(appContext).apply { parentFile?.mkdirs() }
            // Keep the on-device log bounded. The node is stopped here, so the file is
            // not being written while it is renamed.
            runCatching { NodeLog.rotateIfNeeded(logFile) }

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
            refreshInternal(userInitiated = false)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Node start failed", t)
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
                        Log.w(TAG, "Node started without a listening address")
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
                    Log.w(TAG, "Node start on $esploraUrl (${address ?: "no listening address"}) failed", t)
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
        runCatching { builder.setFilesystemLogger(logFile.absolutePath, LogLevel.INFO) }

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
                _messages.tryEmit("Payment sent")
                scheduleRefresh()
            }
            is Event.PaymentFailed -> {
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
        val seed = secureStore.loadSeed() ?: return
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
        eventJob?.cancel()
        eventJob = null
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
                ChainNetwork.values().forEach { network ->
                    runCatching { networkDir(network).deleteRecursively() }
                }
                runCatching { secureStore.clear() }
                runCatching { notes.clear() }
                runCatching { prefs.clear() }
                _state.value = WalletState(
                    setupRequired = true,
                    seedPresent = false,
                    bootstrapped = true,
                    lockOnOpen = prefs.lockOnOpen,
                    screenProtection = prefs.screenProtection
                )
            }
        }
    }

    // ------------------------------------------------------------- settings

    fun switchNetwork(network: ChainNetwork) {
        if (network == prefs.network) return
        val seed = secureStore.loadSeed()
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
            val seed = secureStore.loadSeed()
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

    private suspend fun refreshInternal(userInitiated: Boolean) {
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
                    lastSyncEpochSecs = System.currentTimeMillis() / 1000L,
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
            val n = requireNode()
            val desc = Bolt11InvoiceDescription.Direct(description.trim())
            val invoice = if (amountSats == null || amountSats <= 0L) {
                n.bolt11Payment().receiveVariableAmount(desc, invoiceExpirySecs)
            } else {
                n.bolt11Payment().receive((amountSats * 1000L).toULong(), desc, invoiceExpirySecs)
            }
            val hash = invoice.paymentHash()
            notes.put(hash, description)
            InvoiceResult(invoice = invoice.toString(), paymentHash = hash)
        }

    suspend fun createBolt12Offer(amountSats: Long?, description: String): Result<String> =
        runCatching {
            val n = requireNode()
            val trimmed = description.trim()
            val offer = if (amountSats == null || amountSats <= 0L) {
                n.bolt12Payment().receiveVariableAmount(trimmed, null)
            } else {
                n.bolt12Payment().receive((amountSats * 1000L).toULong(), trimmed, null, null)
            }
            offer.toString()
        }

    suspend fun newOnchainAddress(): Result<String> = runCatching {
        requireNode().onchainPayment().newAddress()
    }

    // ------------------------------------------------------------- sending

    /** Reads the amount and description of a pasted/scanned payment request. */
    suspend fun resolveTarget(input: PaymentInput): Result<ResolvedTarget> = runCatching {
        val n = requireNode()
        when (input.kind) {
            PaymentInputKind.BOLT11 -> {
                val raw = input.bolt11 ?: input.raw
                val invoice = Bolt11Invoice.fromStr(raw)
                if (invoice.network() != n.config().network) {
                    throw IllegalArgumentException("This invoice belongs to a different network")
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
                val offer = Offer.fromStr(raw)
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
                val invoice = Bolt12Invoice.fromStr(raw)
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
            PaymentInputKind.ONCHAIN -> {
                val address = input.address ?: throw IllegalArgumentException("No Bitcoin address found")
                ResolvedTarget(
                    input = input,
                    description = input.label ?: input.message ?: "",
                    amountSats = input.amountSats,
                    amountEditable = input.amountSats == null
                )
            }
            PaymentInputKind.PEER -> throw IllegalArgumentException(
                "That is a peer address. Use Channels to connect to it."
            )
            PaymentInputKind.UNKNOWN -> throw IllegalArgumentException(
                "Unrecognized payment request"
            )
        }
    }

    /**
     * Pays a resolved request. Returns a channel-agnostic reference: the payment id for
     * Lightning payments and the transaction id for on-chain payments.
     */
    suspend fun pay(
        target: ResolvedTarget,
        amountSats: Long?,
        priority: FeePriority
    ): Result<String> = runCatching {
        val n = requireNode()
        val input = target.input
        when (input.kind) {
            PaymentInputKind.BOLT11 -> {
                val invoice = Bolt11Invoice.fromStr(input.bolt11 ?: input.raw)
                val amount = amountSats ?: target.amountSats
                if (invoice.amountMilliSatoshis() == null) {
                    val sats = amount ?: throw IllegalArgumentException("Enter an amount")
                    n.bolt11Payment().sendUsingAmount(invoice, (sats * 1000L).toULong(), null)
                } else {
                    n.bolt11Payment().send(invoice, null)
                }
            }
            PaymentInputKind.BOLT12_OFFER -> {
                val offer = Offer.fromStr(input.bolt12Offer ?: input.raw)
                val amount = amountSats ?: target.amountSats
                if (offer.amount() == null) {
                    val sats = amount ?: throw IllegalArgumentException("Enter an amount")
                    n.bolt12Payment().sendUsingAmount(offer, (sats * 1000L).toULong(), null, null, null)
                } else {
                    n.bolt12Payment().send(offer, null, null, null)
                }
            }
            PaymentInputKind.BOLT12_INVOICE, PaymentInputKind.UNKNOWN -> {
                when (val result = n.unifiedQrPayment().send(input.raw, null)) {
                    is QrPaymentResult.Onchain -> result.txid
                    is QrPaymentResult.Bolt11 -> result.paymentId
                    is QrPaymentResult.Bolt12 -> result.paymentId
                }
            }
            PaymentInputKind.ONCHAIN -> {
                val address = input.address ?: throw IllegalArgumentException("No Bitcoin address found")
                val sats = amountSats ?: target.amountSats ?: throw IllegalArgumentException("Enter an amount")
                sendOnchainInternal(n, address, sats, priority)
            }
            PaymentInputKind.PEER -> throw IllegalArgumentException("Use Channels to connect to this peer")
        }
    }

    suspend fun sendOnchain(
        address: String,
        amountSats: Long?,
        sendAll: Boolean,
        priority: FeePriority
    ): Result<String> = runCatching {
        val n = requireNode()
        if (sendAll) {
            val rates = Market.fetchFeeRates(currentEsploraUrl(prefs.network))
            val feeRate = FeeRate.fromSatPerVbUnchecked(priority.satPerVb(rates).toULong())
            n.onchainPayment().sendAllToAddress(address, true, feeRate)
        } else {
            val sats = amountSats ?: throw IllegalArgumentException("Enter an amount")
            sendOnchainInternal(n, address, sats, priority)
        }
    }

    private suspend fun sendOnchainInternal(
        n: Node,
        address: String,
        amountSats: Long,
        priority: FeePriority
    ): String {
        val rates = Market.fetchFeeRates(currentEsploraUrl(prefs.network))
        val feeRate = FeeRate.fromSatPerVbUnchecked(priority.satPerVb(rates).toULong())
        return n.onchainPayment().sendToAddress(address, amountSats.toULong(), feeRate)
    }

    suspend fun estimateFeeRate(priority: FeePriority): Long =
        withContext(Dispatchers.IO) {
            priority.satPerVb(Market.fetchFeeRates(currentEsploraUrl(prefs.network)))
        }

    // ------------------------------------------------------------- channels

    suspend fun connectPeer(uri: String): Result<Unit> = runCatching {
        val n = requireNode()
        val peer = PaymentInputParser.parsePeer(uri)
            ?: throw IllegalArgumentException("Enter a peer as pubkey@host:port")
        n.connect(peer.peerNodeId!!, peer.peerAddress!!, true)
    }

    suspend fun openChannel(uri: String, amountSats: Long, pushMsat: Long): Result<String> = runCatching {
        val n = requireNode()
        val peer = PaymentInputParser.parsePeer(uri)
            ?: throw IllegalArgumentException("Enter a peer as pubkey@host:port")
        if (amountSats <= 0L) throw IllegalArgumentException("Enter a channel size in sats")

        val connected = runCatching {
            n.listPeers().any { it.nodeId.equals(peer.peerNodeId, ignoreCase = true) && it.isConnected }
        }.getOrDefault(false)
        if (!connected) {
            n.connect(peer.peerNodeId!!, peer.peerAddress!!, true)
        }
        n.openChannel(
            peer.peerNodeId!!,
            peer.peerAddress!!,
            amountSats.toULong(),
            pushMsat.takeIf { it > 0L }?.toULong(),
            null
        )
    }

    suspend fun closeChannel(channel: ChannelRow, force: Boolean): Result<Unit> = runCatching {
        val n = requireNode()
        if (force) {
            n.forceCloseChannel(channel.userChannelId, channel.peer, "Closed from ZeroWallet")
        } else {
            n.closeChannel(channel.userChannelId, channel.peer)
        }
    }

    // ------------------------------------------------------------- helpers

    fun requireNode(): Node = node ?: throw IllegalStateException(
        "The node is not running. Wait for it to start and try again."
    )

    /**
     * The endpoint fee estimates are read from: the one the node is running on, which is the
     * saved one unless a public fallback took over.
     */
    private fun currentEsploraUrl(network: ChainNetwork): String =
        activeEsploraUrl ?: prefs.esploraUrl(network)

    private fun networkDir(network: ChainNetwork): File =
        File(appContext.filesDir, "ldk-node/${network.id}")

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

/** A node that came up, and the chain source it came up on. */
private class StartedNode(val node: Node, val esploraUrl: String)

/** A start that failed for a reason the wallet can explain in its own words. */
internal class NodeStartFailure(message: String) : Exception(message)
