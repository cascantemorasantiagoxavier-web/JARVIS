package com.example.jarvis.core.di

import android.content.Context
import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultIntentDetector
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.context.DefaultContextManager
import com.example.jarvis.ai.memory.DefaultAiMemoryManager
import com.example.jarvis.ai.memory.PersistenceMemoryHolder
import com.example.jarvis.ai.orchestrator.DefaultAgentSelector
import com.example.jarvis.ai.orchestrator.DefaultOrchestratorBrain
import com.example.jarvis.ai.orchestrator.DefaultToolSelector
import com.example.jarvis.ai.orchestrator.OrchestratorBrain
import com.example.jarvis.ai.orchestrator.models.OrchestratorRequest
import com.example.jarvis.core.device.AppManager
import com.example.jarvis.core.device.AppManagerImpl
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.core.network.NetworkMonitor
import com.example.jarvis.core.network.NetworkMonitorImpl
import com.example.jarvis.data.local.ai.GemmaLocalAiProvider
import com.example.jarvis.data.remote.ai.GeminiApiProvider
import com.example.jarvis.data.remote.ai.OpenAiApiProvider
import com.example.jarvis.domain.ai.HybridAiProvider
import com.example.jarvis.domain.ai.ThinkingMode
import com.example.jarvis.domain.ai.ThinkingRouter

/**
 * JarvisBrain serves as the centralized brain for JARVIS.
 * Both Chat and Voice routes pass through processRequest.
 */
object JarvisBrain {
    private val logger: AppLogger = ConsoleLogger()
    val localGemmaProvider = GemmaLocalAiProvider()
    val geminiProvider = GeminiApiProvider()
    val openAiProvider = OpenAiApiProvider()
    val hybridAiProvider = HybridAiProvider(
        openAiProvider = openAiProvider,
        geminiProvider = geminiProvider,
        gemmaLocalProvider = localGemmaProvider
    )

    val thinkingRouter = ThinkingRouter()

    val toolRegistry = DefaultToolRegistry()
    val agentRegistry = DefaultAgentRegistry()

    val intentDetector = DefaultIntentDetector()
    val contextManager = DefaultContextManager()
    val memoryManager = DefaultAiMemoryManager()
    val agentSelector = DefaultAgentSelector(agentRegistry)
    val toolSelector = DefaultToolSelector(toolRegistry)

    val orchestrator: OrchestratorBrain = DefaultOrchestratorBrain(
        intentDetector = intentDetector,
        contextManager = contextManager,
        memoryManager = memoryManager,
        agentSelector = agentSelector,
        toolSelector = toolSelector
    ).apply {
        setProvider(hybridAiProvider)
    }

    suspend fun processRequest(request: JarvisRequest): JarvisResponse {
        val startTime = System.currentTimeMillis()
        logger.i("JarvisBrain", "Processing request from ${request.source}: '${request.text}'")

        if (request.text.contains("diagnóstico completo", ignoreCase = true) || request.text.contains("diagnostico completo", ignoreCase = true)) {
            val diagReport = "JARVIS SYSTEM DIAGNOSTIC REPORT\n" +
                    "- Central Brain: ACTIVE\n" +
                    "- Hybrid AI Provider: ACTIVE (Gemini + Local Gemma)\n" +
                    "- Memory Repository: ${if (PersistenceMemoryHolder.repository != null) "READY" else "NOT_INITIALIZED"}\n" +
                    "- Network: ${if (AppContainer.networkMonitor?.isConnected == true) "ONLINE" else "OFFLINE"}"
            return JarvisResponse(
                text = diagReport,
                action = "system_diagnostic",
                toolUsed = "system_info",
                success = true,
                thinkingMode = ThinkingMode.FAST,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        val orchReq = OrchestratorRequest(input = request.text)
        val orchRes = orchestrator.processRequest(orchReq)
        val duration = System.currentTimeMillis() - startTime

        return JarvisResponse(
            text = orchRes.output,
            action = orchRes.toolSelection?.toolName,
            toolUsed = orchRes.toolSelection?.toolName,
            success = orchRes.isSuccess,
            thinkingMode = ThinkingMode.AUTO,
            memoryUpdated = orchRes.memoryDecision?.action != null,
            executionTimeMs = duration
        )
    }
}

object AppContainer {
    val logger: AppLogger by lazy { ConsoleLogger() }
    val brain: JarvisBrain by lazy { JarvisBrain }

    var appContext: Context? = null
    var appManager: AppManager? = null
    var networkMonitor: NetworkMonitor? = null

    fun initializeAppManager(context: Context) {
        if (appManager == null) {
            appContext = context.applicationContext
            appManager = AppManagerImpl(context.applicationContext)
            val netMon = NetworkMonitorImpl(context.applicationContext)
            networkMonitor = netMon
            JarvisBrain.localGemmaProvider.setApplicationContext(context.applicationContext)
            JarvisBrain.hybridAiProvider.networkMonitor = netMon
            PersistenceMemoryHolder.initialize(context.applicationContext)
        }
    }
}
