package com.jbitmstr.purewallet.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One file inside a channel backup archive, named by its path relative to the node directory. */
class BackupFile(val path: String, val bytes: ByteArray)

/** What a backup says about itself. Kept inside the ciphertext, so the file itself reveals nothing. */
data class BackupManifest(
    val network: String,
    val createdAtEpochSecs: Long,
    val nodeId: String,
    val channelCount: Int,
    val appVersion: String,
    val wordCount: Int
)

/** A decrypted backup: its manifest and the node files it carries. */
class BackupPayload(val manifest: BackupManifest, val files: List<BackupFile>)

/** The archive could not be opened: wrong phrase, damaged file or an unknown format. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Encrypted channel backup for the LDK node store.
 *
 * The node's own directory (channel manager, channel monitors, on-chain wallet, keys seed)
 * is packed into a zip archive and sealed with AES-256-GCM. The key is derived from the
 * BIP-39 recovery phrase alone, with SHA-256 over a domain separator and the normalized
 * words, so the file can sit in Google Drive or anywhere else: without the 12/24 words it
 * is indistinguishable from random bytes, and the mnemonic already has 128 bits of entropy,
 * which makes the single hash as unguessable as the phrase itself.
 *
 * Nothing here touches Android APIs, so the format is covered by plain JVM unit tests.
 */
object ChannelBackup {

    const val FORMAT_VERSION = 1
    const val FILE_SUFFIX = ".zwbackup"
    const val MIME_TYPE = "application/octet-stream"

    /** Nonce size for AES-GCM, in bytes. */
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32
    private const val MAGIC = "ZWBK"
    private const val HEADER_BYTES = 4 + 1 + NONCE_BYTES
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Domain separator so the key cannot be reused as anything else derived from the phrase. */
    private const val KEY_LABEL = "ZeroWallet-Channel-Backup-v1"

    private const val MANIFEST_ENTRY = "manifest.txt"
    private const val DATA_PREFIX = "data/"
    private const val MANIFEST_FORMAT = 1

    // ------------------------------------------------------------------ encryption

    /**
     * Seals [archive] with a key derived from [mnemonic].
     *
     * Layout: `ZWBK` + version byte + 12-byte nonce + AES-256-GCM ciphertext and tag.
     * The header is authenticated as additional data, so no byte of it can be altered.
     */
    fun encrypt(mnemonic: String, archive: ByteArray, nonce: ByteArray? = null): ByteArray {
        val iv = nonce ?: ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        require(iv.size == NONCE_BYTES) { "Nonce must be $NONCE_BYTES bytes" }
        require(archive.isNotEmpty()) { "Nothing to encrypt" }

        val key = deriveKey(mnemonic)
        try {
            val header = header(iv)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv)
            )
            cipher.updateAAD(header)
            val sealed = cipher.doFinal(archive)
            return header + sealed
        } finally {
            key.fill(0)
        }
    }

    /**
     * Opens a backup made by [encrypt].
     *
     * @throws BackupException when the file is not a backup, was damaged, or the phrase
     * that unlocks it is different from the one it was sealed with.
     */
    fun decrypt(mnemonic: String, backup: ByteArray): ByteArray {
        if (backup.size <= HEADER_BYTES) throw BackupException("This file is too small to be a backup")
        if (!looksLikeBackup(backup)) throw BackupException("This is not a ZeroWallet backup file")
        val version = backup[4].toInt() and 0xFF
        if (version != FORMAT_VERSION) {
            throw BackupException("This backup uses format $version, which this version of ZeroWallet cannot read")
        }

        val header = backup.copyOfRange(0, HEADER_BYTES)
        val iv = backup.copyOfRange(5, HEADER_BYTES)
        val sealed = backup.copyOfRange(HEADER_BYTES, backup.size)

        val key = deriveKey(mnemonic)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv)
            )
            cipher.updateAAD(header)
            return cipher.doFinal(sealed)
        } catch (t: Throwable) {
            throw BackupException(
                "Could not unlock this backup. It needs the same recovery phrase it was saved with.",
                t
            )
        } finally {
            key.fill(0)
        }
    }

    /** True when [bytes] starts with this format's magic and version byte. */
    fun looksLikeBackup(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_BYTES) return false
        if (MAGIC.indices.any { bytes[it] != MAGIC[it].code.toByte() }) return false
        return (bytes[4].toInt() and 0xFF) in 1..FORMAT_VERSION
    }

    /**
     * AES-256 key for a phrase: SHA-256 of the key label followed by the normalized mnemonic.
     * The caller is responsible for wiping nothing here; [encrypt] and [decrypt] zero their copy.
     */
    fun deriveKey(mnemonic: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(KEY_LABEL.toByteArray(Charsets.UTF_8))
        digest.update(Bip39.normalize(mnemonic).toByteArray(Charsets.UTF_8))
        return digest.digest().copyOf(KEY_BYTES)
    }

    private fun header(iv: ByteArray): ByteArray {
        val out = ByteArray(HEADER_BYTES)
        MAGIC.forEachIndexed { index, char -> out[index] = char.code.toByte() }
        out[4] = FORMAT_VERSION.toByte()
        iv.copyInto(out, 5)
        return out
    }

    // ------------------------------------------------------------------ archive

    /** Packs [manifest] and [files] into a zip archive ready for [encrypt]. */
    fun buildArchive(manifest: BackupManifest, files: List<BackupFile>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestText(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (file in files.sortedBy { it.path }) {
                val safe = safeRelativePath(file.path)
                zip.putNextEntry(ZipEntry(DATA_PREFIX + safe))
                zip.write(file.bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    /** Reads an archive made by [buildArchive]. */
    fun readArchive(archive: ByteArray): BackupPayload {
        var manifest: BackupManifest? = null
        val files = ArrayList<BackupFile>()
        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        val bytes = zip.readBytes()
                        if (name == MANIFEST_ENTRY) {
                            manifest = parseManifest(String(bytes, Charsets.UTF_8))
                        } else if (name.startsWith(DATA_PREFIX)) {
                            val path = name.removePrefix(DATA_PREFIX)
                            if (path.isNotEmpty()) files.add(BackupFile(safeRelativePath(path), bytes))
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (t: Throwable) {
            throw BackupException("This backup archive is damaged", t)
        }
        val parsed = manifest ?: throw BackupException("This backup has no manifest")
        if (parsed.network.isBlank()) throw BackupException("This backup does not name a network")
        if (files.isEmpty()) throw BackupException("This backup carries no node data")
        return BackupPayload(parsed, files)
    }

    /** Rejects absolute paths and `..` segments, so a crafted archive cannot write outside the node directory. */
    fun safeRelativePath(path: String): String {
        val cleaned = path.replace('\\', '/').trimStart('/')
        val parts = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.isNotEmpty()) { "Empty path in backup" }
        require(parts.none { it == ".." }) { "Backup path escapes the node directory: $path" }
        return parts.joinToString("/")
    }

    /** The text manifest that rides inside the encrypted archive. */
    fun manifestText(manifest: BackupManifest): String = buildString {
        append("format=").append(MANIFEST_FORMAT).append('\n')
        append("network=").append(manifest.network).append('\n')
        append("created=").append(manifest.createdAtEpochSecs).append('\n')
        append("node_id=").append(manifest.nodeId).append('\n')
        append("channels=").append(manifest.channelCount).append('\n')
        append("app_version=").append(manifest.appVersion).append('\n')
        append("words=").append(manifest.wordCount).append('\n')
    }

    /** Parses [manifestText]. Unknown keys are ignored so a newer writer stays readable. */
    fun parseManifest(text: String): BackupManifest {
        val values = text.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.take(separator) to line.substring(separator + 1)
            }
            .toMap()
        val format = values["format"]?.toIntOrNull() ?: MANIFEST_FORMAT
        if (format != MANIFEST_FORMAT) throw BackupException("Unsupported backup contents: $format")
        return BackupManifest(
            network = values["network"]?.trim().orEmpty(),
            createdAtEpochSecs = values["created"]?.toLongOrNull() ?: 0L,
            nodeId = values["node_id"].orEmpty(),
            channelCount = values["channels"]?.toIntOrNull() ?: 0,
            appVersion = values["app_version"].orEmpty(),
            wordCount = values["words"]?.toIntOrNull() ?: 0
        )
    }

    // ------------------------------------------------------------------ files

    /**
     * Every file under [directory] as backup entries, with paths relative to it.
     *
     * Log files, temporary files and lock files are left out: they are not part of the
     * wallet, and a lock file restored next to a fresh node would only confuse it.
     */
    fun collectFiles(directory: File): List<BackupFile> {
        if (!directory.isDirectory) return emptyList()
        val root = directory.absoluteFile
        val out = ArrayList<BackupFile>()
        root.walkTopDown()
            .onEnter { it.name != "logs" }
            .filter { it.isFile && it.length() >= 0 }
            .forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val name = file.name
                if (name.endsWith(".lock") || name.endsWith(".tmp") || name.startsWith(".")) return@forEach
                out.add(BackupFile(relative, file.readBytes()))
            }
        return out.sortedBy { it.path }
    }

    /** Writes backup entries under [directory], creating parents. */
    fun writeFiles(directory: File, files: List<BackupFile>) {
        for (file in files) {
            val target = File(directory, safeRelativePath(file.path))
            target.parentFile?.mkdirs()
            target.writeBytes(file.bytes)
        }
    }

    /** A readable, date-stamped file name such as `zerowallet-mainnet-2026-11-22.zwbackup`. */
    fun suggestedFileName(network: String, epochSecs: Long): String {
        val date = java.time.Instant.ofEpochSecond(epochSecs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
        return "zerowallet-$network-$date$FILE_SUFFIX"
    }
}
