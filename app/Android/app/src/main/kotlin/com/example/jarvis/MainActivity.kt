package com.example.jarvis

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.jarvis.device.PermissionRequest
import com.example.jarvis.ui.JarvisApp
import kotlinx.coroutines.launch

/** Petición de navegación desde un intent; `nonce` permite repetir la misma ruta. */
data class RouteRequest(val route: String, val nonce: Long)

class MainActivity : ComponentActivity() {
    private var pendingPermission: PermissionRequest? = null
    private var routeRequest by mutableStateOf<RouteRequest?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pendingPermission?.result?.complete(result.values.all { it })
        pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.argb(255, 2, 4, 8)),
        )
        super.onCreate(savedInstanceState)
        val container = (application as JarvisApplication).container
        container.permissions.activityAttached = true

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.permissions.requests.collect { req ->
                    pendingPermission?.result?.complete(false)
                    pendingPermission = req
                    permissionLauncher.launch(req.permissions)
                }
            }
        }

        routeRequest = intent?.routeRequest()
        setContent { JarvisApp(container, routeRequest) }
    }

    override fun onStart() {
        super.onStart()
        val container = (application as JarvisApplication).container
        lifecycleScope.launch { container.voice.restoreWakeWord() }
    }

    /** launchMode singleTask: tocar un recordatorio con la app abierta llega aquí. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.routeRequest()?.let { routeRequest = it }
    }

    private fun Intent.routeRequest(): RouteRequest? =
        getStringExtra(EXTRA_ROUTE)?.takeIf { it.isNotBlank() }?.let { RouteRequest(it, System.nanoTime()) }

    override fun onDestroy() {
        (application as JarvisApplication).container.permissions.activityAttached = false
        pendingPermission?.result?.complete(false)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
