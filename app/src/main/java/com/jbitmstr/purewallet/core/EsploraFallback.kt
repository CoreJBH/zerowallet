package com.jbitmstr.purewallet.core

import java.net.URI

/**
 * Which Esplora endpoint a start uses, and what to do when the chain source will not answer.
 *
 * A phone on a carrier network meets endpoints that time out for a while: ldk-node then fails
 * the whole start with `FeerateEstimationUpdateTimeout` ("Updating fee rate estimates timed
 * out.") and the wallet sat on "Stopped" even though another public server was reachable a
 * second away. The saved endpoint is therefore tried first, then the endpoint that last
 * worked on this device, then the chain's other public endpoints — each with the same
 * Lightning-port fallback as before.
 *
 * Public endpoints are only ever swapped for other public endpoints. An endpoint the user
 * entered themselves (their own Esplora, a local one, a paid one) is never quietly replaced:
 * sending a wallet's addresses to a server its owner did not choose is a privacy decision
 * that belongs to them, so that start fails with a message instead.
 *
 * Deliberately free of Android APIs so the order can be covered by plain JVM unit tests.
 */
internal object EsploraFallback {

    /**
     * Endpoints to try, in order: the saved one, the one that last worked here, then the
     * chain's other public endpoints. [allowFallbacks] is false for an endpoint the user
     * chose themselves, where the saved endpoint is the only one tried.
     *
     * Duplicates are dropped after trimming the trailing slash and ignoring case, so a server
     * that appears twice in the list never costs a second start.
     */
    fun candidates(
        configured: String,
        lastWorking: String?,
        fallbacks: List<String>,
        allowFallbacks: Boolean
    ): List<String> {
        val ordered = mutableListOf(configured)
        if (allowFallbacks) {
            lastWorking?.takeIf { it.isNotBlank() }?.let { ordered += it }
            ordered += fallbacks
        }
        val seen = mutableSetOf<String>()
        return ordered.filter { url ->
            val key = key(url)
            key.isNotEmpty() && seen.add(key)
        }
    }

    /** True when [url] is one of the chain's built-in public endpoints. */
    fun isPublicEndpoint(url: String, publicEndpoints: List<String>): Boolean {
        val target = key(url)
        return publicEndpoints.any { key(it) == target }
    }

    /**
     * True when a failed start came from the chain source rather than the phone's Lightning
     * port: a feerate timeout, an Esplora error, a connection that never opened, an HTTP
     * refusal. Those are worth another endpoint; dropping the listening address is not what
     * fixes them.
     */
    fun isChainSourceFailure(t: Throwable): Boolean {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 6) {
            val text = buildString {
                append(current::class.java.simpleName)
                append(' ')
                append(current.message.orEmpty())
            }.lowercase()
            if (CHAIN_SOURCE_MARKERS.any { text.contains(it) }) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * A short name for an endpoint, for messages: Blockstream, mempool.space, mempool.emzy.de,
     * or the host itself for anything else.
     */
    fun displayName(url: String): String {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()
        if (host.isNullOrBlank()) return url
        return when (host) {
            "blockstream.info" -> "Blockstream"
            else -> host.removePrefix("www.")
        }
    }

    /** What the user is told when a start had to move to another public endpoint. */
    fun switchedMessage(from: String, to: String): String =
        "${displayName(from)} did not answer, so the node is using ${displayName(to)}."

    /**
     * What the user is told when every endpoint that could be tried failed on the chain
     * source. [tried] is the list of endpoints, in order.
     */
    fun exhaustedMessage(tried: List<String>, lastFailure: Throwable?): String {
        val servers = when (tried.size) {
            0 -> "the Esplora endpoint"
            1 -> displayName(tried.first())
            else -> tried.map { displayName(it) }.distinct().joinToString(", ")
        }
        val last = lastFailure?.message?.takeIf { it.isNotBlank() }
        val detail = if (last != null) " Last error: $last" else ""
        return if (tried.size > 1) {
            "No Esplora endpoint answered (tried $servers).$detail"
        } else {
            "The Esplora endpoint ($servers) did not answer.$detail"
        }
    }

    private fun key(url: String): String = url.trim().trimEnd('/').lowercase()

    private val CHAIN_SOURCE_MARKERS = listOf(
        "feerateestimation",
        "feerate",
        "esplora",
        "timed out",
        "timeout",
        "connection refused",
        "connection reset",
        "failed to connect",
        "connect error",
        "dns",
        "unreachable",
        "no route to host",
        "network is unreachable",
        "429",
        "503",
        "502",
        "504"
    )
}
