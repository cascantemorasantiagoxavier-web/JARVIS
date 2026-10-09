package com.example.jarvis.core.common

object AppConfig {
    const val APP_NAME = "JARVIS"
    const val VERSION = "1.0.0"
    const val DEBUG_MODE = true

    fun getBackendUrl(): String {
        return ServerConfig.getServerUrl()
    }

    fun isEmulator(): Boolean {
        return ServerConfig.isEmulator()
    }
}
