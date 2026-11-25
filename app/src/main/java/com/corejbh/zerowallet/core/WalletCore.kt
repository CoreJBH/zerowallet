package com.corejbh.zerowallet.core

import android.content.Context
import com.corejbh.zerowallet.BuildConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Node

/**
 * The state and services every wallet layer shares: the one node instance, the lock that
 * guards it, the stored wallet, the settings and the flow the screens render.
 *
 * It is not a screen and it does not decide anything on its own. [NodeRuntime] owns the
 * node's life, [WalletLayer] the phrase and the records around it, [BackupLayer] the
 * encrypted channel backup and [PaymentLayer] every real payment; each of them works through
 * this object so there is exactly one node handle, one state flow and one phrase read in the
 * process. Before 1.2.0 all of it lived in a single 2,196-line `NodeManager`, where a change
 * to a payment could reach the start code and nothing said which fields were safe to touch.
 *
 * **The phrase is never cached here.** [seedOrNull] opens the Keystore copy every time it is
 * called. An earlier build kept the opened phrase in memory for the life of the process so a
 * restart or a backup would not prompt again, which also meant that anything running in the
 * process could read the words once the user had authenticated once. The Keystore's own
 * window decides now, and the pass is never handed out a second time.
 */
internal class WalletCore(context: Context) {

    val appContext: Context = context.applicationContext
    val prefs = Prefs(appContext)
    val secureStore = SecureStore(appContext)
    val notes = PaymentNotes(appContext)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The running node, guarded by [nodeMutex] for every read and write. */
    var node: Node? = null
    var eventJob: Job? = null
    var fiatJob: Job? = null

    /**
     * Whether the wallet is on screen. The price loop only runs while it is, so a read that
     * is started by something other than the first frame (a node that finished starting
     * while the app was away) cannot bring the loop back in the background.
     */
    @Volatile
    var onScreen: Boolean = true

    @Volatile
    var bootstrapped: Boolean = false

    /** The chain source the running node was built with, when it is not the saved one. */
    @Volatile
    var activeEsploraUrl: String? = null

    /**
     * Lightning payments this process has sent and is still waiting to hear settled.
     *
     * A Lightning send that returned without an error is in flight, not settled: the node
     * only knows it went out when the `PaymentSuccessful` event arrives, and `PaymentFailed`
     * when it did not. The set makes that state explicit so a payment is never announced as
     * sent before the network says so. Kept only for the life of the process; the history
     * list is the durable record.
     */
    val pendingLightning: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Serialises every start and stop. Building a node takes seconds, and a restart, a
     * settings change or a return to the foreground can all ask for one in that window; the
     * lock makes them queue instead of racing or being silently dropped.
     */
    val nodeMutex = Mutex()

    /**
     * Which wallet this process is on, bumped every time the stored phrase is replaced.
     *
     * A start that was asked for under one epoch and reaches the node afterwards belongs to a
     * wallet that is gone (wiped, or replaced by a restore), and must not bring a node up on
     * a phrase the wallet no longer holds.
     */
    val walletEpoch = AtomicLong(0L)

    /** Starts that are running or waiting for [nodeMutex]; at most one is worth queueing. */
    val queuedStarts = AtomicInteger(0)

    val lifecycle = NodeLifecycle()

    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** The state as it is right now; for code already holding [nodeMutex]. */
    val snapshot: WalletState get() = _state.value

    fun update(block: (WalletState) -> WalletState) = _state.update(block)

    fun replaceState(next: WalletState) {
        _state.value = next
    }

    /** A message for the screen. Dropped rather than awaited when nobody is listening. */
    fun say(message: String) {
        _messages.tryEmit(message)
    }

    /**
     * What the Keystore says about the stored phrase's key, or null when it cannot be read.
     * Read from the key itself, so a wallet sealed by an older build, by the single-key
     * fallback or before this phone had a screen lock is reported as it actually is.
     */
    fun protectionStatus(): SecureStore.SeedProtectionStatus? =
        runCatching { secureStore.protectionStatus() }.getOrNull()

    /**
     * The recovery phrase, opened from the Keystore for this one call.
     *
     * There is no process-wide copy to hand back: showing the words, restarting the node and
     * packing a backup each read the stored ciphertext, and the Keystore decides whether that
     * needs an authentication. Within its short window it answers at once; outside it the
     * answer is "authenticate", which [WalletLayer.unlockSeed] turns into a prompt and a
     * second attempt.
     *
     * Returns null when the wallet cannot be opened without the user: [readSeed] reports that
     * through [SecureStore.SeedRead.NeedsAuthentication], and the state is set so the wallet
     * shows its lock screen instead of pretending there is no wallet. Every caller already
     * has a "no seed, do nothing" branch.
     */
    fun seedOrNull(): String? = when (val read = secureStore.readSeed()) {
        is SecureStore.SeedRead.Ok -> read.mnemonic
        SecureStore.SeedRead.NeedsAuthentication -> {
            update { it.copy(seedPresent = true, setupRequired = false, seedLocked = true, bootstrapped = true) }
            null
        }
        SecureStore.SeedRead.Invalidated -> {
            reportInvalidatedKey()
            null
        }
        else -> null
    }

    /**
     * The phrase, for a call that has already authenticated the user this very moment.
     *
     * Same read as [seedOrNull]; separate only so a careful caller (a backup export, which
     * prompts first) does not turn a failed read into a "wallet locked" screen it will
     * overwrite a line later.
     */
    fun seedAfterAuthentication(): String? = seedIfAvailable()

    /**
     * The phrase when the Keystore answers without asking the user, otherwise null.
     *
     * For work the user did not ask for - a sync quietly trying another endpoint, a scheduled
     * check - [seedOrNull] would put the wallet on its lock screen when the key is bound to
     * the device lock. That is the right answer to something the user did; it is not the right
     * answer to a background retry, which simply has nothing to do until they are back. A null
     * here says only "not now" and leaves the wallet's state untouched.
     */
    fun seedIfAvailable(): String? = (secureStore.readSeed() as? SecureStore.SeedRead.Ok)?.mnemonic

    /**
     * The Keystore key died with the phone's previous screen lock, and the ciphertext cannot
     * be opened by anything on this device any more. That is not a lost wallet: the phrase
     * still restores it, so say exactly what happened and send the user to the restore flow
     * rather than to a blank setup screen.
     */
    fun reportInvalidatedKey() = reportBrokenWallet(
        "This wallet's key was invalidated when the phone's screen lock was removed or " +
            "reset. Restore the wallet with its recovery phrase."
    )

    /**
     * The stored wallet cannot be opened on this device any more. Never overwrite it: the
     * only safe way forward is restoring the recovery phrase, so the setup screen opens in
     * its restore mode with the reason on it.
     */
    fun reportBrokenWallet(message: String) {
        update {
            it.copy(
                seedLocked = false,
                seedPresent = false,
                setupRequired = true,
                unlockError = true,
                error = message
            )
        }
    }

    /**
     * Runs [block] against the running node while no stop, wipe, import or restart can take
     * that node away, on an IO thread because every one of these calls blocks.
     *
     * Node access without this lock is what makes a native use-after-free: a refresh or a
     * payment that already holds the `Node` while the stop path destroys it touches freed
     * memory and takes the process down. Callers already holding [nodeMutex] must not call
     * this (the mutex is not re-entrant); they take the node directly.
     */
    suspend fun <T> withNode(block: suspend (Node) -> T): T = nodeMutex.withLock {
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
    fun checkedSats(sats: Long, message: String = "Enter an amount in sats"): Long {
        if (sats <= 0L) throw IllegalArgumentException(message)
        if (sats > Fmt.MAX_SATS) {
            throw IllegalArgumentException("That is more than the 21 million BTC that exist")
        }
        return sats
    }

    /** Satoshis to millisatoshis. Safe because [checkedSats] has already capped the value. */
    fun millisats(sats: Long): ULong = (checkedSats(sats) * 1000L).toULong()

    /** This chain's node directory under `files/`. */
    fun networkDir(network: ChainNetwork): File = File(appContext.filesDir, "ldk-node/${network.id}")

    /**
     * The endpoint the running node reads from - the one a public fallback took over, when it
     * did - or the saved one while no node is up.
     */
    fun currentEsploraUrl(network: ChainNetwork): String =
        activeEsploraUrl ?: prefs.esploraUrl(network)

    /** Everything ldk-node writes under `files/`: every chain's store and import leftovers. */
    fun ldkRoot(): File = File(appContext.filesDir, "ldk-node")

    /** Deletes all node data, leaving no store from a previous wallet behind. */
    fun clearNodeData() {
        runCatching { ldkRoot().deleteRecursively() }
    }

    /** True when a node store is still on disk after a delete: a wipe has to say so. */
    fun nodeDataRemains(): Boolean = ldkRoot().exists()

    /**
     * Records what the node reported, so a channel backup exported while it is stopped still
     * names the wallet it came from (see [BackupLayer.backupIdentity]).
     */
    fun recordIdentityIfChanged(network: ChainNetwork) {
        val live = snapshot
        if (live.nodeId != prefs.lastNodeId(network) ||
            live.channels.size != prefs.lastKnownChannelCount(network)
        ) {
            prefs.recordNodeIdentity(network, live.nodeId, live.channels.size)
        }
    }

    /** A failure as the user reads it: a start failure keeps its own message. */
    fun describe(t: Throwable): String {
        if (t is NodeStartFailure) return t.message ?: "The node could not be started"
        val message = t.message?.takeIf { it.isNotBlank() }
        val name = t::class.simpleName ?: "Error"
        return if (message != null) "$name: $message" else name
    }

    companion object {
        /** Debug builds log; a shipped wallet leaves nothing about the node in logcat. */
        fun warn(tag: String, message: String, t: Throwable? = null) {
            if (BuildConfig.DEBUG) {
                if (t == null) android.util.Log.w(tag, message) else android.util.Log.w(tag, message, t)
            }
        }
    }
}
