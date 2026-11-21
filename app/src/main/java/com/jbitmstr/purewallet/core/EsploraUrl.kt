package com.jbitmstr.purewallet.core

import java.net.URI

/**
 * Validation for the Esplora endpoints this wallet talks to.
 *
 * Everything the node reads about the chain (balances, history, fee estimates) comes from
 * this URL, so a custom endpoint is only accepted when it is an `https://` address. The
 * single exception is a host on the phone itself (`127.0.0.1`, `::1`, `localhost`), where
 * a personal Esplora instance or a local proxy usually speaks plain `http://` and the
 * traffic never leaves the device.
 *
 * Deliberately free of Android APIs so the rules can be covered by plain JVM unit tests.
 */
object EsploraUrl {

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "[::1]", "localhost")

    /**
     * Returns the normalized URL, or a [Result.failure] carrying a message that can be
     * shown to the user as-is.
     */
    fun validate(raw: String): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return failure("Enter an Esplora endpoint URL.")
        }
        if (trimmed.any { it.isWhitespace() }) {
            return failure("The endpoint URL cannot contain spaces.")
        }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return failure("That is not a valid URL. Include the full address, for example https://mempool.space/api.")
        }

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme.isNullOrEmpty() || host.isNullOrEmpty()) {
            return failure("Include the full URL with its scheme, for example https://mempool.space/api.")
        }
        if (uri.userInfo != null) {
            return failure("Remove the username or password from the endpoint URL.")
        }
        if (uri.rawQuery != null || uri.fragment != null) {
            return failure("Remove the query string or fragment from the endpoint URL.")
        }

        val loopback = host in LOOPBACK_HOSTS || host.startsWith("127.")
        val allowed = scheme == "https" || (scheme == "http" && loopback)
        if (!allowed) {
            return failure(
                "Use an https:// endpoint. Plain http:// is only allowed for a node on this " +
                    "device (127.0.0.1 or localhost)."
            )
        }

        return Result.success(trimmed.trimEnd('/'))
    }

    /** True when [raw] would be accepted by [validate]. */
    fun isValid(raw: String): Boolean = validate(raw).isSuccess

    private fun failure(message: String): Result<String> =
        Result.failure(IllegalArgumentException(message))
}
