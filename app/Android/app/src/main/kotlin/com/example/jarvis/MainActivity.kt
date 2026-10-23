package com.example.jarvis

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.jarvis.device.PermissionRequest
import com.example.jarvis.ui.JarvisApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingPermission: PermissionRequest? = null

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

        val startRoute = intent?.getStringExtra(EXTRA_ROUTE)
        setContent { JarvisApp(container, startRoute) }
    }

    override fun onDestroy() {
        (application as JarvisApplication).container.permissions.activityAttached = false
        pendingPermission?.result?.complete(false)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
