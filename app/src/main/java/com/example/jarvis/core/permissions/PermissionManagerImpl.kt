package com.example.jarvis.core.permissions

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class PermissionManagerImpl(private val context: Context) : PermissionManager {
    override fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun requestPermission(permission: String) {
        // Handled in UI / Activity layer
    }
}
