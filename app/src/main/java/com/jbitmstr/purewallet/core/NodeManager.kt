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
import java.io.File

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
    val rgsEnabled: Boolean = false,
    val appLockEnabled: Boolean = true,
    val screenProtection: Boolean = true,
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
    private var starting = false
    private var bootstrapped = false

    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ---------------------------------------------------------------- lifecycle

    fun bootstrap() {
        synchronized(this) {
            if (bootstrapped) return
            bootstrapped = true
        }
        val network = prefs.network
        val seed = secureStore.loadSeed()
        if (seed == null && secureStore.hasSeed()) {
            // A wallet exists but the Keystore key could not decrypt it. Never overwrite it:
            // the only safe way forward is restoring the recovery phrase.
            _state.update {
                it.copy(
                    network = network,
                    esploraUrl = prefs.esploraUrl(network),
                    appLockEnabled = prefs.appLockEnabled,
                    screenProtection = prefs.screenProtection,
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
                appLockEnabled = prefs.appLockEnabled,
                screenProtection = prefs.screenProtection,
                seedPresent = seed != null,
                backedUp = prefs.seedBackedUp,
                setupRequired = seed == null
            )
        }
        if (seed != null) {
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
        _state.update { it.copy(seedPresent = true, backedUp = true, setupRequired = false) }
        startNode(normalized)
        return Result.success(Unit)
    }

    fun revealSeed(): String? = secureStore.loadSeed()

    fun confirmSeedBackedUp() {
        prefs.seedBackedUp = true
        _state.update { it.copy(backedUp = true) }
    }

    private fun startNode(mnemonic: String) {
        if (starting) return
        starting = true
        scope.launch {
            _state.update { it.copy(starting = true, busy = "Starting node", error = null) }
            try {
                stopNodeInternal(keepState = true)
                val network = prefs.network
                val dir = networkDir(network).apply { mkdirs() }
                val logFile = NodeLog.file(appContext).apply { parentFile?.mkdirs() }
                // Keep the on-device log bounded. The node is stopped here, so the file is
                // not being written while it is renamed.
                runCatching { NodeLog.rotateIfNeeded(logFile) }

                val builder = Builder()
                builder.setStorageDirPath(dir.absolutePath)
                builder.setNetwork(network.ldk)
                builder.setChainSourceEsplora(
                    prefs.esploraUrl(network),
                    EsploraSyncConfig(BackgroundSyncConfig(30uL, 20uL, 300uL))
                )
                builder.setEntropyBip39Mnemonic(Bip39.normalize(mnemonic), null)
                builder.setNodeAlias(NODE_ALIAS)
                if (prefs.rapidGossipSync) {
                    builder.setGossipSourceRgs(RGS_URL)
                } else {
                    builder.setGossipSourceP2p()
                }
                runCatching { builder.setListeningAddresses(listOf(DEFAULT_LISTENING_ADDRESS)) }
                runCatching { builder.setFilesystemLogger(logFile.absolutePath, LogLevel.INFO) }

                val built = builder.build()
                built.start()
                node = built

                val status = runCatching { built.status() }.getOrNull()
                val listening = runCatching { built.listeningAddresses() }.getOrNull()
                _state.update {
                    it.copy(
                        network = network,
                        esploraUrl = prefs.esploraUrl(network),
                        running = true,
                        starting = false,
                        busy = null,
                        error = null,
                        nodeId = runCatching { built.nodeId() }.getOrDefault(""),
                        alias = runCatching { built.nodeAlias() }.getOrNull() ?: NODE_ALIAS,
                        listeningAddresses = listening ?: emptyList(),
                        bestHeight = status?.currentBestBlock?.height?.toInt()
                    )
                }
                startEventLoop(built)
                startFiatLoop()
                refreshInternal(userInitiated = false)
            } catch (t: Throwable) {
                Log.e(TAG, "Node start failed", t)
                runCatching { node?.stop() }
                runCatching { node?.destroy() }
                node = null
                _state.update {
                    it.copy(
                        running = false,
                        starting = false,
                        busy = null,
                        error = describe(t)
                    )
                }
            } finally {
                starting = false
            }
        }
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
        scope.launch { stopNodeInternal(keepState = true) }
    }

    fun restartNode() {
        val seed = secureStore.loadSeed() ?: return
        scope.launch {
            stopNodeInternal(keepState = true)
            startNode(seed)
        }
    }

    private suspend fun stopNodeInternal(keepState: Boolean) {
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
                starting = false,
                syncing = false,
                busy = null,
                onchainTotalSats = if (keepState) it.onchainTotalSats else 0L,
                lightningBalanceSats = if (keepState) it.lightningBalanceSats else 0L
            )
        }
    }

    fun wipeWallet() {
        scope.launch {
            stopNodeInternal(keepState = false)
            ChainNetwork.values().forEach { network ->
                runCatching { networkDir(network).deleteRecursively() }
            }
            runCatching { secureStore.clear() }
            runCatching { notes.clear() }
            runCatching { prefs.clear() }
            _state.value = WalletState(setupRequired = true, seedPresent = false)
        }
    }

    // ------------------------------------------------------------- settings

    fun switchNetwork(network: ChainNetwork) {
        if (network == prefs.network) return
        val seed = secureStore.loadSeed()
        scope.launch {
            stopNodeInternal(keepState = false)
            prefs.network = network
            _state.update {
                it.copy(
                    network = network,
                    esploraUrl = prefs.esploraUrl(network),
                    payments = emptyList(),
                    channels = emptyList(),
                    peers = emptyList(),
                    nodeId = "",
                    bestHeight = null,
                    lastSyncEpochSecs = null
                )
            }
            _messages.tryEmit("Switched to ${network.title}")
            if (seed != null) startNode(seed)
        }
    }

    fun setEsploraUrl(url: String?): Result<Unit> {
        val result = prefs.setEsploraUrl(prefs.network, url)
        result.onSuccess {
            _state.update { it.copy(esploraUrl = prefs.esploraUrl(prefs.network)) }
            _messages.tryEmit("Esplora endpoint saved. Restart the node to use it.")
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

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.appLockEnabled = enabled
        _state.update { it.copy(appLockEnabled = enabled) }
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
        try {
            val syncError = runCatching { n.syncWallets() }.exceptionOrNull()
            if (syncError != null && userInitiated) {
                _messages.tryEmit("Sync problem: ${describe(syncError)}")
            }

            val balances = runCatching { n.listBalances() }.getOrNull()
            val payments = runCatching { n.listPayments() }.getOrDefault(emptyList())
            val channels = runCatching { n.listChannels() }.getOrDefault(emptyList())
            val peers = runCatching { n.listPeers() }.getOrDefault(emptyList())
            val status = runCatching { n.status() }.getOrNull()

            val pendingOnchain = balances?.pendingBalancesFromChannelClosures
                ?.sumOf { describePendingSweep(it) } ?: 0L

            _state.update { previous ->
                previous.copy(
                    onchainTotalSats = balances?.totalOnchainBalanceSats?.toLong() ?: previous.onchainTotalSats,
                    onchainSpendableSats = balances?.spendableOnchainBalanceSats?.toLong() ?: previous.onchainSpendableSats,
                    onchainPendingSats = pendingOnchain,
                    lightningBalanceSats = balances?.totalLightningBalanceSats?.toLong() ?: previous.lightningBalanceSats,
                    payments = payments.map { toRow(it) }.sortedByDescending { it.timestampSecs },
                    channels = channels.map { toChannelRow(it) },
                    peers = peers.map { PeerRow(it.nodeId, it.address, it.isConnected, it.isPersisted) },
                    bestHeight = status?.currentBestBlock?.height?.toInt() ?: previous.bestHeight,
                    lastSyncEpochSecs = System.currentTimeMillis() / 1000L,
                    running = true,
                    error = null
                )
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
            val rates = Market.fetchFeeRates(prefs.esploraUrl(prefs.network))
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
        val rates = Market.fetchFeeRates(prefs.esploraUrl(prefs.network))
        val feeRate = FeeRate.fromSatPerVbUnchecked(priority.satPerVb(rates).toULong())
        return n.onchainPayment().sendToAddress(address, amountSats.toULong(), feeRate)
    }

    suspend fun estimateFeeRate(priority: FeePriority): Long =
        withContext(Dispatchers.IO) {
            priority.satPerVb(Market.fetchFeeRates(prefs.esploraUrl(prefs.network)))
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
        val message = t.message?.takeIf { it.isNotBlank() }
        val name = t::class.simpleName ?: "Error"
        return if (message != null) "$name: $message" else name
    }

    companion object {
        private const val TAG = "PureWalletNode"
        private const val NODE_ALIAS = "purewallet"
        private const val DEFAULT_LISTENING_ADDRESS = "0.0.0.0:9735"
        private const val RGS_URL = "https://rapidsync.lightningdevkit.org/snapshot/"
        private val invoiceExpirySecs: UInt = 3600u
    }
}
