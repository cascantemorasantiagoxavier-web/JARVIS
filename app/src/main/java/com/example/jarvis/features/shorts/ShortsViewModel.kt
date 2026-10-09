package com.example.jarvis.features.shorts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.jarvis.ai.shorts.ShortsScriptGenerator
import com.example.jarvis.ai.shorts.render.ShortsRenderEngine
import com.example.jarvis.ai.shorts.youtube.YouTubeAuthManager
import com.example.jarvis.ai.shorts.youtube.YouTubeUploadService
import com.example.jarvis.data.shorts.ShortsRepository
import com.example.jarvis.domain.shorts.ShortProject
import com.example.jarvis.domain.shorts.ShortStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShortsUiState(
    val topicInput: String = "",
    val targetDuration: Int = 30,
    val isCreating: Boolean = false,
    val isRendering: Boolean = false,
    val isUploading: Boolean = false,
    val isYouTubeConnected: Boolean = false,
    val youtubeChannelName: String = "No conectado",
    val projects: List<ShortProject> = emptyList(),
    val statusMessage: String? = null
)

class ShortsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ShortsRepository(application)

    private val _uiState = MutableStateFlow(ShortsUiState())
    val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()

    init {
        checkYouTubeConnection()
        loadProjects()
    }

    fun onTopicChanged(topic: String) {
        _uiState.update { it.copy(topicInput = topic) }
    }

    fun onDurationChanged(duration: Int) {
        _uiState.update { it.copy(targetDuration = duration) }
    }

    fun checkYouTubeConnection() {
        val app = getApplication<Application>()
        val connected = YouTubeAuthManager.isConnected(app)
        val channel = if (connected) YouTubeAuthManager.getChannelName(app) else "No conectado"
        _uiState.update {
            it.copy(
                isYouTubeConnected = connected,
                youtubeChannelName = channel
            )
        }
    }

    fun createShortProject() {
        val topic = _uiState.value.topicInput.ifBlank { return }
        val duration = _uiState.value.targetDuration

        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, statusMessage = "Investigando y escribiendo guion...") }
            try {
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
                _uiState.update {
                    it.copy(
                        isCreating = false,
                        topicInput = "",
                        statusMessage = "¡Proyecto '${metadata.title}' creado exitosamente!"
                    )
                }
                loadProjects()
            } catch (e: Exception) {
                _uiState.update { it.copy(isCreating = false, statusMessage = "Error al crear Short: ${e.message}") }
            }
        }
    }

    fun renderProject(projectId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRendering = true, statusMessage = "Renderizando MP4 1080x1920...") }
            val project = repository.getProject(projectId) ?: return@launch
            repository.updateStatus(projectId, ShortStatus.RENDERING)

            val app = getApplication<Application>()
            val result = ShortsRenderEngine.renderShortVideo(app, project)

            if (result.isSuccess) {
                val file = result.getOrThrow()
                repository.saveProject(project.copy(status = ShortStatus.RENDERED, videoPath = file.absolutePath))
                _uiState.update { it.copy(isRendering = false, statusMessage = "Video renderizado exitosamente: ${file.name}") }
            } else {
                repository.updateStatus(projectId, ShortStatus.FAILED, result.exceptionOrNull()?.message)
                _uiState.update { it.copy(isRendering = false, statusMessage = "Error en el render: ${result.exceptionOrNull()?.message}") }
            }
            loadProjects()
        }
    }

    fun publishProject(projectId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, statusMessage = "Publicando Short en YouTube...") }
            val project = repository.getProject(projectId) ?: return@launch
            repository.updateStatus(projectId, ShortStatus.UPLOADING)

            val app = getApplication<Application>()
            val uploadResult = YouTubeUploadService.uploadShortToYouTube(app, project)

            if (uploadResult.isSuccess) {
                val videoId = uploadResult.getOrThrow()
                repository.saveProject(project.copy(status = ShortStatus.PUBLISHED, youtubeVideoId = videoId))
                _uiState.update { it.copy(isUploading = false, statusMessage = "¡Short publicado con éxito! Video ID: $videoId") }
            } else {
                repository.updateStatus(projectId, ShortStatus.FAILED, uploadResult.exceptionOrNull()?.message)
                _uiState.update { it.copy(isUploading = false, statusMessage = "Error al publicar: ${uploadResult.exceptionOrNull()?.message}") }
            }
            loadProjects()
        }
    }

    fun loadProjects() {
        viewModelScope.launch {
            val list = repository.getAllProjects()
            _uiState.update { it.copy(projects = list) }
        }
    }
}
