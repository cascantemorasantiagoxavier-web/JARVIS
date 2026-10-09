package com.example.jarvis.domain.ai

import android.util.Log
import com.example.jarvis.ai.context.BusinessResultMemory
import com.example.jarvis.ai.research.ResearchEngine
import com.example.jarvis.ai.research.ResearchRequest
import com.example.jarvis.core.di.JarvisBrain
import com.example.jarvis.core.network.NetworkMonitor
import com.example.jarvis.data.local.ai.GemmaLocalAiProvider
import com.example.jarvis.data.remote.ai.ChatMessagePayload
import com.example.jarvis.data.remote.ai.GeminiApiProvider
import com.example.jarvis.data.remote.ai.OpenAiApiProvider

enum class AiProviderMode {
    AUTOMATIC,
    ONLINE_GEMINI,
    ONLINE_OPENAI,
    LOCAL_GEMMA
}

class HybridAiProvider(
    val openAiProvider: OpenAiApiProvider = OpenAiApiProvider(),
    val geminiProvider: GeminiApiProvider = GeminiApiProvider(),
    val localAiProvider: LocalAiProvider = LocalAiProvider(),
    val gemmaLocalProvider: GemmaLocalAiProvider = GemmaLocalAiProvider(),
    val researchEngine: ResearchEngine = ResearchEngine(),
    var networkMonitor: NetworkMonitor? = null,
    var mode: AiProviderMode = AiProviderMode.AUTOMATIC
) : AiProvider {

    private val router = HybridBrainRouter()

    override suspend fun createSession(): Result<AiSession> {
        val activeProvider = selectActiveProvider()
        return activeProvider.createSession()
    }

    override suspend fun sendText(session: AiSession, text: String): Result<Unit> {
        val activeProvider = selectActiveProvider()
        return activeProvider.sendText(session, text)
    }

    override suspend fun disconnect(session: AiSession) {
        openAiProvider.disconnect(session)
        geminiProvider.disconnect(session)
        localAiProvider.disconnect(session)
        gemmaLocalProvider.disconnect(session)
    }

    fun selectActiveProvider(): AiProvider {
        val isOnline = networkMonitor?.isConnected ?: true
        val isLocalAvailable = localAiProvider.isLocalAvailable() || gemmaLocalProvider.isModelAvailable()
        return when (mode) {
            AiProviderMode.LOCAL_GEMMA -> localAiProvider
            AiProviderMode.ONLINE_OPENAI -> openAiProvider
            AiProviderMode.ONLINE_GEMINI -> geminiProvider
            AiProviderMode.AUTOMATIC -> {
                if (isLocalAvailable && !isOnline) localAiProvider
                else if (isLocalAvailable && mode == AiProviderMode.LOCAL_GEMMA) localAiProvider
                else if (isOnline) geminiProvider
                else localAiProvider
            }
        }
    }

    suspend fun generateResponse(
        messages: List<ChatMessagePayload>,
        systemPrompt: String? = null,
        model: String? = null
    ): Result<String> {
        val startTime = System.currentTimeMillis()
        val isOnline = networkMonitor?.isConnected ?: true
        val isLocalAvailable = localAiProvider.isLocalAvailable() || gemmaLocalProvider.isModelAvailable()
        val lastUserMessage = messages.lastOrNull { it.role == "user" }?.content ?: ""

        val decision = router.route(lastUserMessage, isOnline)

        if (decision.routeType == RouteType.OFFLINE_GEMMA || !isOnline || mode == AiProviderMode.LOCAL_GEMMA) {
            if (isLocalAvailable) {
                val localRes = localAiProvider.generateResponse(messages, systemPrompt = systemPrompt)
                val latency = System.currentTimeMillis() - startTime
                println("[JARVIS_E2E] PROVIDER=GemmaLocalAiProvider")
                println("[JARVIS_E2E] MODEL=gemma-2b-it-gpu-int4.bin")
                Log.i("JARVIS_BRAIN", "[JARVIS_BRAIN] provider=LOCAL model=gemma-2b-it-gpu-int4.bin latency=${latency}ms")
                return localRes
            } else if (!isOnline) {
                return Result.failure(Exception("LOCAL_MODEL_MISSING: No hay conexión a internet y el modelo local Gemma no está disponible en el dispositivo."))
            }
        }

        var enrichedMessages = messages
        if (decision.routeType == RouteType.WEB_RESEARCH) {
            try {
                val researchResult = researchEngine.investigate(ResearchRequest(query = lastUserMessage, maxSources = 3))
                if (researchResult.sources.isNotEmpty()) {
                    val businessMemories = researchResult.sources.mapIndexed { idx, src ->
                        BusinessResultMemory(
                            id = "biz_${System.currentTimeMillis()}_$idx",
                            name = src.title.take(50),
                            category = if (lastUserMessage.contains("restaurante")) "restaurante" else if (lastUserMessage.contains("ferreteria")) "ferretería" else "negocio",
                            address = src.domain,
                            sourceUrl = src.url,
                            retrievedAt = System.currentTimeMillis()
                        )
                    }
                    JarvisBrain.contextManager.conversationContext.setBusinessSearchResults(businessMemories, lastUserMessage)

                    val sourcesText = buildString {
                        appendLine("\n[FUENTES WEB ACTUALIZADAS PARA CONSULTA]:")
                        researchResult.sources.forEach { src ->
                            appendLine("- Título: ${src.title}")
                            appendLine("  URL: ${src.url}")
                            appendLine("  Dominio: ${src.domain}")
                        }
                    }
                    val updatedList = messages.toMutableList()
                    val lastIdx = updatedList.indexOfLast { it.role == "user" }
                    if (lastIdx >= 0) {
                        val original = updatedList[lastIdx]
                        updatedList[lastIdx] = ChatMessagePayload(original.role, "${original.content}\n\n$sourcesText")
                    }
                    enrichedMessages = updatedList
                }
            } catch (_: Exception) {}
        }

        val selectedModel = when (decision.routeType) {
            RouteType.GEMINI_FAST -> "gemini-flash-lite-latest"
            else -> model ?: "gemini-flash-lite-latest"
        }

        println("[JARVIS_E2E] PROVIDER=GeminiApiProvider")
        println("[JARVIS_E2E] MODEL=$selectedModel")

        val geminiRes = geminiProvider.generateResponse(enrichedMessages, systemPrompt = systemPrompt, model = selectedModel)
        val latency = System.currentTimeMillis() - startTime

        if (geminiRes.isSuccess) {
            Log.i("JARVIS_BRAIN", "[JARVIS_BRAIN] provider=GEMINI model=$selectedModel latency=${latency}ms")
            return geminiRes
        }

        if (isLocalAvailable) {
            val localRes = localAiProvider.generateResponse(messages, systemPrompt = systemPrompt)
            if (localRes.isSuccess) {
                println("[JARVIS_E2E] PROVIDER=GemmaLocalAiProvider")
                Log.i("JARVIS_BRAIN", "[JARVIS_BRAIN] provider=LOCAL model=gemma-2b-it-gpu-int4.bin latency=${System.currentTimeMillis() - startTime}ms")
                return localRes
            }
        }

        return geminiRes
    }
}
