package com.corejbh.zerowallet.core

/** The Lightning port this wallet tries to make reachable for inbound connections. */
internal const val DEFAULT_LISTENING_ADDRESS = "0.0.0.0:9735"

/** What the wallet should do when it comes back to the screen. */
internal enum class ForegroundAction {
    /** Nothing: the node is on its way up, or there is no wallet to start. */
    NONE,

    /** A node is already running, so only a quiet sync is due. */
    REFRESH,

    /** Nothing is running and nothing was stopped by the user: bring the node back. */
    START
}

/**
 * Start and stop decisions for the on-device node, kept away from Android and LDK so the
 * rules behind a node that sat on "Stopped" can be tested on their own.
 *
 * Three things are decided here:
 *
 * - **Coming back.** The wallet has no foreground service, so Android may freeze or kill the
 *   process while the app is away. On return, a node that is still running gets a quiet sync
 *   and a dead one is brought back — unless the user pressed "Stop node", in which case
 *   staying down is their choice and not a failure.
 * - **Listening.** A start tries the public Lightning port first. If that port cannot be
 *   bound (another node holds it, or the phone blocks it) the same start is tried once more
 *   without a listening address, so on-chain sync, sending and already-open channels keep
 *   working instead of leaving the wallet on "Stopped".
 * - **Staying stopped.** The manual stop is remembered on disk, so a process the OS killed
 *   does not quietly undo it.
 *
 * An attempt is the whole way the node is configured, not only its socket: the node alias is
 * set together with the listening address and omitted with it. ldk-node's `Builder::build()`
 * rejects any config that carries a `node_alias` without `listening_addresses` (its
 * `may_announce_channel` check turns into `BuildError::InvalidListeningAddresses`), so a
 * fallback attempt that kept the alias could never build — which is exactly how v1.1.6 lost
 * the wallet whenever port 9735 was taken. A node that announces nothing needs no name.
 */
internal class NodeLifecycle(private val listeningAddress: String? = DEFAULT_LISTENING_ADDRESS) {

    /** True once the user pressed "Stop node": only an explicit start brings it back. */
    var manuallyStopped: Boolean = false
        private set

    /** A wallet was created, restored, or started: automatic starts are allowed again. */
    fun onUserStart() {
        manuallyStopped = false
    }

    /** "Stop node" was pressed, or the wallet was wiped. */
    fun onUserStop() {
        manuallyStopped = true
    }

    /** Restores a stop the user asked for before the process was killed. */
    fun restoreManualStop(stopped: Boolean) {
        manuallyStopped = stopped
    }

    /**
     * What coming back to the screen means: a quiet sync for a node that survived, a start
     * for one that did not, and nothing while a start is already on its way or when the user
     * stopped the node themselves.
     */
    fun foregroundAction(
        seedPresent: Boolean,
        running: Boolean,
        starting: Boolean
    ): ForegroundAction = when {
        running -> ForegroundAction.REFRESH
        shouldStartOnForeground(seedPresent, running, starting) -> ForegroundAction.START
        else -> ForegroundAction.NONE
    }

    /**
     * Whether returning to the foreground should start the node.
     *
     * Left alone when there is no recovery phrase yet, when the node is already up or on its
     * way, and when the user stopped it themselves.
     */
    fun shouldStartOnForeground(
        seedPresent: Boolean,
        running: Boolean,
        starting: Boolean
    ): Boolean = seedPresent && !running && !starting && !manuallyStopped

    /**
     * Listening addresses for one start, in order. The second entry is `null`, meaning the
     * node runs without a public listening address — and therefore without a node alias.
     */
    fun startAttempts(): List<String?> =
        if (listeningAddress.isNullOrBlank()) {
            listOf(null)
        } else {
            listOf(listeningAddress, null)
        }

    /** True when a failure on [attemptedAddress] leaves the no-listening fallback to try. */
    fun shouldRetryWithoutListening(attemptedAddress: String?): Boolean =
        !attemptedAddress.isNullOrBlank()
}
