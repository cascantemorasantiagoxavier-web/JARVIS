package com.example.jarvis.core.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings

class AppManagerImpl(
    private val context: Context
) : AppManager {

    override fun isAppInstalled(packageName: String): Boolean {
        val targetPackage = resolvePackageName(packageName) ?: packageName
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    targetPackage,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(targetPackage, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            context.packageManager.getLaunchIntentForPackage(targetPackage) != null
        } catch (_: Exception) {
            false
        }
    }

    override fun launchApp(packageName: String): Result<Unit> {
        val targetPackage = resolvePackageName(packageName) ?: packageName
        return try {
            val intent: Intent? = context.packageManager.getLaunchIntentForPackage(targetPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Result.success(Unit)
            } else {
                Result.failure(IllegalArgumentException("No launch intent found for $targetPackage"))
            }
        } catch (e: SecurityException) {
            Result.failure(SecurityException("Esta acción requiere autorización del usuario o un permiso especial no concedido."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun openSettings(settingType: String): Result<Unit> {
        val action = when (settingType.lowercase().trim()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "sound", "sonido", "audio" -> Settings.ACTION_SOUND_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(SecurityException("Esta acción requiere autorización del usuario o un permiso especial no concedido."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    override fun getAndroidVersion(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    override fun getBatteryLevel(): Result<Int> {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level in 0..100) {
                Result.success(level)
            } else {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, filter)
                val levelVal = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scaleVal = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (levelVal >= 0 && scaleVal > 0) {
                    Result.success((levelVal * 100) / scaleVal)
                } else {
                    Result.failure(IllegalStateException("Battery level unavailable"))
                }
            }
        } catch (e: SecurityException) {
            Result.failure(SecurityException("Esta acción requiere autorización del usuario o un permiso especial no concedido."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resolvePackageName(input: String): String? {
        val cleanInput = input.lowercase().trim()
            .removePrefix("abre ")
            .removePrefix("abrir ")
            .removePrefix("open ")
            .removePrefix("launch ")
            .removePrefix("inicia ")
            .removePrefix("iniciar ")
            .trim()

        if (cleanInput.isBlank()) return null

        if (cleanInput.contains(".") && !cleanInput.contains(" ")) {
            return cleanInput
        }

        val knownApps = mapOf(
            "spotify" to "com.spotify.music",
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings",
            "ajustes" to "com.android.settings",
            "configuracion" to "com.android.settings",
            "configuración" to "com.android.settings",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "instagram" to "com.instagram.android",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "facebook" to "com.facebook.katana",
            "telegram" to "org.telegram.messenger",
            "camera" to "com.android.camera",
            "cámara" to "com.android.camera",
            "camara" to "com.android.camera",
            "galeria" to "com.android.gallery3d",
            "galería" to "com.android.gallery3d",
            "fotos" to "com.google.android.apps.photos",
            "photos" to "com.google.android.apps.photos",
            "clock" to "com.google.android.deskclock",
            "reloj" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator",
            "calculadora" to "com.google.android.calculator"
        )

        knownApps[cleanInput]?.let { return it }

        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            @Suppress("DEPRECATION")
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            for (info in resolveInfos) {
                val label = info.loadLabel(pm).toString().lowercase()
                if (label == cleanInput || label.contains(cleanInput)) {
                    return info.activityInfo.packageName
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
