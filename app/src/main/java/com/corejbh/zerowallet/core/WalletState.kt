package com.corejbh.zerowallet.core

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
    val error: String? = null,
    /**
     * When this phone last wrote a channel backup, and how many channels it held. A snapshot
     * is only as good as its age, so the settings sheet says how old this one is instead of
     * leaving the user to remember whether they ever saved one.
     */
    val lastChannelBackupEpochSecs: Long? = null,
    val lastChannelBackupChannels: Int = 0
) {
    val totalSats: Long get() = onchainTotalSats + lightningBalanceSats

    /** Whether this chain has a Rapid Gossip Sync server, so the option applies to it. */
    val rgsSupported: Boolean get() = network.rgsSupported
}

data class InvoiceResult(val invoice: String, val paymentHash: String)

/**
 * A ready-to-save channel backup: the sealed file, a suggested name and what went into it.
 *
 * The sealed archive is a file rather than a `ByteArray` on purpose. A node store is
 * megabytes, and holding the plaintext archive, the ciphertext and the copy handed to the
 * share sheet in memory at once was the largest allocation this wallet made; the export now
 * streams disk to disk and only this reference travels.
 */
class ChannelBackupExport(
    val file: File,
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
     * The amount the request names in millisatoshis, when it names one.
     *
     * Lightning invoices are allowed to price in thousandths of a satoshi, and 1000.9 sats
     * truncated to 1000 is a different payment: comparing the truncated figure with a BIP-21
     * `amount=` could call two halves of one QR "the same" when they are not, and the send
     * screen would show a total the invoice does not ask for. The exact value is kept for
     * every check; [amountSats] stays the figure the screen displays.
     */
    val amountMsats: Long? = null,
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

/**
 * Whether a BIP-21 on-chain amount and the invoice beside it name the same payment.
 *
 * The comparison is in millisatoshis because that is the precision an invoice has: a
 * 1,000.9-sat invoice is not a 1,000-sat `amount=`, and paying the on-chain half while the
 * Lightning half was meant would send a different sum than the QR asked for. A side that
 * names no amount agrees with anything, as there is nothing to disagree about.
 */
internal fun lightningAgreesWithOnchain(onchainSats: Long?, lightningMsats: Long?): Boolean =
    when {
        onchainSats == null || lightningMsats == null -> true
        else -> onchainSats * 1000L == lightningMsats
    }

/** A start that failed for a reason the wallet can explain in its own words. */
internal class NodeStartFailure(message: String) : Exception(message)
