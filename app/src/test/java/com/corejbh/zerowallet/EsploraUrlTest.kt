package com.corejbh.zerowallet

import com.corejbh.zerowallet.core.EsploraUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The endpoint rules that keep the node from reading chain data over plain http. */
class EsploraUrlTest {

    @Test
    fun httpsEndpointIsAccepted() {
        assertTrue(EsploraUrl.isValid("https://mempool.space/api"))
        assertTrue(EsploraUrl.isValid("https://blockstream.info/testnet/api"))
        assertTrue(EsploraUrl.isValid("https://example.com:8443/esplora"))
    }

    @Test
    fun whitespaceIsTrimmed() {
        val result = EsploraUrl.validate("  https://mempool.space/api  ")
        assertTrue(result.isSuccess)
        assertEquals("https://mempool.space/api", result.getOrNull())
    }

    @Test
    fun trailingSlashIsRemoved() {
        assertEquals("https://mempool.space/api", EsploraUrl.validate("https://mempool.space/api/").getOrNull())
    }

    @Test
    fun plainHttpOnTheInternetIsRejected() {
        val result = EsploraUrl.validate("http://mempool.space/api")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("https"))
    }

    @Test
    fun plainHttpIsAllowedForTheTwoLoopbackHostsOnly() {
        assertTrue(EsploraUrl.isValid("http://127.0.0.1:3002"))
        assertTrue(EsploraUrl.isValid("http://localhost:5000/api"))
        // The platform's network security config cannot express an address block, so the app
        // refuses these instead of saving an endpoint whose every request would be blocked.
        assertFalse(EsploraUrl.isValid("http://127.0.0.2:3002"))
        assertFalse(EsploraUrl.isValid("http://127.5.6.7:3002"))
        assertFalse(EsploraUrl.isValid("http://127.255.255.255/api"))
        assertFalse(EsploraUrl.isValid("http://[::1]:3002"))
        // https is still fine anywhere, loopback included.
        assertTrue(EsploraUrl.isValid("https://127.0.0.1:3002"))
        assertTrue(EsploraUrl.isValid("https://localhost/api"))
    }

    @Test
    fun theCleartextAllowlistIsTheOneTheNetworkSecurityConfigPermits() {
        // A URL accepted here but not in the config saves fine and then fails every request;
        // a config entry with no matching rule is dead weight. The two lists are compared
        // rather than trusted to stay equal.
        assertEquals(
            cleartextHostsInNetworkSecurityConfig(),
            EsploraUrl.CLEARTEXT_HOSTS
        )
    }

    private fun cleartextHostsInNetworkSecurityConfig(): Set<String> {
        val candidates = listOf(
            java.io.File("src/main/res/xml/network_security_config.xml"),
            java.io.File("app/src/main/res/xml/network_security_config.xml")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("network_security_config.xml not found from ${java.io.File(".").absolutePath}")
        val text = file.readText()
        // Only the <domain-config> that permits cleartext takes domains.
        val cleartextBlock = text.substringAfter("<domain-config cleartextTrafficPermitted=\"true\">")
            .substringBefore("</domain-config>")
        return Regex("<domain[^>]*>([^<]+)</domain>")
            .findAll(cleartextBlock)
            .map { it.groupValues[1].trim() }
            .toSet()
    }

    @Test
    fun aPublicHostThatMerelyStartsWith127IsRejected() {
        // `startsWith("127.")` would let these through and the node would read chain data
        // over plain http to an internet host.
        assertFalse(EsploraUrl.isValid("http://127.example.com/api"))
        assertFalse(EsploraUrl.isValid("http://127.0.0.1.example.com/api"))
        assertFalse(EsploraUrl.isValid("http://127.attacker.net:8080/api"))
        assertFalse(EsploraUrl.isValid("http://127.evil"))
    }

    @Test
    fun loopbackLookalikesOutsideTheRangeAreRejected() {
        assertFalse(EsploraUrl.isValid("http://128.0.0.1:3002"))
        assertFalse(EsploraUrl.isValid("http://127.0.0.256:3002"))
        assertFalse(EsploraUrl.isValid("http://127.0.0.1.nip.io:3002"))
        assertFalse(EsploraUrl.isValid("http://126.0.0.1"))
    }

    @Test
    fun plainHttpOnALanAddressIsRejected() {
        assertFalse(EsploraUrl.isValid("http://192.168.0.10:3002"))
        assertFalse(EsploraUrl.isValid("http://10.0.2.2:3002"))
    }

    @Test
    fun schemeLessUrlIsRejected() {
        assertFalse(EsploraUrl.isValid("mempool.space/api"))
        assertFalse(EsploraUrl.isValid("//mempool.space/api"))
    }

    @Test
    fun otherSchemesAreRejected() {
        assertFalse(EsploraUrl.isValid("ftp://example.com/api"))
        assertFalse(EsploraUrl.isValid("file:///tmp/blockchain"))
        assertFalse(EsploraUrl.isValid("ws://example.com"))
    }

    @Test
    fun credentialsInUrlAreRejected() {
        assertFalse(EsploraUrl.isValid("https://user:secret@example.com/api"))
    }

    @Test
    fun queryAndFragmentAreRejected() {
        assertFalse(EsploraUrl.isValid("https://example.com/api?key=1"))
        assertFalse(EsploraUrl.isValid("https://example.com/api#top"))
    }

    @Test
    fun blankAndBrokenInputIsRejected() {
        assertFalse(EsploraUrl.isValid(""))
        assertFalse(EsploraUrl.isValid("   "))
        assertFalse(EsploraUrl.isValid("https://"))
        assertFalse(EsploraUrl.isValid("not a url"))
    }

    @Test
    fun messageIsUserFacing() {
        val message = EsploraUrl.validate("http://example.com/api").exceptionOrNull()?.message
        assertTrue(!message.isNullOrBlank())
    }
}
