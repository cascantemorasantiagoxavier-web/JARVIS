package com.example.jarvis.ai.tools

import com.example.jarvis.core.device.AppManager
import com.example.jarvis.core.di.AppContainer
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class BatteryTool(
    private val appManagerProvider: () -> AppManager? = { AppContainer.appManager }
) : Tool {
    override val name = "battery_info"
    override val description = "Consulta el nivel y estado actual de la batería del dispositivo localmente"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val appMgr = appManagerProvider() ?: return "No fue posible acceder a la información del sistema para la batería."
        val batteryResult = appMgr.getBatteryLevel()

        return if (batteryResult.isSuccess) {
            val level = batteryResult.getOrNull() ?: 0
            "El nivel de batería actual es del $level%."
        } else {
            batteryResult.exceptionOrNull()?.message ?: "No fue posible consultar el nivel de batería."
        }
    }
}
