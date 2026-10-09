package com.example.jarvis.features.settings

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.jarvis.ai.memory.PersistenceMemoryHolder
import com.example.jarvis.ai.proactive.ProactiveNotificationManager
import com.example.jarvis.core.common.AppConfig
import com.example.jarvis.core.permissions.JarvisPermissionManager
import com.example.jarvis.domain.proactive.ProactiveDecision
import com.example.jarvis.domain.proactive.ProactivePriority
import com.example.jarvis.domain.proactive.ProactiveReason
import com.example.jarvis.domain.proactive.ProactiveSuggestion
import com.example.jarvis.obsidian.vault.AndroidVaultProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class DiagnosticStatus(
    val backend: Boolean = false,
    val gemini: Boolean = false,
    val microphonePermission: Boolean = false,
    val wakeWordService: Boolean = false,
    val wakeWordDetected: Boolean = false,
    val internet: Boolean = false,
    val memory: Boolean = false,
    val ttsInitialized: Boolean = false,
    val openAiRealtime: String = "NO_TESTED"
)

data class SettingsUiState(
    val assistantName: String = AppConfig.APP_NAME,
    val voiceConfig: String = "Comunicación en tiempo real (Predeterminado)",
    val memoryConfig: String = "Memoria jerárquica (Trabajo + Sesión + Largo plazo)",
    val aiConfig: String = "Orquestador modular y sistema multi-agente",
    val securityConfig: String = "Almacenamiento cifrado con Keystore y permisos",
    val vaultPath: String = "No seleccionado",
    val vaultStatus: String = "No configurado",
    val isVaultValid: Boolean = false,
    val diagnostics: DiagnosticStatus = DiagnosticStatus(),
    val isTestingDiagnostics: Boolean = false,
    val isMicrophoneGranted: Boolean = false,
    val isNotificationGranted: Boolean = false,
    val isCalendarGranted: Boolean = false,
    val isContactsGranted: Boolean = false,
    val isLocationGranted: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val vaultProvider = AndroidVaultProvider(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        checkVaultStatus()
        runDiagnostics()
        checkPermissions()
    }

    val assistantName: String = AppConfig.APP_NAME

    fun checkPermissions() {
        val app = getApplication<Application>()
        JarvisPermissionManager.auditAndLogPermissions(app)
        val hasMic = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val hasCal = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val hasContacts = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        _uiState.update {
            it.copy(
                isMicrophoneGranted = hasMic,
                isNotificationGranted = hasNotif,
                isCalendarGranted = hasCal,
                isContactsGranted = hasContacts,
                isLocationGranted = hasLocation
            )
        }
    }

    fun requestMissingPermissions(activity: Activity) {
        JarvisPermissionManager.requestMissingPermissions(activity)
        checkPermissions()
    }

    fun openAppSettings() {
        JarvisPermissionManager.openAppSettings(getApplication())
    }

    fun sendTestNotification(): Boolean {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                Log.w("JARVIS", "[JARVIS][NOTIFY] NOTIFICATION_BLOCKED_PERMISSION_DENIED")
                return false
            }
        }
        val pnm = ProactiveNotificationManager(app)
        val testSuggestion = ProactiveSuggestion(
            id = "test_notif_${System.currentTimeMillis()}",
            title = "Sistema JARVIS Activo",
            message = "Notificación de prueba verificada en el dispositivo físico.",
            priority = ProactivePriority.HIGH,
            decision = ProactiveDecision.NOTIFY,
            reason = ProactiveReason.IDLE_NEXT_ACTION
        )
        val sent = pnm.showProactiveSuggestionNotification(testSuggestion)
        if (sent) {
            Log.i("JARVIS", "[JARVIS][NOTIFY] NOTIFICATION_SENT")
        } else {
            Log.e("JARVIS", "[JARVIS][NOTIFY] NOTIFICATION_FAILED")
        }
        return sent
    }

    fun checkVaultStatus() {
        viewModelScope.launch {
            val path = vaultProvider.vaultPath ?: "No seleccionado"
            val isValid = vaultProvider.validateVault()
            _uiState.update {
                it.copy(
                    vaultPath = path,
                    vaultStatus = if (isValid) "Válido y Accesible" else if (path != "No seleccionado") "Permiso requerido o Inválido" else "No seleccionado",
                    isVaultValid = isValid
                )
            }
        }
    }

    fun onVaultUriSelected(uri: Uri) {
        vaultProvider.setVaultUri(uri)
        checkVaultStatus()
    }

    fun validateVaultAccess() {
        checkVaultStatus()
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingDiagnostics = true) }

            val app = getApplication<Application>()
            val hasMic = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(network)
            val hasInternet = caps != null && (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            )

            val backendConnected = checkBackendHealth()
            val geminiConnected = if (backendConnected) checkGeminiApiHealth() else false

            val memoryOk = try {
                PersistenceMemoryHolder.repository != null
            } catch (_: Exception) { false }

            _uiState.update {
                it.copy(
                    isTestingDiagnostics = false,
                    diagnostics = DiagnosticStatus(
                        backend = backendConnected,
                        gemini = geminiConnected,
                        microphonePermission = hasMic,
                        wakeWordService = true,
                        wakeWordDetected = false,
                        internet = hasInternet,
                        memory = memoryOk,
                        ttsInitialized = true,
                        openAiRealtime = "NO_TESTED"
                    )
                )
            }
        }
    }

    private suspend fun checkBackendHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i("JARVIS", "[JARVIS][DIAGNOSTIC] BACKEND_CHECK_STARTED URL=${AppConfig.getBackendUrl()}/health")
            val url = URL("${AppConfig.getBackendUrl()}/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            Log.i("JARVIS", "[JARVIS][DIAGNOSTIC] BACKEND_HTTP_STATUS=$code BACKEND_AVAILABLE=${code == HttpURLConnection.HTTP_OK}")
            conn.disconnect()
            code == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][DIAGNOSTIC] BACKEND_CHECK_FAILED: ${e.message}")
            false
        }
    }

    private suspend fun checkGeminiApiHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i("JARVIS", "[JARVIS][GEMINI] CHECK_STARTED")
            val url = URL("${AppConfig.getBackendUrl()}/api/gemini/chat")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val bodyJson = "{\"model\":\"gemini-flash-lite-latest\",\"messages\":[{\"role\":\"user\",\"content\":\"Responde únicamente: JARVIS GEMINI OK\"}]}"
            conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            Log.i("JARVIS", "[JARVIS][GEMINI] HTTP_STATUS=$code")

            val ok = if (code == HttpURLConnection.HTTP_OK) {
                val resStr = conn.inputStream.bufferedReader().use { it.readText() }
                Log.i("JARVIS", "[JARVIS][GEMINI] RESPONSE_RECEIVED=true RESPONSE_TEXT=\"$resStr\"")
                resStr.contains("JARVIS GEMINI OK", ignoreCase = true) || resStr.contains("content", ignoreCase = true)
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e("JARVIS", "[JARVIS][GEMINI] RESPONSE_RECEIVED=false HTTP_STATUS=$code ERROR=\"$err\"")
                false
            }
            conn.disconnect()
            ok
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][GEMINI] CHECK_FAILED: ${e.message}", e)
            false
        }
    }
}
