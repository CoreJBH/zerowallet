package com.corejbh.zerowallet

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every privileged action goes through [com.corejbh.zerowallet.core.Authorization.guarded].
 *
 * The eighth review found the device-lock answer being ignored at seven call sites - showing
 * the phrase, funding and closing a channel, exporting and restoring a channel backup,
 * wiping the wallet - because the sheets took the prompt's callback as a plain function and
 * wrote the action into its body. `Authorization` makes that shape unavailable, and this test
 * keeps it that way: it reads the sources and fails if a screen ever takes the raw callback
 * again, or if one of the guarded sites disappears.
 *
 * It is a source check on purpose. Testing it through the composables would need an
 * instrumentation run, and what went wrong was not the behaviour of a Compose screen but the
 * shape of the call site.
 */
class AuthorizeCallSiteTest {

    private val packageDir = "src/main/java/com/corejbh/zerowallet"

    /** The source tree, found from wherever Gradle runs the test. */
    private fun sources(): Map<String, String> {
        var dir: File? = File("").absoluteFile
        val roots = mutableListOf<File>()
        repeat(5) {
            roots.add(File(dir, packageDir))
            roots.add(File(dir, "app/$packageDir"))
            dir = dir?.parentFile
        }
        val root = roots.firstOrNull { it.isDirectory }
            ?: throw AssertionError("Could not find $packageDir from ${File("").absolutePath}")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    @Test
    fun noScreenTakesTheRawPromptCallback() {
        val offenders = sources()
            .filterKeys { it != "core/Authorization.kt" }
            .filterValues { text ->
                text.contains("onAuthorize") ||
                    text.contains("(String, (Boolean) -> Unit) -> Unit")
            }
        assertTrue(
            "A screen still takes the device-lock callback directly instead of an " +
                "Authorization: ${offenders.keys}",
            offenders.isEmpty()
        )
    }

    @Test
    fun everyPrivilegedActionIsGuarded() {
        val sources = sources()
        // The seven places the review named, plus the two phrase reveals on the home side.
        // WalletApp's third guarded call is the seal prompt of 1.1.17: creating or restoring a
        // wallet asks for the device lock when the Keystore's bound key needs it, and the
        // work happens inside that answer rather than before it.
        val expected = mapOf(
            "ui/sheets/SettingsSheet.kt" to 3,
            "ui/sheets/ChannelsSheet.kt" to 2,
            "ui/sheets/SendSheet.kt" to 1,
            "ui/WalletApp.kt" to 3
        )
        for ((file, count) in expected) {
            val text = sources[file] ?: throw AssertionError("$file is gone")
            val found = Regex("""\.guarded\(""").findAll(text).count()
            assertEquals("$file guards $found actions, expected $count", count, found)
        }
    }

    @Test
    fun thePromptItselfPassesTheAnswerThrough() {
        // WalletApp is the one place that builds an Authorization, and AppLock is the one
        // place that answers it. If either stops delivering the boolean, guarded() never runs
        // and every action above becomes unreachable - which is a different failure, not a
        // safer one.
        val sources = sources()
        val walletApp = sources["ui/WalletApp.kt"] ?: throw AssertionError("WalletApp.kt is gone")
        assertTrue(
            "WalletApp no longer builds an Authorization",
            walletApp.contains("val authorization = Authorization {")
        )
        assertTrue(
            "WalletApp never passes the answer back to the caller",
            walletApp.contains("action(true)") && walletApp.contains("action(false)")
        )
    }
}
