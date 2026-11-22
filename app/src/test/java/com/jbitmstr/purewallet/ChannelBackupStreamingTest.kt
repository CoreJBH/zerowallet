package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.BackupException
import com.jbitmstr.purewallet.core.BackupManifest
import com.jbitmstr.purewallet.core.ChannelBackup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The streaming backup path: a node store goes to a sealed file and back without ever being
 * held in memory whole.
 *
 * The format itself is pinned by `ChannelBackupTest`; these tests cover the four functions
 * the app now calls - [ChannelBackup.writeArchive], [ChannelBackup.encryptStream],
 * [ChannelBackup.decryptStream] and [ChannelBackup.extractTo] - and the limits that used to be
 * checked on an in-memory copy.
 */
class ChannelBackupStreamingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val phrase =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private fun manifest(channels: Int = 2) = BackupManifest(
        network = "mainnet",
        createdAtEpochSecs = 1_763_000_000L,
        nodeId = "0247a1f8",
        channelCount = channels,
        appVersion = "1.2.0",
        wordCount = 12
    )

    /** A node store on disk: the files a backup is supposed to carry, and the ones it is not. */
    private fun store(sizeBytes: Int = 0): File {
        val root = folder.newFolder("store")
        File(root, "channel_manager").writeText("channel manager state")
        File(root, "monitors").mkdirs()
        File(root, "monitors/0001").writeText("monitor 1")
        File(root, "logs").mkdirs()
        File(root, "logs/ldk-node.log").writeText("a log line the backup leaves out")
        File(root, "channel_manager.lock").writeText("a lock file the backup leaves out")
        if (sizeBytes > 0) {
            // A file big enough that an in-memory implementation would show up in a profile.
            File(root, "keys_seed").outputStream().use { out ->
                val piece = ByteArray(64 * 1024) { index -> (index % 251).toByte() }
                var written = 0
                while (written < sizeBytes) {
                    val size = minOf(piece.size, sizeBytes - written)
                    out.write(piece, 0, size)
                    written += size
                }
            }
        }
        return root
    }

    private fun writeArchive(root: File): ByteArray {
        val out = ByteArrayOutputStream()
        ChannelBackup.writeArchive(manifest(), root, out)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ round trip

    @Test
    fun aStoreGoesOutAndComesBackFileForFile() {
        val root = store(sizeBytes = 2 * 1024 * 1024)
        val archive = writeArchive(root)

        val sealed = ByteArrayOutputStream()
        val plainBytes = ChannelBackup.encryptStream(
            phrase,
            ByteArrayInputStream(archive),
            sealed
        )
        assertEquals(archive.size.toLong(), plainBytes)

        val reopened = ByteArrayOutputStream()
        val written = ChannelBackup.decryptStream(
            phrase,
            ByteArrayInputStream(sealed.toByteArray()),
            reopened
        )
        assertEquals(archive.size.toLong(), written)
        assertArrayEquals(archive, reopened.toByteArray())

        val restored = folder.newFolder("restored")
        val readManifest = ChannelBackup.extractTo(ByteArrayInputStream(reopened.toByteArray()), restored)
        assertEquals(manifest(), readManifest)
        assertEquals("channel manager state", File(restored, "channel_manager").readText())
        assertEquals("monitor 1", File(restored, "monitors/0001").readText())
        // The files a backup must not carry: a log line names peers and a lock file confuses
        // the node it is restored next to.
        assertFalse(File(restored, "logs/ldk-node.log").exists())
        assertFalse(File(restored, "channel_manager.lock").exists())
        assertTrue(File(restored, "keys_seed").length() == 2L * 1024 * 1024)
    }

    @Test
    fun theManifestAloneCanBeReadFromASealedFile() {
        val archive = writeArchive(store())
        val sealed = ByteArrayOutputStream().also { out ->
            ChannelBackup.encryptStream(phrase, ByteArrayInputStream(archive), out)
        }
        val reopened = ByteArrayOutputStream().also { out ->
            ChannelBackup.decryptStream(phrase, ByteArrayInputStream(sealed.toByteArray()), out)
        }

        val read = ChannelBackup.readManifest(ByteArrayInputStream(reopened.toByteArray()))

        assertEquals(2, read.channelCount)
        assertEquals("0247a1f8", read.nodeId)
    }

    // ----------------------------------------------------------------- refusals

    @Test
    fun anotherPhraseOpensNothing() {
        val sealed = ByteArrayOutputStream().also { out ->
            ChannelBackup.encryptStream(phrase, ByteArrayInputStream(writeArchive(store())), out)
        }
        val other =
            "legal winner thank year wave sausage worth useful legal winner thank yellow"

        val failure = runCatching {
            ChannelBackup.decryptStream(other, ByteArrayInputStream(sealed.toByteArray()), ByteArrayOutputStream())
        }.exceptionOrNull()

        assertTrue("expected a BackupException, got $failure", failure is BackupException)
    }

    @Test
    fun theHeaderNamesTheFormatTheFileWasWrittenIn() {
        val sealed = ByteArrayOutputStream().also { out ->
            ChannelBackup.encryptStream(phrase, ByteArrayInputStream(writeArchive(store())), out)
        }

        assertEquals(
            ChannelBackup.FORMAT_VERSION,
            ChannelBackup.formatOf(ByteArrayInputStream(sealed.toByteArray()))
        )
        assertNull(ChannelBackup.formatOf(ByteArrayInputStream("not a backup".toByteArray())))
    }

    @Test
    fun aStreamThatExpandsPastTheLimitIsRefused() {
        val sealed = ByteArrayOutputStream().also { out ->
            ChannelBackup.encryptStream(phrase, ByteArrayInputStream(writeArchive(store())), out)
        }

        val failure = runCatching {
            ChannelBackup.decryptStream(
                phrase,
                ByteArrayInputStream(sealed.toByteArray()),
                ByteArrayOutputStream(),
                limit = 32
            )
        }.exceptionOrNull()

        assertTrue("expected a BackupException, got $failure", failure is BackupException)
    }

    @Test
    fun anEntryThatEscapesTheTargetDirectoryIsRefused() {
        // A crafted archive: the path inside it points above the directory it is unpacked
        // into. The wallet writes a restore into a staging directory it made, and this is what
        // stops a file from landing outside it.
        val archive = ByteArrayOutputStream().also { buffer ->
            java.util.zip.ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.txt"))
                zip.write(ChannelBackup.manifestText(manifest()).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("data/../../escaped"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val target = folder.newFolder("staging")
        val failure = runCatching {
            ChannelBackup.extractTo(ByteArrayInputStream(archive), target)
        }.exceptionOrNull()

        assertTrue("expected a BackupException, got $failure", failure is BackupException)
        assertFalse(File(target.parentFile, "escaped").exists())
    }

    @Test
    fun anEmptyStoreIsNotABackup() {
        val empty = folder.newFolder("empty")
        val failure = runCatching { writeArchive(empty) }.exceptionOrNull()
        assertTrue("expected a BackupException, got $failure", failure is BackupException)
    }

    @Test
    fun aSealedFileStreamsBackToDiskPieceByPiece() {
        // The shape the app uses: a file in, a file out, nothing in between.
        val root = store(sizeBytes = 3 * 1024 * 1024)
        val sealedFile = File(folder.newFolder("cache"), "zerowallet-mainnet.zwbackup")
        sealedFile.outputStream().use { out ->
            val archive = File.createTempFile("archive", ".zip", folder.root)
            archive.outputStream().use { channel ->
                ChannelBackup.writeArchive(manifest(), root, channel)
            }
            archive.inputStream().use { input -> ChannelBackup.encryptStream(phrase, input, out) }
            Files.delete(archive.toPath())
        }
        val scratch = File(folder.newFolder("scratch"), "restore.zip")
        scratch.outputStream().use { out -> sealedFile.inputStream().use { input ->
            ChannelBackup.decryptStream(phrase, input, out)
        } }

        val restored = folder.newFolder("restored-file")
        val read = scratch.inputStream().use { ChannelBackup.extractTo(it, restored) }

        assertEquals(2, read.channelCount)
        assertEquals("channel manager state", File(restored, "channel_manager").readText())
    }
}
