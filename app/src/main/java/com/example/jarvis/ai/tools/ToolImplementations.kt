package com.example.jarvis.ai.tools

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.core.device.AppManager
import com.example.jarvis.core.di.AppContainer
import com.example.jarvis.domain.tasks.Task
import com.example.jarvis.domain.tasks.TaskPriority
import com.example.jarvis.domain.tasks.TaskStatus
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class SystemInfoTool(
    private val appManagerProvider: () -> AppManager? = { AppContainer.appManager }
) : Tool {
    override val name = "system_info"
    override val description = "Obtiene información general del sistema del dispositivo"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val appMgr = appManagerProvider()
        return if (appMgr != null) {
            "Modelo: ${appMgr.getDeviceModel()} | Versión: ${appMgr.getAndroidVersion()}"
        } else {
            "OS: Android, Versión de JARVIS: 1.0.0"
        }
    }
}



class TaskCreateTool : Tool {
    override val name = "task_create"
    override val description = "Crea una nueva tarea de productividad con prioridad y recordatorio"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(params: Map<String, Any>): String {
        val title = params["title"]?.toString() ?: params["query"]?.toString() ?: "Nueva Tarea de JARVIS"
        val desc = params["description"]?.toString() ?: ""
        val priorityStr = params["priority"]?.toString()?.uppercase() ?: "MEDIUM"
        val priority = try { TaskPriority.valueOf(priorityStr) } catch (_: Exception) { TaskPriority.MEDIUM }

        val task = Task(
            title = title,
            description = desc,
            priority = priority,
            status = TaskStatus.TODO
        )

        val result = GlobalTaskHolder.repository.create(task)
        return if (result is AppResult.Success) {
            "✅ Tarea creada exitosamente:\n• Título: ${task.title}\n• Prioridad: ${task.priority}\n• Estado: Pendiente"
        } else {
            "No fue posible crear la tarea."
        }
    }
}

class ObsidianSearchTool : Tool {
    override val name = "obsidian_search"
    override val description = "Busca en la abstracción del vault de Obsidian"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val query = params["query"]?.toString() ?: ""
        return "Resultados de búsqueda en el vault de Obsidian para: $query"
    }
}

class OpenSettingsTool(
    private val appManagerProvider: () -> AppManager? = { AppContainer.appManager }
) : Tool {
    override val name = "open_settings"
    override val description = "Abre las pantallas de ajustes del dispositivo: generales, Wi-Fi, Bluetooth o sonido"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val settingType = params["settingType"]?.toString()
            ?: params["type"]?.toString()
            ?: params["query"]?.toString()
            ?: "general"

        val appMgr = appManagerProvider() ?: return "No fue posible acceder a la configuración del dispositivo."
        val result = appMgr.openSettings(settingType)
        return if (result.isSuccess) {
            "Abriendo ajustes de $settingType..."
        } else {
            result.exceptionOrNull()?.message ?: "No pude abrir los ajustes de $settingType."
        }
    }
}

class GetDeviceInfoTool(
    private val appManagerProvider: () -> AppManager? = { AppContainer.appManager }
) : Tool {
    override val name = "get_device_info"
    override val description = "Consulta información técnica del dispositivo: modelo, versión de Android o nivel de batería"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val appMgr = appManagerProvider() ?: return "Información del dispositivo no disponible."
        val queryType = params["queryType"]?.toString()
            ?: params["type"]?.toString()
            ?: params["query"]?.toString()
            ?: "all"

        val lower = queryType.lowercase()
        return when {
            lower.contains("bater") || lower.contains("battery") -> {
                val batteryResult = appMgr.getBatteryLevel()
                if (batteryResult.isSuccess) {
                    "Nivel de batería: ${batteryResult.getOrNull()}%"
                } else {
                    batteryResult.exceptionOrNull()?.message ?: "No fue posible consultar el nivel de batería."
                }
            }
            lower.contains("model") -> "Modelo: ${appMgr.getDeviceModel()}"
            lower.contains("version") || lower.contains("versión") || lower.contains("android") -> "Versión: ${appMgr.getAndroidVersion()}"
            else -> {
                val batteryStr = appMgr.getBatteryLevel().getOrNull()?.let { " | Batería: $it%" } ?: ""
                "Modelo: ${appMgr.getDeviceModel()} | Versión: ${appMgr.getAndroidVersion()}$batteryStr"
            }
        }
    }
}

class CheckAppInstalledTool(
    private val appManagerProvider: () -> AppManager? = { AppContainer.appManager }
) : Tool {
    override val name = "check_app_installed"
    override val description = "Comprueba si una aplicación está instalada en el dispositivo"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val packageName = params["packageName"]?.toString()
            ?: params["query"]?.toString()
            ?: return "No encontré esa aplicación instalada."

        val appMgr = appManagerProvider() ?: return "No encontré esa aplicación instalada."

        val isInstalled = appMgr.isAppInstalled(packageName)
        return if (isInstalled) {
            "La aplicación '$packageName' está instalada."
        } else {
            "No encontré esa aplicación instalada."
        }
    }
}

class LaunchAppTool(
    private val appManagerProvider: () -> AppManager? = { AppContainer.appManager }
) : Tool {
    override val name = "launch_app"
    override val description = "Abre una aplicación instalada en el dispositivo"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(params: Map<String, Any>): String {
        val packageName = params["packageName"]?.toString()
            ?: params["query"]?.toString()
            ?: return "No pude abrir esa aplicación."

        val appMgr = appManagerProvider() ?: return "No pude abrir esa aplicación."

        if (!appMgr.isAppInstalled(packageName)) {
            return "No encontré esa aplicación instalada."
        }

        val result = appMgr.launchApp(packageName)
        return if (result.isSuccess) {
            "Abriendo $packageName..."
        } else {
            result.exceptionOrNull()?.message ?: "No pude abrir esa aplicación."
        }
    }
}
