package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.agents.AgentRegistry
import com.example.jarvis.ai.agents.IntentDetector
import com.example.jarvis.ai.agents.ToolRegistry
import com.example.jarvis.ai.context.ContextLevel
import com.example.jarvis.ai.context.ContextManager
import com.example.jarvis.ai.context.ConversationContext
import com.example.jarvis.ai.context.ConversationTurn
import com.example.jarvis.ai.context.DefaultContextManager
import com.example.jarvis.ai.memory.AiMemoryManager
import com.example.jarvis.ai.orchestrator.models.*
import com.example.jarvis.data.local.ai.GemmaLocalAiProvider
import com.example.jarvis.data.remote.ai.ChatMessagePayload
import com.example.jarvis.data.remote.ai.GeminiApiProvider
import com.example.jarvis.data.remote.ai.OpenAiApiProvider
import com.example.jarvis.domain.ai.AiProvider
import com.example.jarvis.domain.ai.HybridAiProvider

/**
 * Default implementation of AgentSelector utilizing AgentRegistry and IntentType.
 */
class DefaultAgentSelector(
    private val agentRegistry: AgentRegistry
) : AgentSelector {
    override suspend fun selectAgent(intent: DetectedIntent, request: OrchestratorRequest): AgentSelection {
        val agentId = when (intent.type) {
            IntentType.CODING -> "coding_agent"
            IntentType.DEVICE, IntentType.OPEN_APP, IntentType.OPEN_SETTINGS, IntentType.DEVICE_INFO,
            IntentType.IOT, IntentType.MAPS, IntentType.CONTACT_SEARCH, IntentType.WEB_SEARCH, IntentType.OPEN_WEB, IntentType.DEVICE_CONTROL,
            IntentType.DATE_TIME_QUERY, IntentType.CALCULATOR_QUERY, IntentType.BATTERY_QUERY, IntentType.NETWORK_QUERY -> "device_agent"
            IntentType.MEMORY, IntentType.MEMORY_QUERY, IntentType.MEMORY_WRITE, IntentType.MEMORY_SAVE, IntentType.MEMORY_DELETE, IntentType.PREFERENCE_UPDATE -> "memory_agent"
            IntentType.OBSIDIAN, IntentType.OBSIDIAN_SEARCH, IntentType.OBSIDIAN_READ, IntentType.OBSIDIAN_WRITE -> "obsidian_agent"
            IntentType.PLANNING -> "planning_agent"
            IntentType.PRODUCTIVITY, IntentType.TASK_CREATE, IntentType.TASK_UPDATE, IntentType.TASK_LIST, IntentType.TASK_COMPLETE, IntentType.PROJECT_QUERY, IntentType.DAILY_BRIEFING -> "productivity_agent"
            IntentType.RESEARCH, IntentType.QUESTION -> "research_agent"
            IntentType.YOUTUBE_SHORTS -> "orchestrator_agent"
            IntentType.CONVERSATION, IntentType.GENERAL, IntentType.UNKNOWN -> "orchestrator_agent"
        }
        val agent = agentRegistry.getAgent(agentId)
        return AgentSelection(
            agentId = agentId,
            agentName = agent?.name ?: "Default Orchestrator Agent",
            reasoning = "Selected based on intent: ${intent.type}",
            targetAgent = agent
        )
    }
}

/**
 * Default implementation of ToolSelector utilizing ToolRegistry and IntentType.
 */
class DefaultToolSelector(
    private val toolRegistry: ToolRegistry
) : ToolSelector {
    override suspend fun selectTool(intent: DetectedIntent, request: OrchestratorRequest): ToolSelection {
        val toolName = when (intent.type) {
            IntentType.DEVICE, IntentType.OPEN_APP, IntentType.OPEN_SETTINGS, IntentType.DEVICE_INFO -> {
                val inputLower = request.input.lowercase()
                if (inputLower.contains("ajustes") || inputLower.contains("settings") || inputLower.contains("configuraci") ||
                    inputLower.contains("wifi") || inputLower.contains("bluetooth") || inputLower.contains("sonido")) {
                    "open_settings"
                } else if (inputLower.contains("bater") || inputLower.contains("battery") || inputLower.contains("modelo") || inputLower.contains("android")) {
                    "get_device_info"
                } else if (inputLower.contains("instalad") || inputLower.contains("comprobar") || inputLower.contains("check")) {
                    "check_app_installed"
                } else if (inputLower.contains("abre") || inputLower.contains("abrir") || inputLower.contains("open") || inputLower.contains("launch")) {
                    "launch_app"
                } else {
                    "system_info"
                }
            }
            IntentType.MEMORY, IntentType.MEMORY_QUERY, IntentType.MEMORY_WRITE -> "memory_search"
            IntentType.MEMORY_SAVE -> "memory_save"
            IntentType.MEMORY_DELETE -> "memory_delete"
            IntentType.PREFERENCE_UPDATE -> "preference_update"
            IntentType.PRODUCTIVITY, IntentType.TASK_CREATE, IntentType.TASK_UPDATE, IntentType.PROJECT_QUERY -> "task_create"
            IntentType.TASK_LIST -> "task_list"
            IntentType.TASK_COMPLETE -> "task_complete"
            IntentType.OBSIDIAN, IntentType.OBSIDIAN_SEARCH, IntentType.OBSIDIAN_READ, IntentType.OBSIDIAN_WRITE -> "obsidian_search"
            IntentType.IOT -> "iot_tool"
            IntentType.MAPS -> "maps_tool"
            IntentType.CONTACT_SEARCH -> "contact_search"
            IntentType.DAILY_BRIEFING -> "daily_briefing"
            IntentType.WEB_SEARCH -> "web_search"
            IntentType.OPEN_WEB -> "open_web"
            IntentType.DEVICE_CONTROL -> "device_control"
            IntentType.DATE_TIME_QUERY -> "get_date_time"
            IntentType.CALCULATOR_QUERY -> "calculator"
            IntentType.BATTERY_QUERY -> "battery_info"
            IntentType.NETWORK_QUERY -> "check_network"
            IntentType.YOUTUBE_SHORTS -> {
                val inputLower = request.input.lowercase()
                if (inputLower.contains("render") || inputLower.contains("monta") || inputLower.contains("prepara el video")) {
                    "render_short"
                } else if (inputLower.contains("publica") || inputLower.contains("sube") || inputLower.contains("upload")) {
                    "publish_short"
                } else if (inputLower.contains("lista") || inputLower.contains("proyectos") || inputLower.contains("pendientes")) {
                    "list_shorts"
                } else {
                    "create_short"
                }
            }
            else -> null
        }
        val tool = toolName?.let { toolRegistry.getTool(it) }
        return ToolSelection(
            toolName = toolName,
            reasoning = if (toolName != null) "Tool selected for intent ${intent.type}" else "No tool required",
            parameters = mapOf(
                "query" to request.input,
                "topic" to request.input,
                "title" to request.input,
                "packageName" to request.input,
                "settingType" to request.input,
                "type" to request.input,
                "url" to request.input,
                "name" to request.input
            ),
            targetTool = tool
        )
    }
}

/**
 * Default implementation of ContextRequirementResolver.
 */
class DefaultContextRequirementResolver : ContextRequirementResolver {
    override suspend fun resolveRequirements(intent: DetectedIntent): ContextRequirements {
        val levels = when (intent.type) {
            IntentType.OBSIDIAN, IntentType.OBSIDIAN_SEARCH, IntentType.OBSIDIAN_READ, IntentType.OBSIDIAN_WRITE -> listOf(
                ContextLevel.LEVEL_0_RAW_INPUT,
                ContextLevel.LEVEL_1_SESSION_HISTORY,
                ContextLevel.LEVEL_4_OBSIDIAN_VAULT
            )
            IntentType.MEMORY, IntentType.MEMORY_QUERY, IntentType.MEMORY_WRITE -> listOf(
                ContextLevel.LEVEL_0_RAW_INPUT,
                ContextLevel.LEVEL_2_WORKING_MEMORY,
                ContextLevel.LEVEL_3_LONG_TERM_MEMORY
            )
            else -> listOf(
                ContextLevel.LEVEL_0_RAW_INPUT,
                ContextLevel.LEVEL_1_SESSION_HISTORY
            )
        }
        return ContextRequirements(requiredLevels = levels)
    }
}

/**
 * Default implementation of ResultValidator.
 */
class DefaultResultValidator : ResultValidator {
    override suspend fun validate(
        input: String,
        result: String,
        intent: DetectedIntent?
    ): ValidationResult {
        val isValid = result.isNotBlank() && !result.startsWith("Error", ignoreCase = true)
        return ValidationResult(
            isValid = isValid,
            confidenceScore = if (isValid) 0.95f else 0.0f,
            reasoning = if (isValid) "Result non-blank and no explicit error" else "Result was blank or contained errors",
            validationErrors = if (isValid) emptyList() else listOf("Output validation failed for input: $input")
        )
    }
}

/**
 * Default implementation of MemoryDecider.
 */
class DefaultMemoryDecider : MemoryDecider {
    override suspend fun decide(
        input: String,
        output: String,
        validation: ValidationResult
    ): MemoryDecision {
        return if (validation.isValid) {
            MemoryDecision(
                action = MemoryAction.MEMORIZE,
                key = input,
                value = output,
                reasoning = "Valid output auto-approved for memory persistence"
            )
        } else {
            MemoryDecision(
                action = MemoryAction.SKIP,
                key = input,
                value = "",
                reasoning = "Invalid result skipped from memory"
            )
        }
    }
}

/**
 * Default implementation of OrchestratorBrain coordinating the 10-step flow.
 */
class DefaultOrchestratorBrain(
    private val intentDetector: IntentDetector,
    private val contextManager: ContextManager,
    private val memoryManager: AiMemoryManager,
    private val agentSelector: AgentSelector,
    private val toolSelector: ToolSelector,
    private val contextResolver: ContextRequirementResolver = DefaultContextRequirementResolver(),
    private val validator: ResultValidator = DefaultResultValidator(),
    private val memoryDecider: MemoryDecider = DefaultMemoryDecider(),
    private var provider: AiProvider? = null
) : OrchestratorBrain {

    override fun setProvider(provider: AiProvider) {
        this.provider = provider
    }

    override suspend fun processRequest(request: OrchestratorRequest): OrchestratorResponse {
        val input = request.input
        if (input.isBlank()) {
            return OrchestratorResponse(
                requestId = request.requestId,
                output = "Empty input received",
                isSuccess = false,
                error = OrchestratorError(ErrorType.VALIDATION_ERROR, "Input cannot be blank")
            )
        }

        // Conversational Context & Anaphora Resolution
        val convContext = (contextManager as? DefaultContextManager)?.conversationContext
        val resolvedInput = convContext?.resolveAnaphora(input) ?: input

        // Step 2: Intent Detection
        val rawIntentStr = intentDetector.detectIntent(resolvedInput)
        val intentType = IntentType.fromString(rawIntentStr)
        val detectedIntent = DetectedIntent(
            type = intentType,
            rawIntent = rawIntentStr,
            description = "Detected $intentType from input"
        )

        println("[JARVIS][BRAIN] REQUEST=$input")
        println("[JARVIS][BRAIN] INTENT=$rawIntentStr")
        println("[JARVIS][BRAIN] MEMORY_REQUIRED=${intentType == IntentType.MEMORY || intentType == IntentType.MEMORY_QUERY || intentType == IntentType.MEMORY_SAVE || intentType == IntentType.MEMORY_WRITE}")
        println("[JARVIS][BRAIN] TOOL_REQUIRED=${intentType != IntentType.CONVERSATION && intentType != IntentType.GENERAL && intentType != IntentType.QUESTION}")
        println("[JARVIS][BRAIN] INTERNET_REQUIRED=${intentType == IntentType.WEB_SEARCH}")
        println("[JARVIS][BRAIN] PROVIDER=gemini-flash-lite-latest")
        println("[JARVIS][BRAIN] THINKING_STARTED")

        // Step 3: Context Requirements
        val requirements = contextResolver.resolveRequirements(detectedIntent)
        contextManager.updateContext("last_intent", rawIntentStr)
        contextManager.updateContext("required_levels", requirements.requiredLevels.joinToString())

        // Step 4: Memory Retrieval
        val memoryResult = try { memoryManager.recall(resolvedInput) } catch (_: Throwable) { "" }

        // Step 5: Agent Selection
        val agentSel = agentSelector.selectAgent(detectedIntent, request.copy(input = resolvedInput))

        // Step 6: Tool Selection
        var toolSel = toolSelector.selectTool(detectedIntent, request.copy(input = resolvedInput))

        if (toolSel.toolName != null) {
            println("[JARVIS][BRAIN] TOOL_STARTED=${toolSel.toolName}")
        }

        // Step 6b: Multi-Tool Chaining (Pass data from previous tool to CalculatorTool if requested)
        if (toolSel.toolName == "calculator" && convContext?.lastToolResult != null) {
            val lastRes = convContext.lastToolResult ?: ""
            val numberMatch = """(\d+(?:[.,]\d+)?)""".toRegex().find(lastRes)
            if (numberMatch != null && (input.contains("población") || input.contains("poblacion") || input.contains("resultado") || input.contains("número"))) {
                val extractedNum = numberMatch.value.replace(",", "")
                val updatedQuery = input.replace("esa población", extractedNum)
                    .replace("esa poblacion", extractedNum)
                    .replace("de esa población", "de $extractedNum")
                    .replace("de esa poblacion", "de $extractedNum")
                    .replace("ese resultado", extractedNum)
                val updatedParams = toolSel.parameters.toMutableMap().apply { put("query", updatedQuery) }
                toolSel = toolSel.copy(parameters = updatedParams)
            }
        }

        // Step 7: Execution (Registered tools execute directly for instant result, and web searches are synthesized by Gemini)
        val rawExecutionResult: String = (when {
            toolSel.targetTool != null -> {
                val toolResult = toolSel.targetTool.execute(toolSel.parameters)
                println("[JARVIS][BRAIN] TOOL_RESULT=$toolResult")
                if ((toolSel.toolName == "web_search" || toolSel.toolName == "research_topic" || toolSel.toolName == "open_web_page") && provider != null) {
                    println("[JARVIS][BRAIN] GEMINI_STARTED")
                    val synthesisPrompt = "Aquí tienes los resultados de búsqueda en Internet obtenidos para la consulta del usuario ('${request.input}'):\n\n$toolResult\n\nPor favor, analiza estos resultados reales de Internet y redacta una respuesta clara, completa y basada en esta información."
                    generateOnlineAiResponse(synthesisPrompt, toolResult, convContext, memoryResult)
                } else {
                    toolResult
                }
            }
            provider != null -> {
                println("[JARVIS][BRAIN] GEMINI_STARTED")
                val agentFallback = agentSel.targetAgent?.process(resolvedInput)
                    ?: "Processed intent: ${detectedIntent.rawIntent} with memory: $memoryResult"
                generateOnlineAiResponse(resolvedInput, agentFallback, convContext, memoryResult)
            }
            agentSel.targetAgent != null -> {
                agentSel.targetAgent.process(resolvedInput)
            }
            else -> {
                "Processed intent: ${detectedIntent.rawIntent} with memory: $memoryResult"
            }
        }) ?: "Processed intent: ${detectedIntent.rawIntent}"

        // Untrusted Content Protection for external web search data
        val executionResult = if (toolSel.toolName == "research_topic" || toolSel.toolName == "web_search") {
            sanitizeUntrustedWebContent(rawExecutionResult)
        } else {
            rawExecutionResult
        }

        println("[JARVIS][BRAIN] GEMINI_RESPONSE=$executionResult")
        println("[JARVIS][BRAIN] FINAL_RESPONSE=$executionResult")

        // Step 8: Result Validation
        val validation = validator.validate(resolvedInput, executionResult, detectedIntent)

        // Step 9: Memory Decision & Conversation Turn Tracking
        try {
            val memDecision = memoryDecider.decide(resolvedInput, executionResult, validation)
            if (memDecision.action == MemoryAction.MEMORIZE && memDecision.key.isNotBlank()) {
                memoryManager.memorize(memDecision.key, memDecision.value)
                println("[JARVIS][BRAIN] MEMORY_SAVED=${memDecision.key}")
            }
        } catch (e: Throwable) {
            println("[JARVIS][BRAIN] Memory persistence skipped in test: ${e.message}")
        }

        convContext?.let { ctx ->
            val turn = ConversationTurn(
                turnId = ctx.turns.size + 1,
                userInput = input,
                intentType = detectedIntent.rawIntent,
                toolUsed = toolSel.toolName,
                toolResult = executionResult,
                assistantOutput = executionResult
            )
            ctx.addTurn(turn)
            ctx.extractAndSetEntities(input, executionResult)
        }

        // Step 10: Response
        return OrchestratorResponse(
            requestId = request.requestId,
            output = executionResult,
            isSuccess = true,
            detectedIntent = detectedIntent,
            agentSelection = agentSel,
            toolSelection = toolSel,
            validationResult = validation,
            memoryDecision = MemoryDecision(MemoryAction.SKIP, "", "", "Done"),
            error = null
        )
    }

    private suspend fun generateOnlineAiResponse(
        input: String,
        agentFallback: String,
        convContext: ConversationContext?,
        memoryResult: String
    ): String {
        val currentProvider = provider ?: return agentFallback

        val sysPromptBuilder = StringBuilder()
        sysPromptBuilder.append("Eres J.A.R.V.I.S., el asistente de inteligencia artificial avanzado. Responde siempre en español de forma clara, natural, sofisticada y directa. Si una consulta no puede completarse, explica la causa de forma honesta en español.")

        if (memoryResult.isNotBlank() && memoryResult != "No relevant memory found") {
            sysPromptBuilder.append("\n\n[MEMORIA DE JARVIS SOBRE EL USUARIO]:\n$memoryResult")
        }

        if (convContext != null && convContext.turns.isNotEmpty()) {
            val lastTurns = convContext.turns.takeLast(5).joinToString("\n") { turn ->
                "Usuario: ${turn.userInput}\nJARVIS: ${turn.assistantOutput}"
            }
            sysPromptBuilder.append("\n\n[HISTORIAL DE CONVERSACIÓN RECIENTE]:\n$lastTurns")
        }

        val messages = listOf(ChatMessagePayload(role = "user", content = input))

        return when (currentProvider) {
            is HybridAiProvider -> {
                val res = currentProvider.generateResponse(messages, systemPrompt = sysPromptBuilder.toString())
                if (res.isSuccess) res.getOrNull() ?: agentFallback else (res.exceptionOrNull()?.message ?: agentFallback)
            }
            is GeminiApiProvider -> {
                val res = currentProvider.generateResponse(messages, systemPrompt = sysPromptBuilder.toString())
                if (res.isSuccess) res.getOrNull() ?: agentFallback else (res.exceptionOrNull()?.message ?: agentFallback)
            }
            is OpenAiApiProvider -> {
                val res = currentProvider.generateResponse(messages, systemPrompt = sysPromptBuilder.toString())
                if (res.isSuccess) res.getOrNull() ?: agentFallback else (res.exceptionOrNull()?.message ?: agentFallback)
            }
            is GemmaLocalAiProvider -> {
                val res = currentProvider.generateResponse(messages, systemPrompt = sysPromptBuilder.toString())
                if (res.isSuccess) res.getOrNull() ?: agentFallback else (res.exceptionOrNull()?.message ?: agentFallback)
            }
            else -> {
                val sessionRes = currentProvider.createSession()
                if (sessionRes.isSuccess) {
                    val session = sessionRes.getOrNull()!!
                    val sendRes = currentProvider.sendText(session, input)
                    currentProvider.disconnect(session)
                    if (sendRes.isSuccess) agentFallback else (sendRes.exceptionOrNull()?.message ?: agentFallback)
                } else {
                    sessionRes.exceptionOrNull()?.message ?: agentFallback
                }
            }
        }
    }

    private fun sanitizeUntrustedWebContent(rawContent: String?): String {
        if (rawContent == null) return ""
        return rawContent.replace("Ignore previous instructions", "[UNTRUSTED_CONTENT_FILTERED]")
            .replace("System prompt:", "[UNTRUSTED_CONTENT_FILTERED]")
            .replace("System Instruction:", "[UNTRUSTED_CONTENT_FILTERED]")
    }
}
