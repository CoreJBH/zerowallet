package com.jbitmstr.purewallet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.jbitmstr.purewallet.core.AppLockGate
import com.jbitmstr.purewallet.core.BackupManifest
import com.jbitmstr.purewallet.core.BackupReview
import com.jbitmstr.purewallet.core.BackupTransfer
import com.jbitmstr.purewallet.core.ChainNetwork
import com.jbitmstr.purewallet.core.ChannelBackupExport
import com.jbitmstr.purewallet.core.ChannelRow
import com.jbitmstr.purewallet.core.FeePriority
import com.jbitmstr.purewallet.core.InvoiceResult
import com.jbitmstr.purewallet.core.PaymentInput
import com.jbitmstr.purewallet.core.ResolvedTarget
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the wallet's own lock is doing, as the screen needs it.
 *
 * This used to live in `WalletApp` as a handful of `remember`s, which made it a property of
 * the composition rather than of the wallet session: a rotation destroyed the activity and
 * the wallet asked for the device lock again, and nothing tied the state to the process it
 * described. It is a ViewModel value now, so a rotation keeps the answer, and a process death
 * re-derives it - always closed - from the stored wallet, which is the safe direction.
 */
data class LockUiState(
    /** The wallet is open on this screen. */
    val unlocked: Boolean = false,
    /** The open-lock decision has been made for this session; before that it stays closed. */
    val started: Boolean = false,
    /** Why the open-lock prompt did not open the wallet, when it did not. */
    val error: String? = null,
    /** Why the wallet's own lock screen could not open the phrase. */
    val seedError: String? = null,
    /**
     * A privileged action was asked for on a phone with no screen lock. It did not run; the
     * only way forward is the Android setting that lets the wallet ask again.
     */
    val needsScreenLock: Boolean = false,
    /** Whether this phone can authenticate at all, re-read on every resume. */
    val lockAvailable: Boolean = false,
    /**
     * Bumped every time the app leaves the screen. The sheets that hold a draft watch it and
     * drop what they hold; the counter keeps them composed, so nothing in flight is cancelled
     * by it.
     */
    val backgrounded: Int = 0
)

/**
 * The bridge between the Compose screens and [com.jbitmstr.purewallet.core.NodeManager], and
 * the owner of the wallet's lock state machine.
 *
 * The node itself outlives configuration changes through the Application, so no work is tied
 * to this ViewModel; what it does own is the answer to "is the wallet open, and why not" -
 * which has to survive a rotation and has to fall back to closed when the process is
 * recreated.
 */
class WalletViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val manager = (application as PureWalletApp).nodeManager

    val state = manager.state
    val messages = manager.messages

    private val _lock = MutableStateFlow(
        LockUiState(
            // Only "the decision was already made" is restored. Whether the wallet was open
            // never is: a bundle restored into a new process must not hand someone an opened
            // wallet. With the flag set and `unlocked` false, the gate below asks exactly the
            // question a cold start asks.
            started = savedState.get<Boolean>(KEY_GATE_STARTED) ?: false
        )
    )
    val lock: StateFlow<LockUiState> = _lock.asStateFlow()

    // ------------------------------------------------------------- lifecycle

    fun bootstrap() = manager.bootstrap()

    /** The wallet is back on screen: bring the node back if it was frozen or failed. */
    fun onAppForeground() = manager.onAppForeground()

    /** The app left the screen: the price loop stops, the node keeps running. */
    fun onAppBackground() = manager.onAppBackground()

    /**
     * The activity resumed. The lock availability is re-read here rather than cached: a screen
     * lock can be removed while the app sits in the background, and a prompt that can no
     * longer succeed must not be left armed.
     */
    fun onScreenResumed(lockAvailable: Boolean) {
        _lock.update { it.copy(lockAvailable = lockAvailable) }
        // A sealed export handed to the share sheet in an earlier session has had its chance
        // by now; this is also what keeps a cancelled share from leaving a copy on disk.
        BackupTransfer.purgeStaleExports(getApplication())
    }

    /** Re-reads whether this phone can authenticate, for a prompt that is about to be shown. */
    fun onLockAvailability(available: Boolean) {
        if (_lock.value.lockAvailable != available) {
            _lock.update { it.copy(lockAvailable = available) }
        }
    }

    /**
     * The activity stopped. The draft counter moves, the recovery phrase is gone from the
     * screen, and - when lock-on-open is on - the gate closes so coming back asks.
     */
    fun onScreenStopped(enforcedLock: Boolean) {
        _lock.update {
            it.copy(
                unlocked = AppLockGate.afterLeavingScreen(it.unlocked, enforcedLock),
                backgrounded = it.backgrounded + 1,
                seedError = null
            )
        }
    }

    /**
     * The saved option and the stored wallet are known: close or open the gate for this
     * session, once.
     *
     * A wallet that had lock-on-open on opens behind the lock screen, one that had it off
     * opens straight away. After that the option can be toggled in Settings without throwing
     * the user out or counting as a pass; leaving the screen is what closes it again.
     */
    fun settleOpenLock(openLockEnabled: Boolean) {
        _lock.update {
            if (it.started) {
                it.copy(unlocked = AppLockGate.afterOptionChanged(it.unlocked, openLockEnabled))
            } else {
                savedState[KEY_GATE_STARTED] = true
                it.copy(started = true, unlocked = AppLockGate.afterStart(openLockEnabled))
            }
        }
    }

    /** The device-lock prompt for opening the wallet succeeded. */
    fun onOpenUnlocked() {
        _lock.update { it.copy(unlocked = true, error = null) }
    }

    /** It did not - cancelled, or the phone has no lock to ask with. */
    fun onOpenLockDenied(error: String?) {
        _lock.update { it.copy(error = error) }
    }

    /** The wallet's own lock screen could not open the phrase. */
    fun onSeedUnlockDenied(error: String?) {
        _lock.update { it.copy(seedError = error) }
    }

    /** The wallet's own lock screen opened it; the same prompt satisfies lock-on-open. */
    fun onSeedUnlocked() {
        _lock.update { it.copy(seedError = null, unlocked = true, error = null) }
    }

    /** An action needed a screen lock this phone does not have. */
    fun reportScreenLockMissing() {
        _lock.update { it.copy(needsScreenLock = true, lockAvailable = false) }
    }

    fun dismissScreenLockNotice() {
        _lock.update { it.copy(needsScreenLock = false) }
    }

    // ----------------------------------------------------------------- wallet

    suspend fun createWallet(wordCount: Int = 12): String = manager.createWallet(wordCount)

    /** Called after the device-lock prompt succeeded: opens the wallet and starts its node. */
    fun unlockSeed() = manager.unlockSeed()

    suspend fun restoreWallet(mnemonic: String): Result<Unit> = manager.restoreWallet(mnemonic)

    /** Opens the phrase from the Keystore for this call; nothing about it is kept. */
    fun revealSeed(): String? = manager.revealSeed()

    fun confirmBackup() = manager.confirmSeedBackedUp()

    /**
     * Deletes the wallet and answers when it is actually gone, so the settings sheet closes
     * only after a wipe that succeeded.
     */
    suspend fun wipeWallet(): Result<Unit> = manager.wipeWallet()

    // ----------------------------------------------------------------- backup

    suspend fun exportChannelBackup(): Result<ChannelBackupExport> = manager.exportChannelBackup()

    suspend fun importChannelBackup(file: File): Result<BackupManifest> =
        manager.importChannelBackup(file)

    /** Reads a backup file's manifest without restoring it, for the confirmation. */
    suspend fun inspectChannelBackup(file: File): Result<BackupReview> =
        manager.inspectChannelBackup(file)

    // ------------------------------------------------------------------- node

    fun refresh() = manager.refresh()

    fun stopNode() = manager.stopNode()

    fun restartNode() = manager.restartNode()

    fun switchNetwork(network: ChainNetwork) = manager.switchNetwork(network)

    fun setEsploraUrl(url: String?): Result<Unit> = manager.setEsploraUrl(url)

    fun setRapidGossipSync(enabled: Boolean) = manager.setRapidGossipSync(enabled)

    fun setLockOnOpen(enabled: Boolean) = manager.setLockOnOpen(enabled)

    fun setScreenProtection(enabled: Boolean) = manager.setScreenProtection(enabled)

    // --------------------------------------------------------------- payments

    suspend fun createInvoice(amountSats: Long?, description: String): Result<InvoiceResult> =
        manager.createBolt11Invoice(amountSats, description)

    suspend fun createOffer(amountSats: Long?, description: String): Result<String> =
        manager.createBolt12Offer(amountSats, description)

    suspend fun newAddress(): Result<String> = manager.newOnchainAddress()

    suspend fun resolveTarget(input: PaymentInput): Result<ResolvedTarget> =
        manager.resolveTarget(input)

    suspend fun pay(
        target: ResolvedTarget,
        amountSats: Long?,
        viaLightning: Boolean = true,
        feeRateSatPerVb: Long? = null
    ): Result<String> = manager.pay(target, amountSats, viaLightning, feeRateSatPerVb)

    suspend fun sendOnchain(
        address: String,
        amountSats: Long?,
        sendAll: Boolean,
        feeRateSatPerVb: Long
    ): Result<String> = manager.sendOnchain(address, amountSats, sendAll, feeRateSatPerVb)

    suspend fun connectPeer(uri: String): Result<Unit> = manager.connectPeer(uri)

    suspend fun openChannel(uri: String, amountSats: Long, pushMsat: Long): Result<String> =
        manager.openChannel(uri, amountSats, pushMsat)

    suspend fun closeChannel(channel: ChannelRow, force: Boolean): Result<Unit> =
        manager.closeChannel(channel, force)

    suspend fun estimateFeeRate(priority: FeePriority): Long? = manager.estimateFeeRate(priority)

    private companion object {
        /** Only the fact that the gate was decided survives process death, never its answer. */
        const val KEY_GATE_STARTED = "wallet_lock_gate_started"
    }
}
