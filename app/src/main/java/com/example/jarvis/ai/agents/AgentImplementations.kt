package com.example.jarvis.ai.agents

import com.example.jarvis.ai.communications.CommunicationDraft
import com.example.jarvis.ai.communications.CommunicationMessage
import com.example.jarvis.ai.communications.CommunicationsEngine
import com.example.jarvis.ai.memory.DefaultMemoryRepository
import com.example.jarvis.ai.planning.DefaultPlanningEngine
import com.example.jarvis.ai.productivity.DefaultTaskRepository
import com.example.jarvis.core.common.AppResult
import com.example.jarvis.core.device.AppManager
import com.example.jarvis.core.di.AppContainer
import com.example.jarvis.domain.agents.*
import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryLevel
import com.example.jarvis.domain.memory.models.MemoryQuery
import com.example.jarvis.domain.planning.Goal
import com.example.jarvis.domain.planning.Plan
import com.example.jarvis.domain.tasks.Task
import com.example.jarvis.domain.tasks.TaskPriority

class CodingAgentImpl : CodingAgent {
    override val id = "coding_agent"
    override val name = "Coding Agent"
    override suspend fun process(input: String): String = reviewCode(input)
    override suspend fun reviewCode(code: String): String = "Code review completed for: $code"
}

class DeviceAgentImpl(
    private val appManagerProvider: () -> AppManager? = { AppContainer.appManager }
) : DeviceAgent {
    override val id = "device_agent"
    override val name = "Device Agent"

    override suspend fun process(input: String): String {
        val cleanInput = input.trim()
        val lower = cleanInput.lowercase()

        val appMgr = appManagerProvider()

        // Battery level request
        if (lower.contains("bater") || lower.contains("battery")) {
            return getBatteryLevel()
        }

        // Device model / Android version / system info request
        if (lower.contains("modelo") || lower.contains("versión") || lower.contains("version") ||
            (lower.contains("info") && lower.contains("dispositivo")) || lower.contains("android")) {
            return getDeviceInfo()
        }

        // Settings opening requests
        if (lower.contains("ajustes") || lower.contains("settings") || lower.contains("configuraci")) {
            val settingType = when {
                lower.contains("wifi") || lower.contains("wi-fi") -> "wifi"
                lower.contains("bluetooth") -> "bluetooth"
                lower.contains("sonido") || lower.contains("sound") || lower.contains("audio") -> "sound"
                else -> "general"
            }
            val result = openSettings(settingType)
            return if (result.isSuccess) {
                "Abriendo ajustes de $settingType..."
            } else {
                result.exceptionOrNull()?.message ?: "No pude abrir los ajustes de $settingType."
            }
        }

        // Check app installation or launch app
        val isCheckOnly = lower.contains("instalad") || lower.contains("comprobar") || lower.contains("check") || lower.contains("installed")
        val appNameOrPackage = extractAppName(cleanInput)

        if (appMgr == null) {
            return if (isCheckOnly) "No encontré esa aplicación instalada." else "No pude abrir esa aplicación."
        }

        if (isCheckOnly) {
            val installed = isAppInstalled(appNameOrPackage)
            return if (installed) {
                "La aplicación '$appNameOrPackage' está instalada."
            } else {
                "No encontré esa aplicación instalada."
            }
        }

        if (!appMgr.isAppInstalled(appNameOrPackage)) {
            return "No encontré esa aplicación instalada."
        }

        val launchResult = launchApp(appNameOrPackage)
        return if (launchResult.isSuccess) {
            "Abriendo $appNameOrPackage..."
        } else {
            launchResult.exceptionOrNull()?.message ?: "No pude abrir esa aplicación."
        }
    }

    override suspend fun controlDevice(action: String): Boolean {
        if (appManagerProvider() == null) return false
        val lower = action.lowercase()
        return if (lower.contains("bater") || lower.contains("info") || lower.contains("modelo")) {
            true
        } else if (lower.contains("ajustes") || lower.contains("settings")) {
            val settingType = when {
                lower.contains("wifi") -> "wifi"
                lower.contains("bluetooth") -> "bluetooth"
                lower.contains("sonido") || lower.contains("sound") -> "sound"
                else -> "general"
            }
            openSettings(settingType).isSuccess
        } else {
            val appName = extractAppName(action)
            if (lower.contains("instalad") || lower.contains("check")) {
                isAppInstalled(appName)
            } else {
                if (!isAppInstalled(appName)) false
                else launchApp(appName).isSuccess
            }
        }
    }

    override suspend fun isAppInstalled(packageName: String): Boolean {
        return appManagerProvider()?.isAppInstalled(packageName) ?: false
    }

    override suspend fun launchApp(packageName: String): Result<Unit> {
        return appManagerProvider()?.launchApp(packageName)
            ?: Result.failure(IllegalStateException("AppManager no disponible."))
    }

    override suspend fun openSettings(settingType: String): Result<Unit> {
        return appManagerProvider()?.openSettings(settingType)
            ?: Result.failure(IllegalStateException("AppManager no disponible."))
    }

    override suspend fun getDeviceInfo(): String {
        val appMgr = appManagerProvider() ?: return "Información del dispositivo no disponible."
        val model = appMgr.getDeviceModel()
        val version = appMgr.getAndroidVersion()
        return "Modelo: $model | Versión: $version"
    }

    override suspend fun getBatteryLevel(): String {
        val appMgr = appManagerProvider() ?: return "No fue posible consultar el nivel de batería."
        val batteryResult = appMgr.getBatteryLevel()
        return if (batteryResult.isSuccess) {
            "Nivel de batería: ${batteryResult.getOrNull()}%"
        } else {
            batteryResult.exceptionOrNull()?.message ?: "No fue posible consultar el nivel de batería."
        }
    }

    private fun extractAppName(input: String): String {
        val prefixes = listOf(
            "comprobar si ", "comprobar ", "is ", "check if ", "check ",
            "está instalada la app ", "esta instalada la app ", "está instalada ", "esta instalada ", "installed ",
            "jarvis, abre ", "jarvis abre ", "jarvis, abrir ", "jarvis abrir ",
            "abre la app ", "abre el ", "abre ", "abrir la app ", "abrir el ", "abrir ",
            "open the app ", "open ", "launch "
        )
        var result = input
        for (prefix in prefixes) {
            if (result.startsWith(prefix, ignoreCase = true)) {
                result = result.substring(prefix.length).trim()
            }
        }
        val suffixes = listOf(" está instalada", " esta instalada", " is installed", " app")
        for (suffix in suffixes) {
            if (result.endsWith(suffix, ignoreCase = true)) {
                result = result.substring(0, result.length - suffix.length).trim()
            }
        }
        return if (result.isBlank()) input else result
    }
}

class MemoryAgentImpl(
    private val memoryRepository: DefaultMemoryRepository = DefaultMemoryRepository()
) : MemoryAgent {
    override val id = "memory_agent"
    override val name = "Memory Agent"

    override suspend fun process(input: String): String {
        organizeMemories()
        val queryResult = searchMemory(input)
        return if (queryResult.isNotEmpty()) {
            "Encontré ${queryResult.size} recuerdos relacionados: ${queryResult.first().content}"
        } else {
            "Procesé tu solicitud de memoria para: $input"
        }
    }

    override suspend fun organizeMemories() {}

    override suspend fun searchMemory(query: String, level: MemoryLevel): List<MemoryEntry> {
        val result = memoryRepository.search(MemoryQuery(query = query, targetLevels = listOf(level)))
        return when (result) {
            is AppResult.Success -> result.data.entries
            else -> emptyList()
        }
    }

    override suspend fun memorize(content: String, level: MemoryLevel, importance: Float): String {
        val entry = MemoryEntry(content = content, level = level, importance = importance)
        memoryRepository.save(entry)
        return entry.id
    }

    override suspend fun updateMemory(id: String, newContent: String): Boolean {
        val getRes = memoryRepository.get(id)
        if (getRes is AppResult.Success && getRes.data != null) {
            val updated = getRes.data.copy(content = newContent)
            return memoryRepository.update(updated) is AppResult.Success
        }
        return false
    }

    override suspend fun detectDuplicates(content: String): Boolean {
        val search = memoryRepository.search(MemoryQuery(query = content))
        return when (search) {
            is AppResult.Success -> search.data.entries.any { it.content.equals(content.trim(), ignoreCase = true) }
            else -> false
        }
    }

    override suspend fun detectConflicts(content: String): MemoryEntry? {
        val search = memoryRepository.search(MemoryQuery(query = content))
        return when (search) {
            is AppResult.Success -> search.data.entries.firstOrNull { it.content.contains(content, ignoreCase = true) }
            else -> null
        }
    }
}

class ObsidianAgentImpl : ObsidianAgent {
    override val id = "obsidian_agent"
    override val name = "Obsidian Agent"
    override suspend fun process(input: String): String {
        syncWithVault()
        return "Obsidian vault synced"
    }
    override suspend fun syncWithVault() {}
}

class CommunicationAgentImpl(
    private val engine: CommunicationsEngine = CommunicationsEngine()
) : CommunicationAgent {
    override val id = "communication_agent"
    override val name = "Communication Agent"

    override suspend fun process(input: String): String {
        val lower = input.lowercase()
        return if (lower.contains("busca") || lower.contains("search") || lower.contains("último mensaje")) {
            val results = engine.searchAll(input)
            if (results.isNotEmpty()) {
                "Encontré ${results.size} mensajes relacionados: ${results.first().body}"
            } else {
                "No encontré mensajes en las comunicaciones."
            }
        } else if (lower.contains("responde") || lower.contains("prepara") || lower.contains("draft")) {
            "Preparé el borrador de respuesta. ¿Quieres que lo envíe?"
        } else {
            val results = engine.searchAll(input)
            "Procesé tu consulta de comunicaciones: ${results.size} resultados encontrados."
        }
    }

    override suspend fun searchCommunications(query: String): String {
        val results = engine.searchAll(query)
        return results.joinToString("\n") { "[${it.provider}] ${it.sender}: ${it.body}" }
    }

    override suspend fun prepareDraft(provider: String, conversationId: String, body: String): String {
        val draft = CommunicationDraft(provider = provider, conversationId = conversationId, body = body)
        val created = engine.createDraft(provider, draft)
        return created.id
    }

    override suspend fun sendMessage(provider: String, conversationId: String, body: String, confirmed: Boolean): Boolean {
        if (!confirmed) {
            throw SecurityException("External send message requires explicit user confirmation.")
        }
        val msg = CommunicationMessage(
            conversationId = conversationId,
            provider = provider,
            sender = "user",
            body = body
        )
        return engine.sendMessage(provider, msg, confirmed, token = null)
    }
}

class OrchestratorAgentImpl : OrchestratorAgent {
    override val id = "orchestrator_agent"
    override val name = "Orchestrator Agent"
    override suspend fun process(input: String): String = executeTask(AgentRequest("req_1", input)).output
    override suspend fun executeTask(request: AgentRequest): AgentResponse =
        AgentResponse(request.requestId, "Orchestrated response for: ${request.input}", true)
}

class PlanningAgentImpl(
    private val planningEngine: DefaultPlanningEngine = DefaultPlanningEngine()
) : PlanningAgent {
    override val id = "planning_agent"
    override val name = "Planning Agent"

    override suspend fun process(input: String): String {
        val plan = planGoal(input)
        return "Plan created with ${plan.steps.size} steps for goal: ${input}"
    }

    override suspend fun createPlan(goal: String): List<String> {
        val plan = planGoal(goal)
        return plan.steps.map { "${it.stepNumber}. ${it.title}" }
    }

    override suspend fun planGoal(goalTitle: String, description: String): Plan {
        val result = planningEngine.generatePlan(Goal(title = goalTitle, description = description))
        return (result as AppResult.Success).data
    }
}

class ProductivityAgentImpl(
    private val taskRepository: DefaultTaskRepository = DefaultTaskRepository()
) : ProductivityAgent {
    override val id = "productivity_agent"
    override val name = "Productivity Agent"

    override suspend fun process(input: String): String {
        val created = createTask(input)
        return "Created productivity task: ${created.title} (ID: ${created.id})"
    }

    override suspend fun manageTasks() {}

    override suspend fun createTask(title: String, description: String, priority: TaskPriority): Task {
        val newTask = Task(title = title, description = description, priority = priority)
        val result = taskRepository.create(newTask)
        return (result as AppResult.Success).data
    }

    override suspend fun completeTask(taskId: String): Boolean {
        val result = taskRepository.complete(taskId)
        return result is AppResult.Success
    }

    override suspend fun searchTasks(query: String): List<Task> {
        val result = taskRepository.search(query)
        return (result as AppResult.Success).data
    }
}

class ResearchAgentImpl : ResearchAgent {
    override val id = "research_agent"
    override val name = "Research Agent"
    override suspend fun process(input: String): String = researchTopic(input)
    override suspend fun researchTopic(topic: String): String = "Research findings on $topic"
}
