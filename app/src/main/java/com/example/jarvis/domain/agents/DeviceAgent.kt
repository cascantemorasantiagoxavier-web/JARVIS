package com.example.jarvis.domain.agents

interface DeviceAgent : Agent {
    suspend fun controlDevice(action: String): Boolean
    suspend fun isAppInstalled(packageName: String): Boolean
    suspend fun launchApp(packageName: String): Result<Unit>
    suspend fun openSettings(settingType: String = "general"): Result<Unit>
    suspend fun getDeviceInfo(): String
    suspend fun getBatteryLevel(): String
}
