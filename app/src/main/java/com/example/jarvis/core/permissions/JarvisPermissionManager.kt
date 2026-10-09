package com.example.jarvis.core.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object JarvisPermissionManager {
    private const val TAG = "JARVIS"

    val runtimePermissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return list.toTypedArray()
        }

    fun auditAndLogPermissions(context: Context) {
        Log.i(TAG, "[JARVIS][PERMISSION] AUDIT_STARTED")

        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "[JARVIS][PERMISSION] RECORD_AUDIO=${if (micGranted) "GRANTED" else "DENIED"}")

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        Log.i(TAG, "[JARVIS][PERMISSION] POST_NOTIFICATIONS=${if (notifGranted) "GRANTED" else "DENIED"}")

        val readCalGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "[JARVIS][PERMISSION] READ_CALENDAR=${if (readCalGranted) "GRANTED" else "DENIED"}")

        val writeCalGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "[JARVIS][PERMISSION] WRITE_CALENDAR=${if (writeCalGranted) "GRANTED" else "DENIED"}")

        val contactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "[JARVIS][PERMISSION] READ_CONTACTS=${if (contactsGranted) "GRANTED" else "DENIED"}")

        val locationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "[JARVIS][PERMISSION] ACCESS_FINE_LOCATION=${if (locationGranted) "GRANTED" else "DENIED"}")

        val allGranted = micGranted && notifGranted && readCalGranted && writeCalGranted && contactsGranted && locationGranted
        Log.i(TAG, "[JARVIS][PERMISSION] REQUIRED_RUNTIME_PERMISSIONS_GRANTED=$allGranted")
    }

    fun requestMissingPermissions(activity: Activity, requestCode: Int = 100) {
        auditAndLogPermissions(activity)
        val missing = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), requestCode)
        }
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
