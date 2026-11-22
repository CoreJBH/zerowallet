package com.jbitmstr.purewallet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.jbitmstr.purewallet.core.ChainNetwork
import com.jbitmstr.purewallet.core.ChannelBackupExport
import com.jbitmstr.purewallet.core.ChannelRow
import com.jbitmstr.purewallet.core.BackupManifest
import com.jbitmstr.purewallet.core.FeePriority
import com.jbitmstr.purewallet.core.InvoiceResult
import com.jbitmstr.purewallet.core.PaymentInput
import com.jbitmstr.purewallet.core.ResolvedTarget

/**
 * Thin bridge between the Compose screens and [com.jbitmstr.purewallet.core.NodeManager].
 * The node itself outlives configuration changes, so no work is tied to this ViewModel.
 */
class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = (application as PureWalletApp).nodeManager

    val state = manager.state
    val messages = manager.messages

    fun bootstrap() = manager.bootstrap()

    /** The wallet is back on screen: bring the node back if it was frozen or failed. */
    fun onAppForeground() = manager.onAppForeground()

    fun createWallet(wordCount: Int = 12): String = manager.createWallet(wordCount)

    suspend fun restoreWallet(mnemonic: String): Result<Unit> = manager.restoreWallet(mnemonic)

    fun revealSeed(): String? = manager.revealSeed()

    fun confirmBackup() = manager.confirmSeedBackedUp()

    suspend fun exportChannelBackup(): Result<ChannelBackupExport> = manager.exportChannelBackup()

    suspend fun importChannelBackup(bytes: ByteArray): Result<BackupManifest> =
        manager.importChannelBackup(bytes)

    fun refresh() = manager.refresh()

    fun stopNode() = manager.stopNode()

    fun restartNode() = manager.restartNode()

    fun switchNetwork(network: ChainNetwork) = manager.switchNetwork(network)

    fun setEsploraUrl(url: String?): Result<Unit> = manager.setEsploraUrl(url)

    fun setRapidGossipSync(enabled: Boolean) = manager.setRapidGossipSync(enabled)

    fun setLockOnOpen(enabled: Boolean) = manager.setLockOnOpen(enabled)

    fun setScreenProtection(enabled: Boolean) = manager.setScreenProtection(enabled)

    fun wipeWallet() = manager.wipeWallet()

    suspend fun createInvoice(amountSats: Long?, description: String): Result<InvoiceResult> =
        manager.createBolt11Invoice(amountSats, description)

    suspend fun createOffer(amountSats: Long?, description: String): Result<String> =
        manager.createBolt12Offer(amountSats, description)

    suspend fun newAddress(): Result<String> = manager.newOnchainAddress()

    suspend fun resolveTarget(input: PaymentInput): Result<ResolvedTarget> =
        manager.resolveTarget(input)

    suspend fun pay(target: ResolvedTarget, amountSats: Long?, priority: FeePriority): Result<String> =
        manager.pay(target, amountSats, priority)

    suspend fun sendOnchain(
        address: String,
        amountSats: Long?,
        sendAll: Boolean,
        priority: FeePriority
    ): Result<String> = manager.sendOnchain(address, amountSats, sendAll, priority)

    suspend fun connectPeer(uri: String): Result<Unit> = manager.connectPeer(uri)

    suspend fun openChannel(uri: String, amountSats: Long, pushMsat: Long): Result<String> =
        manager.openChannel(uri, amountSats, pushMsat)

    suspend fun closeChannel(channel: ChannelRow, force: Boolean): Result<Unit> =
        manager.closeChannel(channel, force)

    suspend fun estimateFeeRate(priority: FeePriority): Long = manager.estimateFeeRate(priority)
}
