package com.corejbh.zerowallet

import com.corejbh.zerowallet.core.NodeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Size-capped rotation of the LDK log file. */
class NodeLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun logFile(bytes: Int): File =
        folder.newFile("ldk-node.log").apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun smallLogIsLeftAlone() {
        val log = logFile(1024)
        assertFalse(NodeLog.rotateIfNeeded(log, maxBytes = 4096, archiveCount = 2))
        assertTrue(log.exists())
        assertFalse(File(log.path + ".1").exists())
    }

    @Test
    fun logExactlyAtTheCapIsLeftAlone() {
        val log = logFile(4096)
        assertFalse(NodeLog.rotateIfNeeded(log, maxBytes = 4096, archiveCount = 2))
        assertEquals(0, log.parentFile?.listFiles()?.count { it.name.startsWith("ldk-node.log.") } ?: 0)
    }

    @Test
    fun oversizedLogIsRotated() {
        val log = logFile(5000)
        assertTrue(NodeLog.rotateIfNeeded(log, maxBytes = 4096, archiveCount = 2))
        assertFalse("the active log is gone until the node writes again", log.exists())
        val archive = File(log.path + ".1")
        assertTrue(archive.exists())
        assertEquals(5000, archive.length().toInt())
    }

    @Test
    fun olderArchivesShiftUpAndTheOldestIsDropped() {
        val log = logFile(5000)
        val first = File(log.path + ".1").apply { writeBytes(ByteArray(11)) }
        val second = File(log.path + ".2").apply { writeBytes(ByteArray(22)) }

        assertTrue(NodeLog.rotateIfNeeded(log, maxBytes = 4096, archiveCount = 2))

        assertEquals(11L, File(log.path + ".2").length())
        assertEquals(5000L, first.length())
        assertFalse(second.exists() && second.length() == 22L)
    }

    @Test
    fun missingLogIsNotAnError() {
        val log = File(folder.root, "ldk-node.log")
        assertFalse(NodeLog.rotateIfNeeded(log, maxBytes = 4096, archiveCount = 2))
    }

    @Test
    fun invalidArgumentsDoNothing() {
        val log = logFile(5000)
        assertFalse(NodeLog.rotateIfNeeded(log, maxBytes = 0, archiveCount = 2))
        assertFalse(NodeLog.rotateIfNeeded(log, maxBytes = 4096, archiveCount = 0))
        assertTrue(log.exists())
    }
}
