package com.example.jarvis.core.device

interface AppManager {
    fun isAppInstalled(packageName: String): Boolean
    fun launchApp(packageName: String): Result<Unit>
    fun openSettings(settingType: String = "general"): Result<Unit>
    fun getDeviceModel(): String
    fun getAndroidVersion(): String
    fun getBatteryLevel(): Result<Int>
}
