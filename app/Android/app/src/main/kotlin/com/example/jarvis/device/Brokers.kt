package com.example.jarvis.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.jarvis.core.tools.ConfirmationGate
import com.example.jarvis.core.tools.PermissionGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** ¿Está la app en primer plano? Android restringe abrir actividades desde segundo plano. */
object ForegroundTracker {
    val isForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

class PermissionRequest(val permissions: Array<String>, val result: CompletableDeferred<Boolean>)

/**
 * Solicita permisos de Android en el momento en que una función los necesita.
 * MainActivity atiende las peticiones con ActivityResult; sin actividad visible, se informa del fallo.
 */
class PermissionBroker(private val context: Context) : PermissionGate {
    private val _requests = MutableSharedFlow<PermissionRequest>(extraBufferCapacity = 4)
    val requests: SharedFlow<PermissionRequest> = _requests.asSharedFlow()
    @Volatile var activityAttached: Boolean = false

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun request(vararg permissions: String): Boolean {
        val missing = permissions.filterNot { isGranted(it) }
        if (missing.isEmpty()) return true
        if (!activityAttached || !ForegroundTracker.isForeground) return false
        val deferred = CompletableDeferred<Boolean>()
        _requests.emit(PermissionRequest(missing.toTypedArray(), deferred))
        return withTimeoutOrNull(90_000) { deferred.await() } ?: false
    }

    /** Permisos lógicos usados en tools.json → permisos de Android. */
    override suspend fun ensure(permission: String): Boolean = withContext(Dispatchers.Main) {
        when (permission) {
            "calendar.read" -> request(Manifest.permission.READ_CALENDAR)
            "location" -> request(Manifest.permission.ACCESS_COARSE_LOCATION)
            "microphone" -> request(Manifest.permission.RECORD_AUDIO)
            // La foto la toma la app de cámara del sistema (ACTION_IMAGE_CAPTURE): JARVIS no necesita
            // el permiso CAMERA, solo que exista una cámara.
            "camera" -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            else -> false
        }
    }
}

data class PendingConfirmation(val toolName: String, val summary: String, val result: CompletableDeferred<Boolean>)

/** Confirmación explícita en pantalla para acciones destructivas o sensibles. */
class ConfirmationBroker : ConfirmationGate {
    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = _pending.asStateFlow()

    override suspend fun confirm(toolName: String, summary: String): Boolean {
        if (!ForegroundTracker.isForeground) return false
        val d = CompletableDeferred<Boolean>()
        val request = PendingConfirmation(toolName, summary, d)
        // Si quedaba otra pregunta abierta, se responde "no" en vez de dejarla esperando.
        _pending.getAndUpdate { request }?.result?.complete(false)
        val answer = try {
            withTimeoutOrNull(120_000) { d.await() } ?: false
        } finally {
            _pending.compareAndSet(request, null)
        }
        return answer
    }

    fun answer(accepted: Boolean) {
        _pending.getAndUpdate { null }?.result?.complete(accepted)
    }
}
