package com.example.jarvis.core.permissions

interface PermissionManager {
    fun hasPermission(permission: String): Boolean
    fun requestPermission(permission: String)
}
