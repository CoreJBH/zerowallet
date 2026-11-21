package com.jbitmstr.purewallet.core

import android.content.Context
import java.io.File

/**
 * Size cap for the LDK filesystem logger.
 *
 * `ldk-node` appends to `files/logs/ldk-node.log` for as long as the node runs. Left
 * alone that file grows without limit on a phone. [rotateIfNeeded] keeps the active log
 * under [MAX_BYTES] and retains [ARCHIVE_COUNT] older files (`ldk-node.log.1`, `.2`).
 *
 * Rotation only happens while the node is stopped (before the logger opens the file and
 * after it closes it): renaming a log that a live writer still holds open would leave the
 * logger writing into the archive and no file at the active path.
 */
object NodeLog {

    const val MAX_BYTES: Long = 2L * 1024L * 1024L
    const val ARCHIVE_COUNT: Int = 2

    private const val FILE_NAME = "ldk-node.log"

    /** The log file the node writes, creating nothing. */
    fun file(context: Context): File = File(File(context.filesDir, "logs"), FILE_NAME)

    /**
     * Rotates [log] when it is larger than [maxBytes].
     *
     * The active file becomes `.1` and every archive shifts one number up, dropping the
     * oldest. Returns true when a rotation happened.
     */
    fun rotateIfNeeded(
        log: File,
        maxBytes: Long = MAX_BYTES,
        archiveCount: Int = ARCHIVE_COUNT
    ): Boolean {
        if (maxBytes <= 0L || archiveCount < 1) return false
        if (!log.isFile || log.length() <= maxBytes) return false

        var index = archiveCount
        while (index >= 1) {
            val target = File(log.path + "." + index)
            if (index == archiveCount && target.exists()) {
                target.delete()
            }
            val source = if (index == 1) log else File(log.path + "." + (index - 1))
            if (source.exists()) {
                source.renameTo(target)
            }
            index--
        }
        return true
    }
}
