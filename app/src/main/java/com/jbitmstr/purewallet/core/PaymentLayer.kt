package com.jbitmstr.purewallet.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Bolt11InvoiceDescription
import org.lightningdevkit.ldknode.Bolt12Invoice
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.FeeRate
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.Offer
import org.lightningdevkit.ldknode.OfferAmount
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.lightningdevkit.ldknode.QrPaymentResult

/**
 * The payment layer: invoices and offers in, every kind of send out, and the channel actions
 * that make a send possible.
 *
 * Nothing here touches the seed or the start path. Every call runs against the running node
 * through [WalletCore.withNode], which holds the node lock for its whole length, so a stop,
 * a wipe or a channel-backup import cannot pull the node out from under a payment that is
 * already talking to it.
 *
 * A Lightning send that returns without an error is *in flight*, not settled: the node only
 * knows it went out when `PaymentSuccessful` arrives. [markPending] is what keeps the screen
 * from calling it sent before then.
 */
internal class PaymentLayer(private val core: WalletCore) {

    // ------------------------------------------------------------- receiving

    suspend fun createBolt11Invoice(amountSats: Long?, description: String): Result<InvoiceResult> =
        runCatching {
            core.withNode { n ->
                val desc = Bolt11InvoiceDescription.Direct(description.trim())
                val invoice = if (amountSats == null || amountSats <= 0L) {
                    n.bolt11Payment().receiveVariableAmount(desc, INVOICE_EXPIRY_SECS)
                } else {
                    n.bolt11Payment().receive(core.millisats(amountSats), desc, INVOICE_EXPIRY_SECS)
                }
                val hash = invoice.paymentHash()
                // A note the Keystore could not seal is simply not saved (fail-closed); the
                // invoice itself is what the user asked for and is returned regardless.
                core.notes.put(hash, description)
                InvoiceResult(invoice = invoice.toString(), paymentHash = hash)
            }
        }

    suspend fun createBolt12Offer(amountSats: Long?, description: String): Result<String> =
        runCatching {
            core.withNode { n ->
                val trimmed = description.trim()
                val offer = if (amountSats == null || amountSats <= 0L) {
                    n.bolt12Payment().receiveVariableAmount(trimmed, null)
                } else {
                    n.bolt12Payment().receive(core.millisats(amountSats), trimmed, null, null)
                }
                offer.toString()
            }
        }

    suspend fun newOnchainAddress(): Result<String> = runCatching {
        core.withNode { n -> n.onchainPayment().newAddress() }
    }

    // --------------------------------------------------------------- sending

    /** Reads the amount and description of a pasted/scanned payment request. */
    suspend fun resolveTarget(input: PaymentInput): Result<ResolvedTarget> = runCatching {
        core.withNode { n ->
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
                            "is on ${core.prefs.network.title}. Switch networks in Settings to pay it."
                    )
                }
                if (invoice.isExpired()) {
                    throw IllegalArgumentException("This invoice has expired")
                }
                val description = when (val d = invoice.invoiceDescription()) {
                    is Bolt11InvoiceDescription.Direct -> d.description
                    is Bolt11InvoiceDescription.Hash -> ""
                }
                val amountMsats = invoice.amountMilliSatoshis()?.toLong()
                ResolvedTarget(
                    input = input.copy(bolt11 = raw),
                    description = description,
                    amountSats = amountMsats?.let { it / 1000L },
                    amountEditable = amountMsats == null,
                    amountMsats = amountMsats
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
                        "This offer is not for ${core.prefs.network.title}, so this wallet cannot pay it"
                    )
                }
                if (offer.isExpired()) {
                    throw IllegalArgumentException("This offer has expired")
                }
                val fixedMsats = when (val amount = offer.amount()) {
                    is OfferAmount.Bitcoin -> amount.amountMsats.toLong()
                    is OfferAmount.Currency -> throw IllegalArgumentException(
                        "This offer is priced in ${amount.iso4217Code} and cannot be paid from here"
                    )
                    null -> null
                }
                ResolvedTarget(
                    input = input.copy(bolt12Offer = raw),
                    description = offer.offerDescription() ?: "",
                    amountSats = fixedMsats?.let { it / 1000L },
                    amountEditable = fixedMsats == null,
                    amountMsats = fixedMsats
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
                if (!Bolt12Chain.matches(invoice.chain(), core.prefs.network)) {
                    throw IllegalArgumentException(
                        "This BOLT12 invoice is for another chain, and this wallet is on " +
                            core.prefs.network.title
                    )
                }
                if (invoice.isExpired()) {
                    throw IllegalArgumentException("This invoice has expired")
                }
                val invoiceMsats = invoice.amountMsats().toLong()
                ResolvedTarget(
                    input = input.copy(bolt12Invoice = raw),
                    description = invoice.invoiceDescription() ?: "",
                    amountSats = invoiceMsats / 1000L,
                    amountEditable = false,
                    amountMsats = invoiceMsats
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
        val network = core.prefs.network
        if (!PaymentInputParser.matchesNetwork(address, network.isMainnet)) {
            throw IllegalArgumentException(
                "That is not a ${network.title} address. Check the address or switch networks in Settings."
            )
        }

        val onchainSats = input.amountSats?.let { core.checkedSats(it) }
        val lightning = input.lightning
            ?.let { PaymentInputParser.parseLightningValue(it) }
            ?.let { parsed -> runCatching { resolveLightning(n, parsed) }.getOrNull() }
        // Two halves that disagree on the amount are not the same payment: the on-chain
        // amount is the one the sender wrote on the QR, so that route is used instead. The
        // invoice's own millisatoshis are compared, not the sats the screen rounds them to.
        val agreed = when {
            lightning == null -> null
            !lightningAgreesWithOnchain(onchainSats, lightning.amountMsats) -> null
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
        val outbound = core.snapshot.channels
            .filter { it.usable }
            .sumOf { it.localSats }
        if (outbound <= 0L) return false
        val needed = amountSats ?: return outbound > 1_000L
        return outbound - needed >= 500L
    }

    /**
     * Pays a resolved request. Returns a channel-agnostic reference: the payment id for
     * Lightning payments and the transaction id for on-chain payments.
     *
     * There is no guess here. A request the parser could not name (`UNKNOWN`) is refused:
     * handing its raw text to the node's unified-QR send was a fallback that decided the
     * payment route *after* the confirmation screen had shown one, so a QR this wallet did not
     * understand could be paid over a road the user was never shown, with an amount the screen
     * never had.
     */
    suspend fun pay(
        target: ResolvedTarget,
        amountSats: Long?,
        viaLightning: Boolean = true,
        feeRateSatPerVb: Long? = null
    ): Result<String> = runCatching {
        core.withNode { n ->
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
                    PaymentInputKind.UNKNOWN -> throw IllegalArgumentException(
                        "Unrecognized payment request. Scan or paste the request again."
                    )
                    PaymentInputKind.ONCHAIN -> {
                        val address = input.address
                            ?: throw IllegalArgumentException("No Bitcoin address found")
                        val sats = amountSats ?: target.amountSats
                            ?: throw IllegalArgumentException("Enter an amount")
                        val rate = feeRateSatPerVb
                            ?: throw IllegalStateException(
                                "The fee rate has not been confirmed yet"
                            )
                        sendOnchainInternal(n, address, core.checkedSats(sats), rate)
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
                    n.bolt11Payment().sendUsingAmount(invoice, core.millisats(sats), null)
                } else {
                    n.bolt11Payment().send(invoice, null)
                }
                markPending(id)
            }
            PaymentInputKind.BOLT12_OFFER -> {
                val offer = Offer.fromStr(input.bolt12Offer ?: input.raw)
                val id = if (offer.amount() == null) {
                    val sats = amountSats ?: throw IllegalArgumentException("Enter an amount")
                    n.bolt12Payment().sendUsingAmount(offer, core.millisats(sats), null, null, null)
                } else {
                    n.bolt12Payment().send(offer, null, null, null)
                }
                markPending(id)
            }
            PaymentInputKind.BOLT12_INVOICE -> {
                when (val result = n.unifiedQrPayment().send(input.raw, null)) {
                    is QrPaymentResult.Onchain -> result.txid
                    is QrPaymentResult.Bolt11 -> markPending(result.paymentId)
                    is QrPaymentResult.Bolt12 -> markPending(result.paymentId)
                }
            }
            else -> throw IllegalArgumentException("This is not a Lightning payment request")
        }

    /**
     * Records that a Lightning payment has left this node but has not settled, and says so.
     *
     * The send screen and the history list must not read "sent" before the node reports the
     * payment settled (`Event.PaymentSuccessful`); between the two it is in flight, and
     * `Event.PaymentFailed` can still take it back.
     */
    private fun markPending(paymentId: String): String {
        if (paymentId.isNotBlank()) {
            core.pendingLightning.add(paymentId)
            core.say("Payment sending")
        }
        return paymentId
    }

    suspend fun sendOnchain(
        address: String,
        amountSats: Long?,
        sendAll: Boolean,
        feeRateSatPerVb: Long
    ): Result<String> = runCatching {
        core.withNode { n ->
            if (sendAll) {
                n.onchainPayment().sendAllToAddress(address, true, sendRate(feeRateSatPerVb))
            } else {
                val sats = amountSats ?: throw IllegalArgumentException("Enter an amount")
                sendOnchainInternal(n, address, core.checkedSats(sats), feeRateSatPerVb)
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

    // -------------------------------------------------------------- channels

    suspend fun connectPeer(uri: String): Result<Unit> = runCatching {
        core.withNode { n ->
            val peer = PaymentInputParser.parsePeer(uri)
                ?: throw IllegalArgumentException("Enter a peer as pubkey@host:port")
            n.connect(peer.peerNodeId!!, peer.peerAddress!!, true)
        }
    }

    suspend fun openChannel(uri: String, amountSats: Long, pushMsat: Long): Result<String> = runCatching {
        core.withNode { n ->
            val peer = PaymentInputParser.parsePeer(uri)
                ?: throw IllegalArgumentException("Enter a peer as pubkey@host:port")
            val capacity = core.checkedSats(amountSats, "Enter a channel size in sats")
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
        core.withNode { n ->
            if (force) {
                n.forceCloseChannel(channel.userChannelId, channel.peer, "Closed from ZeroWallet")
            } else {
                n.closeChannel(channel.userChannelId, channel.peer)
            }
        }
    }

    companion object {
        private val INVOICE_EXPIRY_SECS: UInt = 3600u

        /** A chain's name for a message the user reads. */
        private fun networkName(network: Network): String = when (network) {
            Network.BITCOIN -> "Bitcoin Mainnet"
            Network.TESTNET -> "Testnet3"
            Network.SIGNET -> "Signet"
            Network.REGTEST -> "Regtest"
        }

        /** One sync row of the history list, with the local note the node does not keep. */
        fun toRow(payment: PaymentDetails, notes: PaymentNotes): PaymentRow {
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

        /** One sync row of the channel list. */
        fun toChannelRow(channel: ChannelDetails): ChannelRow {
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

        /** The satoshis still on their way back from a closed channel. */
        fun pendingSweepSats(balance: org.lightningdevkit.ldknode.PendingSweepBalance): Long =
            when (balance) {
                is org.lightningdevkit.ldknode.PendingSweepBalance.PendingBroadcast ->
                    balance.amountSatoshis.toLong()
                is org.lightningdevkit.ldknode.PendingSweepBalance.BroadcastAwaitingConfirmation ->
                    balance.amountSatoshis.toLong()
                is org.lightningdevkit.ldknode.PendingSweepBalance.AwaitingThresholdConfirmations ->
                    balance.amountSatoshis.toLong()
            }
    }
}
