package com.jbitmstr.purewallet

import android.app.Application
import com.jbitmstr.purewallet.core.NodeManager

/**
 * Holds the process-wide node manager so the Lightning node keeps running across
 * Activity recreation and while the foreground service holds the process.
 */
class PureWalletApp : Application() {

    val nodeManager: NodeManager by lazy { NodeManager(this) }

    override fun onCreate() {
        super.onCreate()
        nodeManager.bootstrap()
    }
}
