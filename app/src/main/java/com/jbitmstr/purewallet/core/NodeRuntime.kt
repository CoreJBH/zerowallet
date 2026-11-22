package com.jbitmstr.purewallet.core

import com.jbitmstr.purewallet.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.BackgroundSyncConfig
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.EsploraSyncConfig
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.LogLevel
import org.lightningdevkit.ldknode.Node
import java.io.File

/**
 * The wallet layer's node half: one ldk-node on this phone, built, started, watched and
 * stopped - and the sync that turns it into [WalletState].
 *
 * There is no backend, no LSP and no custodial service in this path: the node talks to the
 * Bitcoin network through Esplora and to Lightning peers directly over TCP. There is no
 * foreground service either, so the node lives exactly as long as this process does, and
 * coming back to the screen is what brings it back.
 *
 * Every start and stop takes [WalletCore.nodeMutex]; nothing here reads the node outside it.
 */
internal class NodeRuntime(
    private val core: WalletCore,
    private val market: MarketLayer
) {

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
        // The market loop stopped when the app left the screen; starting it again is what
        // makes the balance card's fiat figure current rather than an hour old.
        core.onScreen = true
        market.startLoop()
        val snapshot = core.snapshot
        when (core.lifecycle.foregroundAction(snapshot.seedPresent, snapshot.running, snapshot.starting)) {
            ForegroundAction.REFRESH -> core.scope.launch { refreshInternal(userInitiated = false) }
            ForegroundAction.START -> core.scope.launch {
                // The phrase is read from the Keystore again here - this function runs on the
                // lifecycle callback's thread, which is the main one, and a Keystore read
                // there is a main-thread stall right when the user is waiting for their
                // wallet, so it is done on the IO dispatcher like the rest of the node work.
                val seed = withContext(Dispatchers.IO) { core.seedOrNull() } ?: return@launch
                requestStart(seed)
            }
            ForegroundAction.NONE -> Unit
        }
    }

    /**
     * Called when the app leaves the screen (ON_STOP).
     *
     * No copy of the phrase is kept anywhere, so there is nothing to drop: every later read
     * goes back to the Keystore, and a key bound to the device lock asks for a fresh
     * authentication there, so backgrounding the app is never a free pass to the words. The
     * node keeps running: hiding the screen is not stopping the Lightning node.
     */
    /** Starts the price loop if the wallet is on screen; the wallet layer calls it at start. */
    fun startMarketLoop() = market.startLoop()

    fun onAppBackground() {
        // Nothing about the price changes while the screen is away, and a request every 60
        // seconds from a wallet that is not on screen costs battery and tells the price
        // endpoints the user's address for no reason at all. The loop starts again with the
        // first frame after the app comes back (onAppForeground).
        core.onScreen = false
        market.stopLoop()
    }

    // ------------------------------------------------------------------ start

    fun startNode(mnemonic: String, requestedAt: Long = core.walletEpoch.get()) {
        requestStart(mnemonic, requestedAt)
    }

    /**
     * Queues one start. A second request while a start is running or waiting is dropped: the
     * node coming up once is what the caller wants, not a restart loop.
     *
     * [requestedAt] is the wallet epoch this start was asked under. A start that waited for
     * the lock while the phrase was replaced (a wipe, or a restore) is dropped instead of
     * building a node on a phrase that is no longer the wallet's.
     */
    fun requestStart(mnemonic: String, requestedAt: Long = core.walletEpoch.get()) {
        core.lifecycle.onUserStart()
        core.prefs.nodeStoppedByUser = false
        if (core.queuedStarts.incrementAndGet() > 1) {
            core.queuedStarts.decrementAndGet()
            return
        }
        // Show "Starting" before the coroutine gets the lock, so a second tap or a second
        // resume sees a node on its way instead of a stopped one.
        core.update { it.copy(starting = true, busy = "Starting node", error = null) }
        core.scope.launch {
            var counted = true
            try {
                core.nodeMutex.withLock {
                    if (requestedAt != core.walletEpoch.get()) {
                        // The wallet was replaced while this start waited. The node that is
                        // here now is the new wallet's business, so this start ends quietly
                        // instead of building a node the wallet would have to throw away.
                        core.update { it.copy(starting = false, busy = null) }
                        return@withLock
                    }
                    startNodeLocked(mnemonic)
                    // Counted out while the lock is still held: whoever stops the node next
                    // and starts it again is not mistaken for a duplicate of this start.
                    core.queuedStarts.decrementAndGet()
                    counted = false
                }
            } finally {
                if (counted) core.queuedStarts.decrementAndGet()
            }
        }
    }

    fun stopNode() {
        core.lifecycle.onUserStop()
        core.prefs.nodeStoppedByUser = true
        core.scope.launch { stopNodeInternal(keepState = true) }
    }

    fun restartNode() {
        core.scope.launch {
            // The phrase may have to come back out of the Keystore, which is a blocking call:
            // this is reached from a button on the main thread.
            val seed = withContext(Dispatchers.IO) { core.seedOrNull() } ?: return@launch
            requestStart(seed)
        }
    }

    /**
     * Builds and starts the node; returns once it is running or has failed.
     *
     * Always a full replace: an existing node is stopped first, so a restart and a first
     * start take the same path. Callers already holding [WalletCore.nodeMutex] call this
     * directly.
     *
     * @return null when the node runs, otherwise the failure it ended with. The caller that
     * restores a channel backup needs it: whether the data it just put in place is kept or
     * rolled back depends on whether the start failed because the chain source is unreachable
     * or because the store itself could not be read.
     */
    suspend fun startNodeLocked(mnemonic: String): Throwable? {
        try {
            stopNodeLocked(keepState = true, nextStarting = true)
            core.update { it.copy(starting = true, busy = "Starting node", error = null) }

            val network = core.prefs.network
            val savedEsplora = core.prefs.esploraUrl(network)
            // Before anything is built: a store left one rename away by an interrupted import
            // is put back, and the fragments of an import that never reached the swap are
            // cleared. Creating an empty directory here would hide the real store from the
            // node, which would then start as a wallet with no channels.
            recoverNodeDirs(network)
            val dir = core.networkDir(network).apply { mkdirs() }
            val logFile = NodeLog.file(core.appContext).apply { parentFile?.mkdirs() }
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
            core.node = built
            core.activeEsploraUrl = started.esploraUrl
            core.prefs.setLastWorkingEsplora(network, started.esploraUrl)

            val status = runCatching { built.status() }.getOrNull()
            val listening = runCatching { built.listeningAddresses() }.getOrNull()
            // Kept for a channel backup exported while this node is stopped (see
            // [BackupLayer.backupIdentity]).
            core.prefs.recordNodeIdentity(
                network,
                runCatching { built.nodeId() }.getOrDefault(""),
                -1
            )
            core.update {
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
                core.say(EsploraFallback.switchedMessage(savedEsplora, started.esploraUrl))
            }
            startEventLoop(built)
            market.startLoop()
            // Already under the node lock here: the refreshing variant that takes it would
            // deadlock against this very start.
            refreshLocked(userInitiated = false)
            return null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // The exception text can name the endpoint and the peers it was talking to, so it
            // is only written where a developer is watching. A release build keeps the user's
            // message and nothing else.
            WalletCore.warn(TAG, "Node start failed", t)
            runCatching { core.node?.stop() }
            runCatching { core.node?.destroy() }
            core.node = null
            core.activeEsploraUrl = null
            core.update {
                it.copy(
                    running = false,
                    starting = false,
                    busy = null,
                    activeEsploraUrl = null,
                    error = core.describe(t)
                )
            }
            return t
        } finally {
            // The node either runs or carries an error by now; never leave the UI stuck on
            // "Starting".
            if (core.snapshot.starting) core.update { it.copy(starting = false) }
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
        val saved = core.prefs.esploraUrl(network)
        val candidates = EsploraFallback.candidates(
            configured = saved,
            lastWorking = core.prefs.lastWorkingEsplora(network),
            fallbacks = network.publicEsploraUrls,
            allowFallbacks = EsploraFallback.isPublicEndpoint(saved, network.publicEsploraUrls)
        )
        var lastFailure: Throwable? = null
        for ((candidateIndex, esploraUrl) in candidates.withIndex()) {
            for ((index, address) in core.lifecycle.startAttempts().withIndex()) {
                try {
                    val built = buildAndStart(mnemonic, network, dir, logFile, address, esploraUrl)
                    if (index > 0) {
                        WalletCore.warn(TAG, "Node started without a listening address")
                        core.say(
                            "Port 9735 is not available, so the node is running without a public " +
                                "listening address. Sending and receiving over open channels still work."
                        )
                    }
                    return StartedNode(built, esploraUrl)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    lastFailure = t
                    WalletCore.warn(
                        TAG,
                        "Node start on $esploraUrl (${address ?: "no listening address"}) failed",
                        t
                    )
                    val moreEndpoints = candidateIndex < candidates.lastIndex
                    // A chain source that will not answer is not fixed by dropping the port.
                    if (moreEndpoints && EsploraFallback.isChainSourceFailure(t)) break
                    if (!core.lifecycle.shouldRetryWithoutListening(address)) break
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
        // The snapshot server belongs to the chain: the mainnet URL on signet or testnet
        // would hand this node a graph of another network. [GossipSource] picks the chain's
        // own server or none at all, and the option cannot override that.
        val rgsUrl = GossipSource.rgsUrlFor(network, core.prefs.rapidGossipSync)
        if (rgsUrl != null) {
            builder.setGossipSourceRgs(rgsUrl)
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

    // ------------------------------------------------------------------- stop

    private suspend fun stopNodeInternal(keepState: Boolean) {
        core.nodeMutex.withLock { stopNodeLocked(keepState) }
    }

    /** Stops the node; callers already holding [WalletCore.nodeMutex] use this. */
    suspend fun stopNodeLocked(keepState: Boolean, nextStarting: Boolean = false) {
        val loop = core.eventJob
        core.eventJob = null
        loop?.cancel()
        // The event loop holds the node across a blocking `nextEventAsync()`. Destroying the
        // node while that call is still in flight is exactly the native use-after-free this
        // guard exists for, so the loop is given a moment to leave before the node goes.
        // The wait is bounded: a loop that will not stop is still cancelled below, and
        // stopping the node is the only way left to get it back.
        withTimeoutOrNull(EVENT_LOOP_STOP_MILLIS) { loop?.join() }
        val current = core.node
        core.node = null
        if (current != null) {
            withContext(Dispatchers.IO) {
                runCatching { current.stop() }
                runCatching { current.destroy() }
            }
            runCatching { NodeLog.rotateIfNeeded(NodeLog.file(core.appContext)) }
        }
        core.update {
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
        if (!nextStarting) core.activeEsploraUrl = null
    }

    // ----------------------------------------------------------------- events

    private fun startEventLoop(n: Node) {
        core.eventJob?.cancel()
        core.eventJob = core.scope.launch {
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
                core.say("Payment received")
                scheduleRefresh()
            }
            is Event.PaymentClaimable -> scheduleRefresh()
            is Event.PaymentSuccessful -> {
                // A Lightning payment is only now settled; the send screen called it
                // "sending" until this event arrived.
                core.pendingLightning.remove(event.paymentId)
                core.say("Payment sent")
                scheduleRefresh()
            }
            is Event.PaymentFailed -> {
                core.pendingLightning.remove(event.paymentId)
                core.say("Payment failed")
                scheduleRefresh()
            }
            is Event.ChannelReady -> {
                core.say("Channel ready")
                scheduleRefresh()
            }
            is Event.ChannelPending -> scheduleRefresh()
            is Event.ChannelClosed -> {
                core.say("Channel closed")
                scheduleRefresh()
            }
            else -> Unit
        }
    }

    /**
     * A refresh owed to a node event, coalesced.
     *
     * One payment raises several events in a row (`PaymentClaimable`, then
     * `PaymentSuccessful`, then the channel update), and each one used to queue its own
     * delayed refresh: several full syncs of which the last made the rest pointless. Each new
     * event replaces the refresh that is still waiting, so a burst settles into one sync.
     */
    private var pendingRefresh: Job? = null

    private fun scheduleRefresh() {
        pendingRefresh?.cancel()
        pendingRefresh = core.scope.launch {
            delay(REFRESH_SETTLE_MILLIS)
            // The wait is over: from here this job is the sync itself, so it stops being the
            // pending one. A later event must not cancel a sync that is already talking to
            // the node; it schedules the next one instead.
            if (pendingRefresh === coroutineContext[Job]) pendingRefresh = null
            refreshInternal(userInitiated = false)
        }
    }

    // ------------------------------------------------------------------- sync

    fun refresh() {
        core.scope.launch { refreshInternal(userInitiated = true) }
    }

    /**
     * Reads the wallet from the node under [WalletCore.nodeMutex], so a stop, wipe or restart
     * cannot pull the node out from under the sync (a native use-after-free that crashes the
     * app).
     */
    private suspend fun refreshInternal(userInitiated: Boolean) = withContext(Dispatchers.IO) {
        core.nodeMutex.withLock { refreshLocked(userInitiated) }
    }

    /** The body of a refresh; callers already hold [WalletCore.nodeMutex]. */
    suspend fun refreshLocked(userInitiated: Boolean) {
        val n = core.node ?: return
        if (core.snapshot.syncing) return
        core.update { it.copy(syncing = true) }
        val network = core.prefs.network
        try {
            val syncError = runCatching { n.syncWallets() }.exceptionOrNull()
            if (syncError != null && userInitiated) {
                core.say("Sync problem: ${core.describe(syncError)}")
            }

            val balances = runCatching { n.listBalances() }.getOrNull()
            // A read that failed is not an empty history: keep the rows the wallet already
            // shows instead of blanking them until the next successful read.
            val paymentsRead = runCatching { n.listPayments() }
            val channels = runCatching { n.listChannels() }.getOrDefault(emptyList())
            val peers = runCatching { n.listPeers() }.getOrDefault(emptyList())
            val status = runCatching { n.status() }.getOrNull()

            val pendingOnchain = balances?.pendingBalancesFromChannelClosures
                ?.sumOf { PaymentLayer.pendingSweepSats(it) } ?: 0L
            val rows = paymentsRead.getOrNull()?.map { PaymentLayer.toRow(it, core.notes) }
                ?.sortedByDescending { it.timestampSecs }

            core.update { previous ->
                previous.copy(
                    onchainTotalSats = balances?.totalOnchainBalanceSats?.toLong() ?: previous.onchainTotalSats,
                    onchainSpendableSats = balances?.spendableOnchainBalanceSats?.toLong() ?: previous.onchainSpendableSats,
                    onchainPendingSats = pendingOnchain,
                    lightningBalanceSats = balances?.totalLightningBalanceSats?.toLong() ?: previous.lightningBalanceSats,
                    payments = rows ?: previous.payments,
                    channels = channels.map { PaymentLayer.toChannelRow(it) },
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
            core.recordIdentityIfChanged(network)
            if (balances != null) {
                // Real numbers from a sync: they are what the next open shows while it waits.
                // A snapshot the Keystore could not seal is not written at all (see
                // Prefs.saveCachedWallet); the previous sealed one stays and keeps its date.
                core.prefs.saveCachedWallet(network, WalletCache.snapshotOf(core.snapshot))
            }
        } finally {
            core.update { it.copy(syncing = false) }
        }
    }

    /** The endpoint fee estimates are read from: the one the node runs on, or the saved one. */
    fun currentEsploraUrl(network: ChainNetwork): String =
        core.currentEsploraUrl(network)

    // --------------------------------------------------------------- settings

    fun switchNetwork(network: ChainNetwork) {
        if (network == core.prefs.network) return
        core.scope.launch {
            val seed = core.nodeMutex.withLock {
                stopNodeLocked(keepState = false)
                core.prefs.network = network
                core.update {
                    it.copy(
                        network = network,
                        esploraUrl = core.prefs.esploraUrl(network),
                        activeEsploraUrl = null,
                        // The saved option is read as "on" only for a chain that has a
                        // snapshot server, so the settings sheet never shows a switch that
                        // claims a source this chain cannot use.
                        rgsEnabled = core.prefs.rapidGossipSync && network.rgsSupported,
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
                core.update { WalletCache.applyTo(it, core.prefs.cachedWallet(network)) }
                // Read under the lock, and so off the caller's (main) thread: this was a
                // synchronous Keystore call on the settings sheet's thread.
                core.seedOrNull()
            }
            core.say("Switched to ${network.title}")
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
        val network = core.prefs.network
        val before = core.prefs.esploraUrl(network)
        // Which wallet this change belongs to. The restart is queued behind the node lock, and
        // a phrase that is replaced while it waits (a restore, a wipe) must not be started
        // again from here: the new wallet has its own start.
        val requestedAt = core.walletEpoch.get()
        val result = core.prefs.setEsploraUrl(network, url)
        result.onSuccess {
            val after = core.prefs.esploraUrl(network)
            core.update { it.copy(esploraUrl = after) }
            // This used to read the Keystore on the caller's thread - the settings sheet's,
            // which is the main one - and then take the node lock to do it. Both belong on
            // the IO dispatcher, under the same lock, so a stop, a wipe or a restore cannot
            // pull the node out from under the restart.
            core.scope.launch {
                val message = core.nodeMutex.withLock {
                    val seed = core.seedOrNull()
                    val replacing = requestedAt != core.walletEpoch.get()
                    val running = core.node != null && !core.snapshot.starting
                    when {
                        replacing -> {
                            // The wallet was replaced while this waited. Its store and its
                            // phrase are the new wallet's, which is started elsewhere.
                            "Esplora endpoint saved. It is used the next time the node starts."
                        }
                        esploraChangeNeedsRestart(before, after, running, seed != null) -> {
                            core.say("Esplora endpoint saved. Restarting the node to use it…")
                            startNodeLocked(seed!!)
                            if (core.snapshot.running) {
                                "Esplora endpoint saved and in use"
                            } else {
                                "Esplora endpoint saved. The node could not restart on it yet; " +
                                    "it is used when the node starts."
                            }
                        }
                        before != after ->
                            "Esplora endpoint saved. It is used the next time the node starts."
                        else -> "Esplora endpoint saved."
                    }
                }
                core.say(message)
            }
        }.onFailure { failure ->
            core.say(failure.message ?: "That endpoint could not be saved.")
        }
        return result
    }

    fun setRapidGossipSync(enabled: Boolean) {
        val network = core.snapshot.network
        // A chain with no snapshot server of its own is never pointed at another chain's.
        // The option stays a saved preference for the chains that have one.
        if (enabled && !network.rgsSupported) {
            core.update { it.copy(rgsEnabled = false) }
            core.say(
                "${network.title} has no Rapid Gossip Sync server, so the node keeps " +
                    "learning the network from peers."
            )
            return
        }
        core.prefs.rapidGossipSync = enabled
        core.update { it.copy(rgsEnabled = enabled) }
        core.say("Gossip source changed. Restart the node to apply it.")
    }

    fun setLockOnOpen(enabled: Boolean) {
        core.prefs.lockOnOpen = enabled
        core.update { it.copy(lockOnOpen = enabled) }
    }

    fun setScreenProtection(enabled: Boolean) {
        core.prefs.screenProtection = enabled
        core.update { it.copy(screenProtection = enabled) }
    }

    // ------------------------------------------------------------ node folders

    /**
     * Puts a store back when a channel-backup import was interrupted, and clears what an
     * import that never reached the swap left behind.
     *
     * The import's own rollback covers a failure it lives through; a process killed between
     * the two renames does not. In that state this chain's directory is missing while its
     * whole store sits in `previous-<chain>-<stamp>`, and starting the node would quietly
     * make an empty one instead - a wallet that looks wiped with its funds still on disk. The
     * newest displaced store is renamed back, and every `incoming-` fragment (a backup that
     * was being written when the process died) is deleted: it was never the wallet.
     */
    fun recoverNodeDirs(network: ChainNetwork) {
        val root = core.ldkRoot()
        val dir = core.networkDir(network)
        val folders = root.list()?.filter { File(root, it).isDirectory }.orEmpty()
        if (!dir.exists()) {
            val previous = NodeDirs.newestPrevious(folders, network.id)?.let { File(root, it) }
            if (previous != null && previous.renameTo(dir)) {
                WalletCore.warn(TAG, "Put ${previous.name} back as ${dir.name} after an interrupted import")
            }
        }
        NodeDirs.incomingDirs(folders).forEach { name ->
            runCatching { File(root, name).deleteRecursively() }
        }
    }

    companion object {
        private const val TAG = "PureWalletNode"

        /**
         * The name this node announces on the Lightning network.
         *
         * It is a label in the gossip graph, not a key: changing it costs nothing that can be
         * lost and is picked up on the next start. It was "purewallet" until the app was
         * renamed, and the old name made the wallet's own node unfindable by the name the
         * app uses everywhere else.
         */
        const val NODE_ALIAS = "zerowallet"

        /** How long a stopping node waits for its event loop to leave the native call. */
        private const val EVENT_LOOP_STOP_MILLIS = 2_000L

        /** How long a burst of node events is given to settle before one refresh runs. */
        private const val REFRESH_SETTLE_MILLIS = 1_500L
    }
}

/** A node that came up, and the chain source it came up on. */
internal class StartedNode(val node: Node, val esploraUrl: String)
