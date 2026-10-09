package com.example.jarvis.data.shorts

import android.content.Context
import android.util.Log
import com.example.jarvis.domain.shorts.ShortProject
import com.example.jarvis.domain.shorts.ShortStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ShortsRepository(private val context: Context) {

    private val file = File(context.filesDir, "shorts_projects.json")
    private val mutex = Mutex()
    private val projectsMap = mutableMapOf<String, ShortProject>()

    private val _projectsFlow = MutableStateFlow<List<ShortProject>>(emptyList())
    val projectsFlow: StateFlow<List<ShortProject>> = _projectsFlow.asStateFlow()

    init {
        loadProjects()
    }

    private fun loadProjects() {
        try {
            if (file.exists()) {
                val jsonStr = file.readText(Charsets.UTF_8)
                if (jsonStr.isNotBlank()) {
                    val array = JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val project = parseProjectJson(obj)
                        projectsMap[project.id] = project
                    }
                }
            }
            _projectsFlow.value = projectsMap.values.toList().sortedByDescending { it.creationTimestamp }
        } catch (e: Exception) {
            Log.e("JARVIS", "[SHORTS] Failed to load projects from json: ${e.message}", e)
        }
    }

    private fun saveProjects() {
        try {
            val array = JSONArray()
            projectsMap.values.forEach { project ->
                array.put(serializeProjectJson(project))
            }
            file.writeText(array.toString(), Charsets.UTF_8)
            _projectsFlow.value = projectsMap.values.toList().sortedByDescending { it.creationTimestamp }
        } catch (e: Exception) {
            Log.e("JARVIS", "[SHORTS] Failed to save projects to json: ${e.message}", e)
        }
    }

    suspend fun saveProject(project: ShortProject) = mutex.withLock {
        projectsMap[project.id] = project
        saveProjects()
    }

    suspend fun getProject(id: String): ShortProject? = mutex.withLock {
        return projectsMap[id]
    }

    suspend fun getAllProjects(): List<ShortProject> = mutex.withLock {
        return projectsMap.values.toList().sortedByDescending { it.creationTimestamp }
    }

    suspend fun updateStatus(id: String, status: ShortStatus, errorMessage: String? = null) = mutex.withLock {
        val existing = projectsMap[id] ?: return@withLock
        val updated = existing.copy(status = status, errorMessage = errorMessage ?: existing.errorMessage)
        projectsMap[id] = updated
        saveProjects()
    }

    suspend fun deleteProject(id: String) = mutex.withLock {
        val proj = projectsMap.remove(id)
        if (proj != null) {
            proj.videoPath?.let { File(it).delete() }
            proj.audioPath?.let { File(it).delete() }
            saveProjects()
        }
    }

    private fun parseProjectJson(obj: JSONObject): ShortProject {
        val statusStr = obj.optString("status", ShortStatus.DRAFT.name)
        val status = try { ShortStatus.valueOf(statusStr) } catch (_: Exception) { ShortStatus.DRAFT }

        return ShortProject(
            id = obj.optString("id"),
            topic = obj.optString("topic"),
            status = status,
            targetDurationSeconds = obj.optInt("targetDurationSeconds", 30),
            creationTimestamp = obj.optLong("creationTimestamp", System.currentTimeMillis()),
            audioPath = obj.optString("audioPath").ifBlank { null },
            videoPath = obj.optString("videoPath").ifBlank { null },
            youtubeVideoId = obj.optString("youtubeVideoId").ifBlank { null },
            errorMessage = obj.optString("errorMessage").ifBlank { null }
        )
    }

    private fun serializeProjectJson(project: ShortProject): JSONObject {
        return JSONObject().apply {
            put("id", project.id)
            put("topic", project.topic)
            put("status", project.status.name)
            put("targetDurationSeconds", project.targetDurationSeconds)
            put("creationTimestamp", project.creationTimestamp)
            put("audioPath", project.audioPath ?: "")
            put("videoPath", project.videoPath ?: "")
            put("youtubeVideoId", project.youtubeVideoId ?: "")
            put("errorMessage", project.errorMessage ?: "")
        }
    }
}
