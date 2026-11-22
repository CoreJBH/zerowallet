package com.jbitmstr.purewallet.core

import org.lightningdevkit.ldknode.Network

/**
 * The chains this wallet can run on.
 *
 * Bitcoin mainnet is the default. Signet and Testnet3 stay available for safe testing.
 * ldk-node 0.7.0 exposes BITCOIN, TESTNET, SIGNET and REGTEST; there is no separate
 * testnet4 variant in the library, so Testnet3 is the test network offered here.
 */
enum class ChainNetwork(
    val id: String,
    val title: String,
    val detail: String,
    val ldk: Network,
    val defaultEsploraUrl: String,
    /** Other public Esplora servers this chain can be read from, tried after the default. */
    val publicEsploraFallbacks: List<String>,
    val explorerTxUrl: String,
    val ticker: String,
    /**
     * Hash of block 0 on this chain, as hex.
     *
     * BOLT12 does not carry a network name: a BOLT12 invoice names the chain it is for by
     * this hash, so it is the only way to tell a mainnet invoice from a signet one.
     */
    val genesisBlockHash: String
) {
    MAINNET(
        id = "mainnet",
        title = "Bitcoin Mainnet",
        detail = "Real bitcoin. Payments are final.",
        ldk = Network.BITCOIN,
        // Blockstream's Esplora answers chain scans and address lookups without the rate
        // limiting that made mempool.space return 429 during the wallet's full scan.
        defaultEsploraUrl = "https://blockstream.info/api",
        publicEsploraFallbacks = listOf(
            "https://mempool.space/api",
            "https://mempool.emzy.de/api"
        ),
        explorerTxUrl = "https://mempool.space/tx/",
        ticker = "BTC",
        genesisBlockHash = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
    ),
    SIGNET(
        id = "signet",
        title = "Signet",
        detail = "Test coins from a signet faucet. Safe for experiments.",
        ldk = Network.SIGNET,
        defaultEsploraUrl = "https://mempool.space/signet/api",
        publicEsploraFallbacks = listOf(
            "https://blockstream.info/signet/api"
        ),
        explorerTxUrl = "https://mempool.space/signet/tx/",
        ticker = "SIGBTC",
        genesisBlockHash = "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"
    ),
    TESTNET(
        id = "testnet",
        title = "Testnet3",
        detail = "Legacy test network. Test coins have no value.",
        ldk = Network.TESTNET,
        defaultEsploraUrl = "https://blockstream.info/testnet/api",
        publicEsploraFallbacks = listOf(
            "https://mempool.space/testnet/api"
        ),
        explorerTxUrl = "https://blockstream.info/testnet/tx/",
        ticker = "TBTC",
        genesisBlockHash = "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943"
    );

    val isMainnet: Boolean get() = this == MAINNET

    /** The chain's built-in endpoints, default first, with no repeats. */
    val publicEsploraUrls: List<String>
        get() = (listOf(defaultEsploraUrl) + publicEsploraFallbacks).distinct()

    companion object {
        fun fromId(id: String?): ChainNetwork = values().firstOrNull { it.id == id } ?: MAINNET
    }
}

/**
 * Tells whether a BOLT12 invoice belongs to the chain this wallet is running on.
 *
 * A BOLT12 invoice carries the 32-byte genesis hash of its chain instead of a name, so an
 * invoice read on the wrong chain can only fail after the node starts routing it. Comparing
 * the hash with the known block-0 hash of each chain catches that before the payment screen.
 * Plain Kotlin, so unit tests cover it without the node.
 */
object Bolt12Chain {

    private val HEX = "0123456789abcdef".toCharArray()

    /** The 32 chain-hash bytes as lowercase hex, or null when [chain] is not a chain hash. */
    fun hexOf(chain: List<UByte>): String? {
        if (chain.size != 32) return null
        val out = StringBuilder(64)
        for (byte in chain) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    /** True when [chain] is the genesis hash of [network]. */
    fun matches(chain: List<UByte>, network: ChainNetwork): Boolean =
        hexOf(chain) == network.genesisBlockHash
}
