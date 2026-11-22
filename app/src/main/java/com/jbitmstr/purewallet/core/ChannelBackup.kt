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
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
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
 * BIP-39 recovery phrase alone, so the file can sit in Google Drive or anywhere else:
 * without the 12/24 words it is indistinguishable from random bytes, and the mnemonic
 * already has 128 bits of entropy.
 *
 * The key comes from PBKDF2-HMAC-SHA256 with 210,000 iterations and a random 16-byte salt
 * stored in the header as of format 3 - the count OWASP recommends for PBKDF2-SHA256, and the
 * work factor matters here because the phrase is the only secret protecting the file. The
 * salt makes each backup's key different even for the same phrase, so a precomputed table
 * cannot be reused across files. Format 2 used HKDF-SHA256, and format 1 one plain SHA-256
 * over the label and the phrase; both still open, because a reader tells them apart by the
 * version byte, while every new backup is written as format 3.
 *
 * Opening a backup is bounded: a maximum file size, a maximum number of entries and a
 * maximum uncompressed total, all checked while the stream is read rather than from the
 * sizes the archive claims. A crafted or damaged file therefore cannot expand into the
 * phone's memory or storage.
 *
 * Nothing here touches Android APIs, so the format is covered by plain JVM unit tests.
 */
object ChannelBackup {

    /** The format this build writes: PBKDF2-HMAC-SHA256 with a random salt. */
    const val FORMAT_VERSION = 3

    /** Format 2 (HKDF-SHA256), still readable because backups outlive app versions. */
    const val HKDF_FORMAT_VERSION = 2

    /** Format 1 (single SHA-256), the oldest format this wallet still opens. */
    const val LEGACY_FORMAT_VERSION = 1

    /** Iterations of PBKDF2-HMAC-SHA256 for format 3 (OWASP's 2023 recommendation). */
    const val KDF_ITERATIONS = 210_000

    /** Size of the random salt in a format 3 header. */
    const val SALT_BYTES = 16

    /** Largest sealed file this wallet will open (64 MiB). */
    const val MAX_BACKUP_BYTES = 64 * 1024 * 1024

    /** Largest number of files inside one archive. */
    const val MAX_ENTRIES = 4096

    /** Largest single plaintext file inside one archive (64 MiB). */
    const val MAX_ENTRY_BYTES = 64 * 1024 * 1024

    /** Largest unpadded total of all plaintext files (128 MiB). */
    const val MAX_ARCHIVE_BYTES = 128 * 1024 * 1024

    const val FILE_SUFFIX = ".zwbackup"
    const val MIME_TYPE = "application/octet-stream"

    /** Nonce size for AES-GCM, in bytes. */
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32
    private const val KEY_BITS = KEY_BYTES * 8
    private const val MAGIC = "ZWBK"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

    /**
     * Header size per format: `ZWBK`, the version byte, the format 3 salt when there is one,
     * and the nonce. A reader needs the version byte first, which sits at a fixed position
     * in every format.
     */
    private fun headerBytes(version: Int): Int =
        MAGIC.length + 1 + (if (version >= 3) SALT_BYTES else 0) + NONCE_BYTES

    /**
     * Domain separators so the key cannot be reused as anything else derived from the
     * phrase. The v2 label is the HKDF salt, the v1 label was hashed with the phrase.
     */
    private const val KEY_LABEL = "ZeroWallet-Channel-Backup-v1"
    private const val HKDF_SALT = "ZeroWallet-Channel-Backup-v2"
    private const val HKDF_INFO = "channel-backup-key"

    private const val MANIFEST_ENTRY = "manifest.txt"
    private const val DATA_PREFIX = "data/"
    private const val MANIFEST_FORMAT = 1

    // ------------------------------------------------------------------ encryption

    /**
     * Seals [archive] with a key derived from [mnemonic].
     *
     * Layout: `ZWBK` + version byte + 16-byte salt + 12-byte nonce + AES-256-GCM ciphertext
     * and tag. The whole header is authenticated as additional data, so neither the salt nor
     * the nonce can be altered without the tag failing.
     */
    fun encrypt(
        mnemonic: String,
        archive: ByteArray,
        nonce: ByteArray? = null,
        salt: ByteArray? = null
    ): ByteArray {
        val iv = nonce ?: ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        require(iv.size == NONCE_BYTES) { "Nonce must be $NONCE_BYTES bytes" }
        val formatSalt = salt ?: ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        require(formatSalt.size == SALT_BYTES) { "Salt must be $SALT_BYTES bytes" }
        require(archive.isNotEmpty()) { "Nothing to encrypt" }

        val key = deriveKey(mnemonic, FORMAT_VERSION, formatSalt)
        try {
            val header = header(FORMAT_VERSION, formatSalt, iv)
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
        if (backup.size <= headerBytes(LEGACY_FORMAT_VERSION)) {
            throw BackupException("This file is too small to be a backup")
        }
        if (backup.size > MAX_BACKUP_BYTES) {
            throw BackupException("This backup is larger than ZeroWallet opens ($MAX_BACKUP_BYTES bytes)")
        }
        if (!looksLikeBackup(backup)) throw BackupException("This is not a ZeroWallet backup file")
        val version = backup[4].toInt() and 0xFF
        if (version < LEGACY_FORMAT_VERSION || version > FORMAT_VERSION) {
            throw BackupException("This backup uses format $version, which this version of ZeroWallet cannot read")
        }

        val headerSize = headerBytes(version)
        if (backup.size <= headerSize) throw BackupException("This file is too small to be a backup")
        val header = backup.copyOfRange(0, headerSize)
        val salt = if (version >= 3) backup.copyOfRange(5, 5 + SALT_BYTES) else null
        val ivStart = 5 + (salt?.size ?: 0)
        val iv = backup.copyOfRange(ivStart, headerSize)
        val sealed = backup.copyOfRange(headerSize, backup.size)

        val key = deriveKey(mnemonic, version, salt)
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

    /** True when [bytes] starts with this format's magic and a version this build reads. */
    fun looksLikeBackup(bytes: ByteArray): Boolean {
        if (bytes.size < headerBytes(LEGACY_FORMAT_VERSION)) return false
        if (MAGIC.indices.any { bytes[it] != MAGIC[it].code.toByte() }) return false
        return (bytes[4].toInt() and 0xFF) in LEGACY_FORMAT_VERSION..FORMAT_VERSION
    }

    /**
     * AES-256 key for a phrase.
     *
     * Format 3 (current): PBKDF2-HMAC-SHA256 over the normalized words, with the file's own
     * salt and [KDF_ITERATIONS] rounds. Format 2: HKDF-SHA256 with the domain label as salt.
     * Format 1: a single SHA-256 over the label and the normalized words.
     *
     * [salt] is required for format 3 and ignored by the older formats, which carry no salt.
     */
    fun deriveKey(mnemonic: String, version: Int = FORMAT_VERSION, salt: ByteArray? = null): ByteArray =
        when {
            version >= 3 -> pbkdf2Key(
                mnemonic,
                salt ?: throw IllegalArgumentException("Format $version needs the salt from its header")
            )
            version == 2 -> hkdfKey(mnemonic)
            else -> legacyKey(mnemonic)
        }

    private fun pbkdf2Key(mnemonic: String, salt: ByteArray): ByteArray {
        val normalized = Bip39.normalize(mnemonic)
        val passphrase = normalized.toCharArray()
        val spec = PBEKeySpec(passphrase, salt, KDF_ITERATIONS, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            passphrase.fill('\u0000')
        }
    }

    private fun hkdfKey(mnemonic: String): ByteArray {
        val ikm = Bip39.normalize(mnemonic).toByteArray(Charsets.UTF_8)
        try {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(HKDF_SALT.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val prk = mac.doFinal(ikm) // HKDF-Extract
            try {
                val info = HKDF_INFO.toByteArray(Charsets.UTF_8)
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                // HKDF-Expand, one block is enough for a 32-byte key.
                mac.update(info)
                mac.update(byteArrayOf(1))
                return mac.doFinal()
            } finally {
                prk.fill(0)
            }
        } finally {
            ikm.fill(0)
        }
    }

    private fun legacyKey(mnemonic: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(KEY_LABEL.toByteArray(Charsets.UTF_8))
        digest.update(Bip39.normalize(mnemonic).toByteArray(Charsets.UTF_8))
        return digest.digest().copyOf(KEY_BYTES)
    }

    private fun header(version: Int, salt: ByteArray?, iv: ByteArray): ByteArray {
        val out = ByteArray(headerBytes(version))
        MAGIC.forEachIndexed { index, char -> out[index] = char.code.toByte() }
        out[4] = version.toByte()
        var offset = 5
        if (version >= 3 && salt != null) {
            salt.copyInto(out, offset)
            offset += SALT_BYTES
        }
        iv.copyInto(out, offset)
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

    /** Reads an archive made by [buildArchive], within the size limits above. */
    fun readArchive(archive: ByteArray): BackupPayload {
        if (archive.size > MAX_ARCHIVE_BYTES) {
            throw BackupException("This backup archive is too large to open")
        }
        var manifest: BackupManifest? = null
        val files = ArrayList<BackupFile>()
        var entries = 0
        var plaintextBytes = 0L
        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries++
                        if (entries > MAX_ENTRIES) {
                            throw BackupException("This backup holds too many files to be a channel backup")
                        }
                        val name = entry.name
                        val bytes = readEntryBounded(zip)
                        plaintextBytes += bytes.size
                        if (plaintextBytes > MAX_ARCHIVE_BYTES) {
                            throw BackupException("This backup unpacks to more than ZeroWallet will read")
                        }
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
        } catch (b: BackupException) {
            throw b
        } catch (t: Throwable) {
            throw BackupException("This backup archive is damaged", t)
        }
        val parsed = manifest ?: throw BackupException("This backup has no manifest")
        if (parsed.network.isBlank()) throw BackupException("This backup does not name a network")
        if (files.isEmpty()) throw BackupException("This backup carries no node data")
        return BackupPayload(parsed, files)
    }

    /**
     * Reads one entry into memory, refusing anything over [MAX_ENTRY_BYTES].
     *
     * The count is taken from the bytes actually read, not from `ZipEntry.size`: a crafted
     * archive can claim any size it likes, and a decompression bomb only shows its real size
     * while it is being unpacked.
     */
    private fun readEntryBounded(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_ENTRY_BYTES) {
                throw BackupException("A file inside this backup is too large to open")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
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
