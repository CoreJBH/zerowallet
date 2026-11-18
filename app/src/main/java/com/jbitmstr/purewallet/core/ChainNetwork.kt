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
    val explorerTxUrl: String,
    val ticker: String
) {
    MAINNET(
        id = "mainnet",
        title = "Bitcoin Mainnet",
        detail = "Real bitcoin. Payments are final.",
        ldk = Network.BITCOIN,
        defaultEsploraUrl = "https://mempool.space/api",
        explorerTxUrl = "https://mempool.space/tx/",
        ticker = "BTC"
    ),
    SIGNET(
        id = "signet",
        title = "Signet",
        detail = "Test coins from a signet faucet. Safe for experiments.",
        ldk = Network.SIGNET,
        defaultEsploraUrl = "https://mempool.space/signet/api",
        explorerTxUrl = "https://mempool.space/signet/tx/",
        ticker = "SIGBTC"
    ),
    TESTNET(
        id = "testnet",
        title = "Testnet3",
        detail = "Legacy test network. Test coins have no value.",
        ldk = Network.TESTNET,
        defaultEsploraUrl = "https://blockstream.info/testnet/api",
        explorerTxUrl = "https://blockstream.info/testnet/tx/",
        ticker = "TBTC"
    );

    val isMainnet: Boolean get() = this == MAINNET

    companion object {
        fun fromId(id: String?): ChainNetwork = values().firstOrNull { it.id == id } ?: MAINNET
    }
}
