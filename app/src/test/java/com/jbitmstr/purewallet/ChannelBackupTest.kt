package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.BackupException
import com.jbitmstr.purewallet.core.BackupFile
import com.jbitmstr.purewallet.core.BackupManifest
import com.jbitmstr.purewallet.core.ChannelBackup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers the encrypted channel backup: the AES-256-GCM envelope, the archive layout and the
 * file collection, plus every way a wrong phrase or a damaged file must be refused.
 */
class ChannelBackupTest {

    private val mnemonic =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val otherMnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private fun manifest(network: String = "mainnet") = BackupManifest(
        network = network,
        createdAtEpochSecs = 1_763_750_000L,
        nodeId = "02aa" + "bb".repeat(30),
        channelCount = 2,
        appVersion = "1.1.2",
        wordCount = 12
    )

    private fun sampleFiles() = listOf(
        BackupFile("channel_manager", byteArrayOf(1, 2, 3, 4)),
        BackupFile("channel_monitor/0a0b", "monitor-state".toByteArray()),
        BackupFile("keys_seed", ByteArray(64) { it.toByte() })
    )

    private fun tempDir(prefix: String): File =
        File.createTempFile(prefix, "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    // --------------------------------------------------------------- envelope

    @Test
    fun encryptThenDecryptReturnsTheArchive() {
        val archive = ChannelBackup.buildArchive(manifest(), sampleFiles())
        val sealed = ChannelBackup.encrypt(mnemonic, archive)
        assertTrue(ChannelBackup.looksLikeBackup(sealed))
        assertArrayEquals(archive, ChannelBackup.decrypt(mnemonic, sealed))
    }

    @Test
    fun encryptedFileRevealsNoPlaintext() {
        val marker = "SECRET-CHANNEL-STATE"
        val archive = ChannelBackup.buildArchive(
            manifest(),
            listOf(BackupFile("channel_manager", marker.toByteArray()))
        )
        val sealed = ChannelBackup.encrypt(mnemonic, archive)
        val asText = String(sealed, Charsets.ISO_8859_1)
        assertFalse(asText.contains(marker))
        assertFalse(asText.contains("mainnet"))
        assertFalse(asText.contains("ZWBK-data"))
    }

    @Test
    fun wrongMnemonicFailsCleanly() {
        val archive = ChannelBackup.buildArchive(manifest(), sampleFiles())
        val sealed = ChannelBackup.encrypt(mnemonic, archive)
        val failure = runCatching { ChannelBackup.decrypt(otherMnemonic, sealed) }.exceptionOrNull()
        assertTrue(failure is BackupException)
        assertTrue(failure?.message?.contains("recovery phrase") == true)
    }

    @Test
    fun mnemonicCaseAndSpacingDoNotChangeTheKey() {
        val messy = "  LEGAL   winner\nthank year wave sausage worth useful legal winner thank YELLOW "
        val archive = ChannelBackup.buildArchive(manifest(), sampleFiles())
        val sealed = ChannelBackup.encrypt(mnemonic, archive)
        assertArrayEquals(archive, ChannelBackup.decrypt(messy, sealed))
        assertArrayEquals(ChannelBackup.deriveKey(mnemonic), ChannelBackup.deriveKey(messy))
    }

    @Test
    fun derivedKeyIsA256BitSecretThatDiffersPerPhrase() {
        val key = ChannelBackup.deriveKey(mnemonic)
        assertEquals(32, key.size)
        assertArrayEquals(key, ChannelBackup.deriveKey(mnemonic))
        assertFalse(key.contentEquals(ChannelBackup.deriveKey(otherMnemonic)))
    }

    @Test
    fun anyFlippedCiphertextByteIsRejected() {
        val archive = ChannelBackup.buildArchive(manifest(), sampleFiles())
        val sealed = ChannelBackup.encrypt(mnemonic, archive)
        for (position in intArrayOf(17, sealed.size / 2, sealed.size - 1)) {
            val damaged = sealed.copyOf()
            damaged[position] = (damaged[position].toInt() xor 0x01).toByte()
            val failure = runCatching { ChannelBackup.decrypt(mnemonic, damaged) }.exceptionOrNull()
            assertTrue("byte $position should be authenticated", failure is BackupException)
        }
    }

    @Test
    fun headerChangesAreRejected() {
        val archive = ChannelBackup.buildArchive(manifest(), sampleFiles())
        val sealed = ChannelBackup.encrypt(mnemonic, archive)

        val badMagic = sealed.copyOf().also { it[0] = 'X'.code.toByte() }
        assertTrue(runCatching { ChannelBackup.decrypt(mnemonic, badMagic) }.exceptionOrNull() is BackupException)

        val badNonce = sealed.copyOf().also { it[8] = (it[8].toInt() xor 0x01).toByte() }
        assertTrue(runCatching { ChannelBackup.decrypt(mnemonic, badNonce) }.exceptionOrNull() is BackupException)

        val futureVersion = sealed.copyOf().also { it[4] = 9.toByte() }
        assertTrue(runCatching { ChannelBackup.decrypt(mnemonic, futureVersion) }.exceptionOrNull() is BackupException)
    }

    @Test
    fun shortOrForeignFilesAreRejected() {
        assertFalse(ChannelBackup.looksLikeBackup(ByteArray(0)))
        assertFalse(ChannelBackup.looksLikeBackup("hello".toByteArray()))
        assertTrue(runCatching { ChannelBackup.decrypt(mnemonic, ByteArray(8)) }.exceptionOrNull() is BackupException)
        assertTrue(
            runCatching { ChannelBackup.decrypt(mnemonic, "not a backup at all".toByteArray()) }
                .exceptionOrNull() is BackupException
        )
    }

    @Test
    fun noncesAreFreshForEveryExport() {
        val archive = ChannelBackup.buildArchive(manifest(), sampleFiles())
        val first = ChannelBackup.encrypt(mnemonic, archive)
        val second = ChannelBackup.encrypt(mnemonic, archive)
        assertNotEquals(first[5], second[5])
        assertArrayEquals(archive, ChannelBackup.decrypt(mnemonic, second))
    }

    // --------------------------------------------------------------- archive

    @Test
    fun archiveRoundTripKeepsManifestAndFiles() {
        val archive = ChannelBackup.buildArchive(manifest("signet"), sampleFiles())
        val payload = ChannelBackup.readArchive(archive)
        assertEquals("signet", payload.manifest.network)
        assertEquals(1_763_750_000L, payload.manifest.createdAtEpochSecs)
        assertEquals(2, payload.manifest.channelCount)
        assertEquals("1.1.2", payload.manifest.appVersion)
        assertEquals(12, payload.manifest.wordCount)
        assertEquals(sampleFiles().map { it.path }.sorted(), payload.files.map { it.path })
        assertArrayEquals(
            sampleFiles().first { it.path == "keys_seed" }.bytes,
            payload.files.first { it.path == "keys_seed" }.bytes
        )
    }

    @Test
    fun manifestIsParsedFromItsOwnText() {
        val text = ChannelBackup.manifestText(manifest("testnet"))
        val parsed = ChannelBackup.parseManifest(text)
        assertEquals("testnet", parsed.network)
        assertEquals(1_763_750_000L, parsed.createdAtEpochSecs)
        assertEquals(2, parsed.channelCount)
    }

    @Test
    fun archiveWithoutManifestIsRefused() {
        val buffer = java.io.ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("data/channel_manager"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val failure = runCatching { ChannelBackup.readArchive(buffer.toByteArray()) }.exceptionOrNull()
        assertTrue(failure is BackupException)
        assertTrue(failure?.message?.contains("manifest") == true)
    }

    @Test
    fun archiveWithNoNodeDataIsRefused() {
        val buffer = java.io.ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.txt"))
            zip.write(ChannelBackup.manifestText(manifest()).toByteArray())
            zip.closeEntry()
        }
        assertTrue(runCatching { ChannelBackup.readArchive(buffer.toByteArray()) }.exceptionOrNull() is BackupException)
    }

    @Test
    fun zipSlipPathsAreRefused() {
        assertTrue(runCatching { ChannelBackup.safeRelativePath("../escape") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ChannelBackup.safeRelativePath("a/../../escape") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("channel_manager", ChannelBackup.safeRelativePath("/channel_manager"))
        assertEquals("channel_monitor/0a0b", ChannelBackup.safeRelativePath("channel_monitor\\0a0b"))

        val buffer = java.io.ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.txt"))
            zip.write(ChannelBackup.manifestText(manifest()).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("data/../../evil"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        assertTrue(runCatching { ChannelBackup.readArchive(buffer.toByteArray()) }.exceptionOrNull() is BackupException)
    }

    @Test
    fun suggestedNameCarriesNetworkAndDate() {
        assertEquals(
            "zerowallet-mainnet-2025-11-21.zwbackup",
            ChannelBackup.suggestedFileName("mainnet", 1_763_750_000L)
        )
        assertTrue(ChannelBackup.suggestedFileName("signet", 0L).startsWith("zerowallet-signet-19"))
    }

    // --------------------------------------------------------------- files

    @Test
    fun collectFilesWalksTheNodeDirectoryAndSkipsNoise() {
        val root = tempDir("zwbackup")
        try {
            File(root, "channel_manager").writeBytes(byteArrayOf(9))
            File(root, "channel_monitor").mkdirs()
            File(root, "channel_monitor/0a0b").writeBytes(byteArrayOf(7, 7))
            File(root, "wallet").mkdirs()
            File(root, "wallet/wallet.sqlite").writeBytes(byteArrayOf(5))
            File(root, "logs").mkdirs()
            File(root, "logs/ldk-node.log").writeBytes("log".toByteArray())
            File(root, "wallet.db.lock").writeBytes(byteArrayOf(0))

            val files = ChannelBackup.collectFiles(root)
            assertEquals(
                listOf("channel_manager", "channel_monitor/0a0b", "wallet/wallet.sqlite"),
                files.map { it.path }
            )
            assertArrayEquals(byteArrayOf(7, 7), files.first { it.path == "channel_monitor/0a0b" }.bytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeFilesRecreatesTheLayout() {
        val source = tempDir("zwbackup-src")
        val target = tempDir("zwbackup-dst")
        try {
            File(source, "channel_monitor").mkdirs()
            File(source, "channel_monitor/0a0b").writeBytes(byteArrayOf(1, 2))
            File(source, "channel_manager").writeBytes(byteArrayOf(3))

            val files = ChannelBackup.collectFiles(source)
            ChannelBackup.writeFiles(target, files)

            assertArrayEquals(byteArrayOf(1, 2), File(target, "channel_monitor/0a0b").readBytes())
            assertArrayEquals(byteArrayOf(3), File(target, "channel_manager").readBytes())
        } finally {
            source.deleteRecursively()
            target.deleteRecursively()
        }
    }

    @Test
    fun aWholeBackupRoundTripsThroughTheFilesystem() {
        val source = tempDir("zwbackup-full")
        val restored = tempDir("zwbackup-restore")
        try {
            File(source, "channel_monitor").mkdirs()
            File(source, "channel_monitor/0a0b").writeBytes(ByteArray(300) { (it % 251).toByte() })
            File(source, "channel_manager").writeBytes("state".toByteArray())

            val archive = ChannelBackup.buildArchive(manifest(), ChannelBackup.collectFiles(source))
            val sealed = ChannelBackup.encrypt(mnemonic, archive)
            // A file that travels as opaque bytes, the way it would through Google Drive.
            val onDisk = File.createTempFile("channel", ChannelBackup.FILE_SUFFIX)
            FileOutputStream(onDisk).use { it.write(sealed) }

            val payload = ChannelBackup.readArchive(ChannelBackup.decrypt(mnemonic, onDisk.readBytes()))
            ChannelBackup.writeFiles(restored, payload.files)

            assertArrayEquals(
                File(source, "channel_monitor/0a0b").readBytes(),
                File(restored, "channel_monitor/0a0b").readBytes()
            )
            assertEquals("state", File(restored, "channel_manager").readText())
            onDisk.delete()
        } finally {
            source.deleteRecursively()
            restored.deleteRecursively()
        }
    }
}
