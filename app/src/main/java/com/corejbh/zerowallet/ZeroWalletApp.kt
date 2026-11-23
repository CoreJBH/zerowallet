package com.corejbh.zerowallet

import android.app.Application
import com.corejbh.zerowallet.core.NodeManager

/**
 * Holds the process-wide node manager so the Lightning node keeps running across
 * Activity recreation. There is no foreground service: the node lives while the app process
 * does, and the wallet brings it back and resyncs when it returns to the screen.
 */
class ZeroWalletApp : Application() {

    val nodeManager: NodeManager by lazy { NodeManager(this) }

    override fun onCreate() {
        super.onCreate()
        nodeManager.bootstrap()
    }
}
