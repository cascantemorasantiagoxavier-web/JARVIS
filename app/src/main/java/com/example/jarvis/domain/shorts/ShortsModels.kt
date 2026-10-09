package com.example.jarvis.domain.shorts

import java.util.UUID

enum class ShortStatus {
    DRAFT,
    RESEARCHING,
    SCRIPT_READY,
    VOICE_READY,
    ASSETS_READY,
    RENDERING,
    RENDERED,
    VALIDATING,
    READY_TO_PUBLISH,
    UPLOADING,
    PUBLISHED,
    FAILED,
    CANCELLED
}

enum class ShortsAutomationMode {
    MANUAL,
    SEMI_AUTOMATIC,
    AUTOMATIC
}

data class ShortScript(
    val hook: String,
    val context: String,
    val mainBody: String,
    val twist: String,
    val cta: String,
    val targetDurationSeconds: Int = 30
) {
    fun toFullText(): String {
        return "$hook $context $mainBody $twist $cta"
    }
}

data class ShortMetadata(
    val title: String,
    val description: String,
    val hashtags: List<String> = listOf("#Shorts", "#IA", "#Tecnologia"),
    val tags: List<String> = emptyList(),
    val categoryId: String = "28" // Tech & Science
)

data class ShortProject(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val status: ShortStatus = ShortStatus.DRAFT,
    val script: ShortScript? = null,
    val metadata: ShortMetadata? = null,
    val audioPath: String? = null,
    val videoPath: String? = null,
    val youtubeVideoId: String? = null,
    val targetDurationSeconds: Int = 30,
    val creationTimestamp: Long = System.currentTimeMillis(),
    val scheduledPublishTime: Long? = null,
    val errorMessage: String? = null,
    val isAutoPublish: Boolean = false
)
