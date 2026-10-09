package com.example.jarvis.ai.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class CheckNetworkTool(
    private val contextProvider: () -> Context? = { null }
) : Tool {
    override val name = "check_network"
    override val description = "Comprueba la conectividad real a Internet del dispositivo localmente"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val ctx = contextProvider()
        if (ctx != null) {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val network = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(network)
                val isConnected = capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )

                return if (isConnected) {
                    "Tienes conexión a Internet activa."
                } else {
                    "No tienes conexión a Internet en este momento. Las funciones locales de JARVIS (hora, fecha, batería, calculadora, mapas y accesibilidad) permanecen operativas."
                }
            }
        }
        return "Conexión a Internet no verificada. Las herramientas locales de JARVIS se mantienen activas."
    }
}
