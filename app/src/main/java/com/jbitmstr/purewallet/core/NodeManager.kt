package com.jbitmstr.purewallet.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The wallet's front door: one on-device ldk-node and everything the screens can do with it.
 *
 * There is no backend, no LSP and no custodial service in this path: the node talks to the
 * Bitcoin network through Esplora and to Lightning peers directly over TCP, and the recovery
 * phrase never leaves this phone.
 *
 * This class used to hold all of that in one 2,196-line file, where a payment path, a Keystore
 * seal and the node's start logic sat in the same scope and nothing said which fields a change
 * was allowed to touch. Since 1.2.0 it is a front door onto four layers that each own one
 * job, and it holds no state of its own:
 *
 * - [WalletLayer] - the phrase: create, restore, reveal, unlock and wipe.
 * - [NodeRuntime] - the node: bootstrap, start, stop, events, sync and the settings that
 *   change how it is built.
 * - [BackupLayer] - the encrypted channel backup, out and back in.
 * - [PaymentLayer] - invoices, offers, sends and channels.
 * - [MarketLayer] - the fiat figure and the fee rate.
 *
 * What they share lives in [WalletCore]: the node handle, the lock around it, the wallet
 * epoch, the state flow the screens render and the settings. Nothing is cached about the
 * phrase; every read opens the Keystore copy, so the Keystore's own window decides when a
 * pass still counts.
 */
class NodeManager(context: Context) {

    private val core = WalletCore(context)
    private val market = MarketLayer(core)
    private val runtime = NodeRuntime(core, market)
    private val wallet = WalletLayer(core, runtime)
    private val backup = BackupLayer(core, runtime)
    private val payments = PaymentLayer(core)

    val state: StateFlow<WalletState> = core.state
    val messages: SharedFlow<String> = core.messages

    // ------------------------------------------------------------- wallet

    /** Reads the saved settings once and starts the stored wallet's node. */
    fun bootstrap() = wallet.bootstrap()

    /**
     * Called when the wallet comes back to the screen: brings a dead node back, or resyncs
     * one that survived.
     */
    fun onAppForeground() = runtime.onAppForeground()

    /** Called when the app leaves the screen: the price loop stops, the node keeps running. */
    fun onAppBackground() = runtime.onAppBackground()

    /** Creates a wallet and returns its words; the node starts behind it. */
    suspend fun createWallet(wordCount: Int = 12): String = wallet.createWallet(wordCount)

    /** Replaces this device's wallet with the one [mnemonic] belongs to. */
    suspend fun restoreWallet(mnemonic: String): Result<Unit> = wallet.restoreWallet(mnemonic)

    /**
     * The recovery phrase, opened from the Keystore for this call only.
     *
     * The screen that shows it proves it is the user first; nothing about the phrase is kept
     * afterwards, so the next look asks again.
     */
    fun revealSeed(): String? = wallet.revealSeed()

    fun confirmSeedBackedUp() = wallet.confirmSeedBackedUp()

    /** Called after the device-lock prompt succeeded: opens the wallet and starts its node. */
    fun unlockSeed() = wallet.unlockSeed()

    /**
     * Deletes this wallet from the phone.
     *
     * Suspended on purpose: the screen that asked keeps its confirmation open until this
     * answers, so a wipe that could not finish still has somewhere to say why.
     */
    suspend fun wipeWallet(): Result<Unit> = wallet.wipeWallet()

    // --------------------------------------------------------------- node

    fun refresh() = runtime.refresh()

    fun stopNode() = runtime.stopNode()

    fun restartNode() = runtime.restartNode()

    fun switchNetwork(network: ChainNetwork) = runtime.switchNetwork(network)

    fun setEsploraUrl(url: String?): Result<Unit> = runtime.setEsploraUrl(url)

    fun setRapidGossipSync(enabled: Boolean) = runtime.setRapidGossipSync(enabled)

    fun setLockOnOpen(enabled: Boolean) = runtime.setLockOnOpen(enabled)

    fun setScreenProtection(enabled: Boolean) = runtime.setScreenProtection(enabled)

    // ------------------------------------------------------------- backup

    /** Packs the node store into a sealed `.zwbackup` file under the app's cache. */
    suspend fun exportChannelBackup(): Result<ChannelBackupExport> = backup.exportChannelBackup()

    /** Reads a backup's manifest without restoring it, for the confirmation. */
    suspend fun inspectChannelBackup(file: File): Result<BackupReview> =
        backup.inspectChannelBackup(file)

    /** Replaces this device's node store with the one inside [file]. */
    suspend fun importChannelBackup(file: File): Result<BackupManifest> =
        backup.importChannelBackup(file)

    // ------------------------------------------------------------ payments

    suspend fun createBolt11Invoice(amountSats: Long?, description: String): Result<InvoiceResult> =
        payments.createBolt11Invoice(amountSats, description)

    suspend fun createBolt12Offer(amountSats: Long?, description: String): Result<String> =
        payments.createBolt12Offer(amountSats, description)

    suspend fun newOnchainAddress(): Result<String> = payments.newOnchainAddress()

    suspend fun resolveTarget(input: PaymentInput): Result<ResolvedTarget> =
        payments.resolveTarget(input)

    suspend fun pay(
        target: ResolvedTarget,
        amountSats: Long?,
        viaLightning: Boolean = true,
        feeRateSatPerVb: Long? = null
    ): Result<String> = payments.pay(target, amountSats, viaLightning, feeRateSatPerVb)

    suspend fun sendOnchain(
        address: String,
        amountSats: Long?,
        sendAll: Boolean,
        feeRateSatPerVb: Long
    ): Result<String> = payments.sendOnchain(address, amountSats, sendAll, feeRateSatPerVb)

    suspend fun connectPeer(uri: String): Result<Unit> = payments.connectPeer(uri)

    suspend fun openChannel(uri: String, amountSats: Long, pushMsat: Long): Result<String> =
        payments.openChannel(uri, amountSats, pushMsat)

    suspend fun closeChannel(channel: ChannelRow, force: Boolean): Result<Unit> =
        payments.closeChannel(channel, force)

    // -------------------------------------------------------------- market

    /** The rate the send screen plans at, or null when the chain source did not answer. */
    suspend fun estimateFeeRate(priority: FeePriority): Long? = market.estimateFeeRate(priority)
}
