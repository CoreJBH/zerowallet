package com.corejbh.zerowallet

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The FileProvider that hands a sealed channel backup to the share sheet, against the real
 * manifest and `file_paths.xml`.
 *
 * A share that silently hands out a `content://` URI the receiving app cannot open is a backup
 * that never leaves the phone, and one that hands out more than `cache/backups` is a wallet's
 * files exposed to any app the user shares with. Both are decided by two XML files, which no
 * unit test can read, so this checks them where Android enforces them.
 */
@RunWith(AndroidJUnit4::class)
class FileProviderInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val authority = "${context.packageName}.fileprovider"
    private val shared = File(context.cacheDir, "backups")
    private var written: File? = null

    @After
    fun cleanUp() {
        written?.delete()
        shared.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun theSealedBackupInTheCacheIsSharedAndCanBeRead() {
        shared.mkdirs()
        val file = File(shared, "zerowallet-mainnet-test.zwbackup").apply {
            writeBytes(ByteArray(4096) { index -> (index % 251).toByte() })
        }
        written = file

        val uri = FileProvider.getUriForFile(context, authority, file)

        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        // The receiving app reads it through the same resolver the wallet does: a URI that
        // resolves but cannot be opened is the failure this test exists for.
        val read = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        assertNotNull("the shared URI could not be opened", read)
        assertEquals(4096, read!!.size)
    }

    @Test
    fun onlyTheBackupCacheIsShareable() {
        // The node store and the recovery phrase are not in `cache/backups`, and asking for
        // them has to fail rather than produce a URI that happens to work.
        val nodeStore = File(context.filesDir, "ldk-node/mainnet").apply { mkdirs() }
        val storeFile = File(nodeStore, "channel_manager").apply { writeText("state") }
        try {
            FileProvider.getUriForFile(context, authority, storeFile)
            fail("the node store was handed out through the backup provider")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("Failed to find configured root") == true)
        } finally {
            storeFile.delete()
            nodeStore.delete()
        }
    }

    @Test
    fun theShareIntentCarriesAReadGrantAndNothingElse() {
        shared.mkdirs()
        val file = File(shared, "zerowallet-mainnet-intent.zwbackup").apply { writeText("sealed") }
        written = file
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        assertTrue(
            "the grant is the one thing that lets Drive read it",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
        assertTrue(
            "a write grant is never needed for a backup",
            intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0
        )
    }
}
