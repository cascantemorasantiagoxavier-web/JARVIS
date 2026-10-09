package com.example.jarvis.ai.tools

import android.content.Context
import com.example.jarvis.ai.shorts.ShortsScriptGenerator
import com.example.jarvis.ai.shorts.render.ShortsRenderEngine
import com.example.jarvis.ai.shorts.youtube.YouTubeUploadService
import com.example.jarvis.data.shorts.ShortsRepository
import com.example.jarvis.domain.shorts.ShortProject
import com.example.jarvis.domain.shorts.ShortStatus
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class CreateShortTool(private val context: Context) : Tool {
    override val name = "create_short"
    override val description = "Crea un nuevo proyecto de YouTube Short (investigación, guion y metadata)"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val topic = params["topic"]?.toString() ?: params["query"]?.toString() ?: return "Debes especificar un tema para el Short."
        val duration = params["duration"]?.toString()?.toIntOrNull() ?: 30

        val repository = ShortsRepository(context)
        val script = ShortsScriptGenerator.generateScript(topic, duration)
        val metadata = ShortsScriptGenerator.generateMetadata(topic, script)

        val project = ShortProject(
            topic = topic,
            status = ShortStatus.SCRIPT_READY,
            script = script,
            metadata = metadata,
            targetDurationSeconds = duration
        )

        repository.saveProject(project)

        return "🎬 ¡Short Creado Exitosamente!\n" +
                "ID: ${project.id}\n" +
                "Título: ${metadata.title}\n" +
                "Hook: ${script.hook}\n" +
                "Duración: $duration segundos\n" +
                "Hashtags: ${metadata.hashtags.joinToString(" ")}\n\n" +
                "Siguiente paso: Renderizar el video vertical."
    }
}

class RenderShortTool(private val context: Context) : Tool {
    override val name = "render_short"
    override val description = "Renderiza el video vertical 1080x1920 MP4 para un Short"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val repository = ShortsRepository(context)
        val projects = repository.getAllProjects()
        val project = projects.firstOrNull { it.status == ShortStatus.SCRIPT_READY || it.status == ShortStatus.DRAFT }
            ?: return "No hay proyectos de Shorts pendientes de renderizar."

        repository.updateStatus(project.id, ShortStatus.RENDERING)
        val renderResult = ShortsRenderEngine.renderShortVideo(context, project)

        return if (renderResult.isSuccess) {
            val file = renderResult.getOrThrow()
            repository.saveProject(project.copy(status = ShortStatus.RENDERED, videoPath = file.absolutePath))
            "📹 Renderizado Completado:\n" +
                    "Video MP4: ${file.name}\n" +
                    "Resolución: 1080x1920 (9:16 Vertical)\n" +
                    "Tamaño: ${file.length()} bytes\n" +
                    "Estado: Listo para vista previa y publicación."
        } else {
            repository.updateStatus(project.id, ShortStatus.FAILED, renderResult.exceptionOrNull()?.message)
            "Error al renderizar el Short: ${renderResult.exceptionOrNull()?.message}"
        }
    }
}

class PublishShortTool(private val context: Context) : Tool {
    override val name = "publish_short"
    override val description = "Publica un Short renderizado en la cuenta oficial de YouTube"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(params: Map<String, Any>): String {
        val repository = ShortsRepository(context)
        val projects = repository.getAllProjects()
        val project = projects.firstOrNull { it.status == ShortStatus.RENDERED || it.status == ShortStatus.READY_TO_PUBLISH }
            ?: return "No hay proyectos de Shorts renderizados listos para publicar."

        repository.updateStatus(project.id, ShortStatus.UPLOADING)
        val uploadResult = YouTubeUploadService.uploadShortToYouTube(context, project)

        return if (uploadResult.isSuccess) {
            val videoId = uploadResult.getOrThrow()
            repository.saveProject(project.copy(status = ShortStatus.PUBLISHED, youtubeVideoId = videoId))
            "🚀 Short Publicado Exitosamente en YouTube:\n" +
                    "ID de Video: $videoId\n" +
                    "Título: ${project.metadata?.title ?: project.topic}\n" +
                    "URL: https://youtube.com/shorts/$videoId"
        } else {
            repository.updateStatus(project.id, ShortStatus.FAILED, uploadResult.exceptionOrNull()?.message)
            "Error al publicar el Short en YouTube: ${uploadResult.exceptionOrNull()?.message}"
        }
    }
}

class ListShortsTool(private val context: Context) : Tool {
    override val name = "list_shorts"
    override val description = "Lista todos los proyectos de YouTube Shorts y sus estados"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val repository = ShortsRepository(context)
        val projects = repository.getAllProjects()
        if (projects.isEmpty()) {
            return "No tienes ningún proyecto de Short creado. Pídeme crear uno sobre cualquier tema."
        }

        val listStr = projects.joinToString("\n\n") { p ->
            "🎬 [${p.status}] ${p.topic}\n" +
                    "  ID: ${p.id}\n" +
                    "  Título: ${p.metadata?.title ?: "Sin título"}\n" +
                    "  Duración: ${p.targetDurationSeconds}s"
        }

        return "Proyectos de YouTube Shorts (${projects.size}):\n\n$listStr"
    }
}
