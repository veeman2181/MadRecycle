package com.ecomadison.app.network

/** Gates whether §5.5 Tier 3.5 (cloud vision backup) is even attempted. */
interface NetworkMonitor {
    fun isConnected(): Boolean
}
