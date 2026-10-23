package com.example.jarvis

import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import com.example.jarvis.device.MediaRequest
import com.example.jarvis.device.PermissionRequest
import kotlinx.coroutines.CompletableDeferred
import com.example.jarvis.ui.JarvisApp
import kotlinx.coroutines.launch

/** Petición de navegación desde un intent; `nonce` permite repetir la misma ruta. */
data class RouteRequest(val route: String, val nonce: Long)

class MainActivity : ComponentActivity() {
    private var pendingPermission: PermissionRequest? = null
    private var routeRequest by mutableStateOf<RouteRequest?>(null)
    private var pendingDocument: CompletableDeferred<Uri?>? = null
    private var pendingPhoto: CompletableDeferred<Boolean>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pendingPermission?.result?.complete(result.values.all { it })
        pendingPermission = null
    }

    // Selector del sistema (SAF): el usuario elige el archivo; JARVIS solo recibe ese documento.
    private val documentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingDocument?.complete(uri)
        pendingDocument = null
    }

    // App de cámara del sistema: guarda la foto en el URI de FileProvider que le damos.
    private val photoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        pendingPhoto?.complete(taken)
        pendingPhoto = null
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.media.requests.collect { req ->
                    when (req) {
                        is MediaRequest.PickDocument -> {
                            pendingDocument?.complete(null)
                            pendingDocument = req.result
                            documentLauncher.launch(req.mimeTypes)
                        }
                        is MediaRequest.CapturePhoto -> {
                            pendingPhoto?.complete(false)
                            pendingPhoto = req.result
                            photoLauncher.launch(req.target)
                        }
                    }
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
        pendingDocument?.complete(null)
        pendingPhoto?.complete(false)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
