package com.jbitmstr.purewallet.core

/**
 * BIP-21 payment URIs for an on-chain address this wallet hands out.
 *
 * The receive sheet used to build `bitcoin:<address>?amount=...` inline, from a field the
 * on-chain tab never showed: the amount could only ever be empty there, so the `amount`
 * parameter was dead code and every on-chain QR asked for no particular sum. The builder
 * lives here so the rule is explicit and tested apart from the screen:
 *
 * - the address must be an address and nothing else: no scheme, no query, no whitespace, so
 *   a value that is already a URI cannot be wrapped a second time;
 * - an amount of zero or less is no amount, and the URI then carries no `amount` parameter,
 *   because `amount=0` is a request to send nothing;
 * - the amount is written in BTC with eight decimals, the precision BIP-21 defines, never
 *   more than the 21 million supply.
 */
object Bip21 {

    const val SCHEME = "bitcoin:"

    /** `bitcoin:<address>` or `bitcoin:<address>?amount=<btc>` for a positive [amountSats]. */
    fun uri(address: String, amountSats: Long? = null): String {
        val clean = address.trim()
        require(clean.isNotEmpty()) { "There is no address to make a payment request from" }
        require(!clean.contains(SCHEME, ignoreCase = true)) { "The address already carries a bitcoin: scheme" }
        require(clean.none { it.isWhitespace() || it == '?' || it == '&' || it == ':' }) {
            "That is not a plain Bitcoin address"
        }
        val amount = amountSats?.takeIf { it > 0L }
        if (amount == null) return SCHEME + clean
        require(amount <= Fmt.MAX_SATS) { "That is more than the 21 million BTC that exist" }
        return SCHEME + clean + "?amount=" + Fmt.btc(amount)
    }

    /**
     * The address inside a BIP-21 URI, or [uri] itself when it carries no scheme.
     *
     * The receive sheet shows the bare address under the QR code (the code itself holds the
     * whole URI), and whoever copies it wants the address, not the query that belongs to
     * this one request.
     */
    fun addressOf(uri: String): String {
        val text = uri.trim()
        val withoutScheme = if (text.startsWith(SCHEME, ignoreCase = true)) {
            text.substring(SCHEME.length)
        } else {
            text
        }
        return withoutScheme.substringBefore('?')
    }
}
