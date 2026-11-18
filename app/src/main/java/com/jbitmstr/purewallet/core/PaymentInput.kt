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
    val peerAddress: String? = null
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

    private fun parseLightning(value: String): PaymentInput {
        val text = value.trim()
        val lower = text.lowercase()
        return when {
            lower.startsWith("lno1") -> PaymentInput(
                kind = PaymentInputKind.BOLT12_OFFER,
                raw = text,
                bolt12Offer = text
            )
            lower.startsWith("lni1") -> PaymentInput(
                kind = PaymentInputKind.BOLT12_INVOICE,
                raw = text,
                bolt12Invoice = text
            )
            lower.startsWith("lnr1") -> PaymentInput(PaymentInputKind.UNKNOWN, text)
            lower.startsWith("ln") && lower.length > 20 -> PaymentInput(
                kind = PaymentInputKind.BOLT11,
                raw = text,
                bolt11 = text
            )
            else -> PaymentInput(PaymentInputKind.UNKNOWN, text)
        }
    }

    private fun parseBip21(text: String): PaymentInput {
        val body = text.substringAfter(':').trim()
        val address = body.substringBefore('?').trim()
        val query = if (body.contains('?')) body.substringAfter('?') else ""

        var amountSats: Long? = null
        var label: String? = null
        var message: String? = null
        var lightning: String? = null

        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val key = pair.substringBefore('=').lowercase()
                val value = decode(pair.substringAfter('=', ""))
                when (key) {
                    "amount" -> amountSats = Fmt.parseBtcToSats(value)
                    "label" -> label = value.ifBlank { null }
                    "message" -> message = value.ifBlank { null }
                    "lightning" -> lightning = value.ifBlank { null }
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

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        value
    }
}
