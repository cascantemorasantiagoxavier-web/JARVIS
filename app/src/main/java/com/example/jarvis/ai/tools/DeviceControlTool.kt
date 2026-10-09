package com.example.jarvis.ai.tools

import android.accessibilityservice.AccessibilityService
import com.example.jarvis.ai.device.DeviceControlProvider
import com.example.jarvis.ai.device.JarvisAccessibilityService
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class DeviceControlTool(
    private val providerProvider: () -> DeviceControlProvider? = { JarvisAccessibilityService.instance },
    override val riskLevel: RiskLevel = RiskLevel.EXECUTE
) : Tool {

    override val name = "device_control"
    override val description = "Controla la interfaz del dispositivo mediante accesibilidad (atrás, inicio, recientes, clics, texto, desplazamiento)"

    override suspend fun execute(params: Map<String, Any>): String {
        val action = params["action"]?.toString()?.lowercase() ?: params["command"]?.toString()?.lowercase() ?: "home"
        val textParam = params["text"]?.toString() ?: params["query"]?.toString() ?: ""

        val provider = providerProvider()
        if (provider == null || !provider.isServiceEnabled) {
            return "El servicio de accesibilidad de JARVIS no está activo. Por favor, actívalo en los Ajustes de accesibilidad del dispositivo."
        }

        return when (action) {
            "back", "atrás", "atras" -> {
                val success = provider.performActionGlobal(AccessibilityService.GLOBAL_ACTION_BACK)
                if (success) "Acción realizada: Atrás" else "No se pudo ejecutar la acción Atrás."
            }
            "home", "inicio" -> {
                val success = provider.performActionGlobal(AccessibilityService.GLOBAL_ACTION_HOME)
                if (success) "Acción realizada: Inicio" else "No se pudo ejecutar la acción Inicio."
            }
            "recents", "recientes" -> {
                val success = provider.performActionGlobal(AccessibilityService.GLOBAL_ACTION_RECENTS)
                if (success) "Acción realizada: Aplicaciones recientes" else "No se pudo ejecutar la acción Recientes."
            }
            "notifications", "notificaciones" -> {
                val success = provider.performActionGlobal(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                if (success) "Panel de notificaciones abierto." else "No se pudo abrir el panel de notificaciones."
            }
            "click", "pulsar" -> {
                if (textParam.isBlank()) return "Especifica el texto del elemento a pulsar."
                val success = provider.clickNodeByText(textParam)
                if (success) "Pulsado elemento con texto: '$textParam'" else "No encontré ningún elemento interactivo con el texto '$textParam'."
            }
            "input_text", "escribir", "texto" -> {
                if (textParam.isBlank()) return "Especifica el texto a escribir."
                val success = provider.inputText(textParam)
                if (success) "Texto escrito correctamente." else "No encontré ningún campo de texto editable activo."
            }
            "scroll", "desplazar" -> {
                val success = provider.scrollForward()
                if (success) "Desplazamiento realizado." else "No se pudo desplazar la pantalla."
            }
            else -> {
                "Acción de accesibilidad desconocida: $action"
            }
        }
    }
}
