package com.example.jarvis.core.common

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

object ServerConfig {
    private const val PREFS_NAME = "jarvis_server_prefs"
    private const val KEY_SERVER_URL = "custom_server_url"
    const val DEFAULT_EMULATOR_URL = "http://10.0.2.2:3000"
    const val DEFAULT_PHYSICAL_URL = "https://jarvis-gbqy.onrender.com"
    const val DEFAULT_LOCAL_URL = "http://localhost:3000"

    fun getServerUrl(context: Context? = null): String {
        val prefs = getPrefs(context)
        val customUrl = prefs?.getString(KEY_SERVER_URL, null)
        if (!customUrl.isNullOrBlank()) {
            return customUrl.trimEnd('/')
        }

        return try {
            val isAndroidEnv = try { Build.MODEL != null } catch (_: Throwable) { false }
            if (!isAndroidEnv) {
                DEFAULT_LOCAL_URL
            } else if (isEmulator()) {
                DEFAULT_EMULATOR_URL
            } else {
                DEFAULT_PHYSICAL_URL
            }
        } catch (_: Throwable) {
            DEFAULT_LOCAL_URL
        }
    }

    private fun getPrefs(context: Context?): SharedPreferences? {
        val ctx = context ?: return null
        return try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
    }

    fun setServerUrl(context: Context, url: String): Boolean {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank() || (!trimmed.startsWith("http://") && !trimmed.startsWith("https://"))) {
            return false
        }
        val prefs = getPrefs(context) ?: return false
        prefs.edit().putString(KEY_SERVER_URL, trimmed).apply()
        return true
    }

    fun resetToDefault(context: Context) {
        val prefs = getPrefs(context) ?: return
        prefs.edit().remove(KEY_SERVER_URL).apply()
    }

    fun isEmulator(): Boolean {
        return try {
            (Build.FINGERPRINT.startsWith("generic")
                    || Build.FINGERPRINT.startsWith("unknown")
                    || Build.MODEL.contains("google_sdk")
                    || Build.MODEL.contains("Emulator")
                    || Build.MODEL.contains("Android SDK built for x86")
                    || Build.MANUFACTURER.contains("Genymotion")
                    || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                    || "google_sdk" == Build.PRODUCT)
        } catch (_: Throwable) {
            true
        }
    }
}
