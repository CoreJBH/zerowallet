package com.jbitmstr.purewallet.core

import java.net.URLDecoder

enum class PaymentInputKind {
    BOLT11,
    BOLT12_OFFER,
    BOLT12_INVOICE,
    ONCHAIN,
    PEER,
    UNKNOWN
}

/**
 * A payment request the user pasted or scanned, before the node has looked at it.
 *
 * Parsing here is purely textual (BIP-21, BOLT11/12 prefixes, peer URIs); amounts and
 * descriptions come from the node once the request is resolved.
 */
data class PaymentInput(
    val kind: PaymentInputKind,
    val raw: String,
    val bolt11: String? = null,
    val bolt12Offer: String? = null,
    val bolt12Invoice: String? = null,
    val address: String? = null,
    val amountSats: Long? = null,
    val label: String? = null,
    val message: String? = null,
    val lightning: String? = null,
    val peerNodeId: String? = null,
    val peerAddress: String? = null,
    /**
     * Why a request was refused outright, in words the send screen can show. Set only on a
     * [PaymentInputKind.UNKNOWN] result whose shape was understood but must not be paid:
     * a QR carrying two different `amount` parameters, for instance.
     */
    val rejection: String? = null
)

object PaymentInputParser {

    fun parse(raw: String): PaymentInput {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) return PaymentInput(PaymentInputKind.UNKNOWN, text)

        val lower = text.lowercase()
        return when {
            lower.startsWith("bitcoin:") -> parseBip21(text)
            lower.startsWith("lightning:") -> parseLightning(text.substringAfter(':'))
            else -> {
                val lightning = parseLightning(text)
                when {
                    lightning.kind != PaymentInputKind.UNKNOWN -> lightning
                    parsePeer(text) != null -> parsePeer(text)!!
                    looksLikeAddress(text) -> PaymentInput(
                        kind = PaymentInputKind.ONCHAIN,
                        raw = text,
                        address = text
                    )
                    else -> PaymentInput(PaymentInputKind.UNKNOWN, text)
                }
            }
        }
    }

    /** Parses `pubkey@host:port`. Returns null when the text is not a peer URI. */
    fun parsePeer(text: String): PaymentInput? {
        val trimmed = text.trim()
        val at = trimmed.indexOf('@')
        if (at <= 0 || at == trimmed.length - 1) return null
        val nodeId = trimmed.substring(0, at)
        val address = trimmed.substring(at + 1)
        if (nodeId.length != 66 || !nodeId.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        if (!address.contains(':') || address.contains(' ')) return null
        val port = address.substringAfterLast(':').toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return PaymentInput(
            kind = PaymentInputKind.PEER,
            raw = trimmed,
            peerNodeId = nodeId.lowercase(),
            peerAddress = address
        )
    }

    fun looksLikeAddress(text: String): Boolean {
        val candidate = text.trim()
        if (candidate.contains(' ')) return false
        val lower = candidate.lowercase()
        if (lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("bcrt1")) {
            return candidate.length in 14..90
        }
        if (candidate.startsWith("1") || candidate.startsWith("3") ||
            candidate.startsWith("m") || candidate.startsWith("n") || candidate.startsWith("2")
        ) {
            return candidate.length in 26..62 && candidate.all { it.isLetterOrDigit() }
        }
        return false
    }

    /**
     * True when [address] carries the prefix of the chain it is meant for.
     *
     * Mainnet addresses start with `bc1` (bech32) or `1`/`3` (base58); testnet and signet
     * addresses start with `tb1` or `m`/`n`/`2`. Paying a mainnet address on a test chain (or
     * the other way round) can only fail, and a mistyped chain marker is exactly the kind of
     * mistake this check catches before the payment screen is reached. It is a shape check:
     * the node still decodes and verifies the address itself.
     */
    fun matchesNetwork(address: String, mainnet: Boolean): Boolean {
        val candidate = address.trim()
        if (candidate.isEmpty() || candidate.any { it.isWhitespace() }) return false
        val lower = candidate.lowercase()
        return if (mainnet) {
            when {
                lower.startsWith("bc1") -> candidate.length in 14..90
                candidate.startsWith("1") || candidate.startsWith("3") ->
                    candidate.length in 26..62 && candidate.all { it.isLetterOrDigit() }
                else -> false
            }
        } else {
            when {
                lower.startsWith("tb1") -> candidate.length in 14..90
                candidate.startsWith("m") || candidate.startsWith("n") || candidate.startsWith("2") ->
                    candidate.length in 26..62 && candidate.all { it.isLetterOrDigit() }
                else -> false
            }
        }
    }

    private fun parseLightning(value: String): PaymentInput {
        val text = value.trim()
        val lower = text.lowercase()
        return when {
            // The BOLT12 prefixes decide the kind, and the rest of the string decides whether
            // it is that kind at all. A broken `lno1...` must not fall through to the BOLT11
            // branch below just because its tail happens to look like bech32.
            lower.startsWith("lno1") -> if (looksLikeBolt12Offer(text)) {
                PaymentInput(kind = PaymentInputKind.BOLT12_OFFER, raw = text, bolt12Offer = text)
            } else {
                PaymentInput(PaymentInputKind.UNKNOWN, text)
            }
            lower.startsWith("lni1") -> if (looksLikeBolt12Invoice(text)) {
                PaymentInput(kind = PaymentInputKind.BOLT12_INVOICE, raw = text, bolt12Invoice = text)
            } else {
                PaymentInput(PaymentInputKind.UNKNOWN, text)
            }
            lower.startsWith("lnr1") -> PaymentInput(PaymentInputKind.UNKNOWN, text)
            lower.startsWith("ln") && lower.length > 20 && isBolt11Body(text) -> PaymentInput(
                kind = PaymentInputKind.BOLT11,
                raw = text,
                bolt11 = text
            )
            else -> PaymentInput(PaymentInputKind.UNKNOWN, text)
        }
    }

    /**
     * The Lightning request inside a BIP-21 `lightning=` parameter, when it is one this
     * wallet can pay.
     *
     * A BIP-21 QR can carry an on-chain address and the same recipient's Lightning invoice
     * side by side; the parameter is also used for BOLT12 offers and invoices. Anything
     * that is not a well-formed BOLT11/BOLT12 string is ignored here rather than reported:
     * the on-chain address in the same QR is still valid on its own.
     */
    fun parseLightningValue(value: String): PaymentInput? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val withoutScheme = if (trimmed.lowercase().startsWith("lightning:")) {
            trimmed.substringAfter(':')
        } else {
            trimmed
        }
        val parsed = parseLightning(withoutScheme)
        return when (parsed.kind) {
            PaymentInputKind.BOLT11, PaymentInputKind.BOLT12_OFFER, PaymentInputKind.BOLT12_INVOICE -> parsed
            else -> null
        }
    }

    /**
     * BOLT12 offers are bech32 strings with the `lno1` prefix.
     *
     * The prefix alone is not enough: a truncated or mistyped string also starts with
     * `lno1`, and handing it to the node only fails after the payment screen. The payload
     * must be bech32 data (the separator `1` never repeats, and `b`, `i` and `o` are not in
     * the alphabet) and long enough to carry a signed offer.
     */
    fun looksLikeBolt12Offer(text: String): Boolean =
        text.length >= MIN_BOLT12_LENGTH &&
            text.lowercase().startsWith("lno1") &&
            isBech32Body(text, "lno".length)

    /** BOLT12 invoices use the `lni1` prefix; the same shape rules apply. */
    fun looksLikeBolt12Invoice(text: String): Boolean =
        text.length >= MIN_BOLT12_LENGTH &&
            text.lowercase().startsWith("lni1") &&
            isBech32Body(text, "lni".length)

    /**
     * True when everything after the `1` separator at [prefixLength] is bech32 data.
     *
     * Accepts all-lowercase and all-uppercase bech32 (never a mix), which is what the
     * encoding allows and what a QR scanner can hand over.
     */
    private fun isBech32Body(text: String, prefixLength: Int): Boolean {
        val body = text.substring(prefixLength)
        if (body.length < 7 || body[0] != '1') return false
        return isBech32Data(body.substring(1))
    }

    /**
     * True when [text] is a BOLT11 invoice: `ln` + currency + optional amount + `1` + data.
     *
     * The separator is the last `1` in the string, because bech32 data cannot contain one.
     */
    private fun isBolt11Body(text: String): Boolean {
        val separator = text.lastIndexOf('1')
        if (separator <= 2 || separator >= text.length - 1) return false
        return isBech32Data(text.substring(separator + 1))
    }

    private fun isBech32Data(data: String): Boolean {
        if (data.length < 6) return false
        val lower = data.lowercase()
        return lower.all { it in BECH32_CHARSET }
    }

    /** Characters bech32 data may use; `1` is only the separator. */
    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /** Decimals in one bitcoin: a satoshi is the smallest amount an invoice can name. */
    private const val SATS_DECIMALS = 8

    /** The BIP-21 prefix for a parameter a wallet has to understand to pay at all. */
    private const val REQUIRED_PREFIX = "req-"

    /**
     * Shortest string this wallet treats as BOLT12. A real offer carries a 33-byte signing
     * key, a description and a signature, so it is several hundred characters; this is only
     * a sanity floor that keeps obvious junk (`lno1q`) out of the payment flow.
     */
    private const val MIN_BOLT12_LENGTH = 60

    private fun parseBip21(text: String): PaymentInput {
        val body = text.substringAfter(':').trim()
        val address = body.substringBefore('?').trim()
        val query = if (body.contains('?')) body.substringAfter('?') else ""

        var amountSats: Long? = null
        var label: String? = null
        var message: String? = null
        var lightning: String? = null
        var amountSeen = false
        var lightningSeen = false

        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val key = pair.substringBefore('=').lowercase()
                val value = decode(pair.substringAfter('=', ""))
                when (key) {
                    "amount" -> {
                        // Two amounts in one request are not a request this wallet will pay:
                        // BIP-21 does not say which one wins, so other wallets can read a
                        // different amount than this one, and the payer cannot tell which
                        // number the receiver meant.
                        if (amountSeen) return duplicateAmount(text)
                        amountSeen = true
                        // An amount that cannot be read is refused, not dropped: treating it
                        // as "no amount" would show an editable box and let the payer send a
                        // sum of their own to a request that named one.
                        amountSats = parseBip21Amount(value)
                            ?: return unreadableAmount(text, value)
                    }
                    "label" -> label = value.ifBlank { null }
                    "message" -> message = value.ifBlank { null }
                    "lightning" -> {
                        // Same reasoning as the amount: two Lightning requests in one code is
                        // not a request this wallet can pay without guessing which one the
                        // receiver meant, and a wallet that guessed differently would settle
                        // a different payment than the QR was read as.
                        if (lightningSeen) return duplicateLightning(text)
                        lightningSeen = true
                        lightning = value.ifBlank { null }
                    }
                    else -> {
                        // BIP-21 says a `req-` parameter is required: a wallet that does not
                        // understand it must refuse the whole request rather than pay the
                        // part it recognises. A future extension could change where the funds
                        // go, so ignoring it would be paying a request that was not read.
                        if (key.startsWith(REQUIRED_PREFIX)) return unsupportedRequirement(text, key)
                    }
                }
            }
        }

        if (!address.isEmpty() && looksLikeAddress(address)) {
            return PaymentInput(
                kind = PaymentInputKind.ONCHAIN,
                raw = text,
                address = address,
                amountSats = amountSats,
                label = label,
                message = message,
                lightning = lightning
            )
        }
        return PaymentInput(PaymentInputKind.UNKNOWN, text)
    }

    private fun duplicateAmount(text: String) = PaymentInput(
        kind = PaymentInputKind.UNKNOWN,
        raw = text,
        rejection = "This payment request carries more than one amount. Wallets read that " +
            "differently, so it is not paid here. Ask for a new QR or a Lightning invoice."
    )

    private fun duplicateLightning(text: String) = PaymentInput(
        kind = PaymentInputKind.UNKNOWN,
        raw = text,
        rejection = "This payment request carries more than one Lightning request. Wallets " +
            "read that differently, so it is not paid here. Ask for a new QR code or a plain " +
            "Lightning invoice."
    )

    /**
     * A `req-` parameter this wallet does not implement. BIP-21 makes it a condition of paying,
     * so the request is refused whole rather than paid with the extension dropped.
     */
    private fun unsupportedRequirement(text: String, key: String) = PaymentInput(
        kind = PaymentInputKind.UNKNOWN,
        raw = text,
        rejection = "This payment request requires something this wallet does not support " +
            "(${key.take(24)}). BIP-21 says such a request must not be paid without it, so it " +
            "is refused here - ask for a plain address or a Lightning invoice."
    )

    /**
     * The `amount=` of a BIP-21 request in satoshis, or null when it is not an amount this
     * wallet can read.
     *
     * BIP-21 spells the amount as whole bitcoin in plain decimal - digits with at most one
     * dot - so that is the only shape accepted here: no exponent, no comma, no sign. More
     * decimals than a satoshi has are refused too. Rounding them down would quietly pay a
     * different sum than the one the receiver asked for, and rounding up would pay more.
     */
    private fun parseBip21Amount(value: String): Long? {
        val cleaned = value.trim()
        if (cleaned.isEmpty()) return null
        if (!cleaned.all { it.isDigit() || it == '.' }) return null
        if (cleaned.count { it == '.' } > 1) return null
        val fraction = cleaned.substringAfter('.', "").trimEnd('0')
        if (fraction.length > SATS_DECIMALS) return null
        return Fmt.parseBtcToSats(cleaned)
    }

    /**
     * A request that names an amount this wallet cannot read. It is refused whole: falling
     * through to the address alone would turn a fixed sum into an editable one.
     */
    private fun unreadableAmount(text: String, value: String) = PaymentInput(
        kind = PaymentInputKind.UNKNOWN,
        raw = text,
        rejection = "This payment request carries an amount this wallet cannot read " +
            "(amount=${value.take(24)}). An amount is plain bitcoin with at most 8 " +
            "decimals, so the sum is not guessed here - ask for a new QR code or a " +
            "Lightning invoice."
    )

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        value
    }
}
