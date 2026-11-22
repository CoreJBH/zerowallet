package com.jbitmstr.purewallet.core

/**
 * The folder names a channel-backup import passes through, and how the next start finds them.
 *
 * An import is three renames: the backup is written into `incoming-<chain>-<stamp>`, the live
 * store is moved aside to `previous-<chain>-<stamp>`, and the new folder is renamed into the
 * chain's place. A process killed between the second and the third leaves the chain's
 * directory missing while its whole store sits one rename away - a wallet that would start as
 * an empty node and look wiped, with its channels on disk the whole time.
 *
 * The chain is part of the name because every chain's store lives under the same `ldk-node/`
 * folder: without it, a restore interrupted on mainnet could be rolled back into signet's
 * directory the next time the app opened on that chain.
 *
 * Pure, so the matching and the ordering are unit-tested rather than read off a file listing.
 */
internal object NodeDirs {

    private const val INCOMING = "incoming"
    private const val PREVIOUS = "previous"

    /** Where an import writes the backup it is about to put in place. */
    fun incoming(networkId: String, stamp: Long): String = "$INCOMING-$networkId-$stamp"

    /** Where an import moves the store it is replacing, until it knows the new one works. */
    fun previous(networkId: String, stamp: Long): String = "$PREVIOUS-$networkId-$stamp"

    /** True for a folder written by an import for [networkId] that never reached the swap. */
    fun isIncoming(name: String, networkId: String): Boolean =
        name.startsWith("$INCOMING-$networkId-")

    /**
     * True for any folder an import was writing into, whichever chain it was for.
     *
     * Such a folder is a backup that never reached the swap, so it is never a wallet: every
     * one of them can go, including one left by an import on a chain that is not open now.
     */
    fun isAnyIncoming(name: String): Boolean = name.startsWith("$INCOMING-")

    /** True for a folder holding a store [networkId] displaced. */
    fun isPrevious(name: String, networkId: String): Boolean =
        name.startsWith("$PREVIOUS-$networkId-")

    /**
     * The newest displaced store for [networkId], or null when there is none.
     *
     * The stamp is fixed-width enough to sort as text for any date this wallet will see, and
     * the newest is the one closest to what the user had: an older folder could be the store
     * from a restore that was already replaced.
     */
    fun newestPrevious(names: List<String>, networkId: String): String? =
        names.filter { isPrevious(it, networkId) }.maxOrNull()

    /** Every folder an interrupted import left half-written, for the caller to clear. */
    fun incomingDirs(names: List<String>): List<String> = names.filter { isAnyIncoming(it) }
}
