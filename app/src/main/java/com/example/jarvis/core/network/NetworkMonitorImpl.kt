package com.example.jarvis.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class NetworkMonitorImpl(private val context: Context) : NetworkMonitor {
    override val isConnected: Boolean
        get() {
            return try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network = connectivityManager?.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } catch (_: Exception) {
                true // default to true in mock/abstraction mode if permission not declared
            }
        }
}
