package com.example.jarvis.data.models

data class TaskModel(
    val id: String,
    val title: String,
    val isCompleted: Boolean
)

data class ProjectModel(
    val id: String,
    val name: String,
    val description: String
)

data class KnowledgeModel(
    val id: String,
    val content: String,
    val source: String
)
