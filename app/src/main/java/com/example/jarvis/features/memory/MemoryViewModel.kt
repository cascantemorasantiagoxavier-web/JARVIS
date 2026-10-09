package com.example.jarvis.features.memory

import com.example.jarvis.core.viewmodel.BaseViewModel

data class MemoryUiState(
    val workingMemoryStatus: String = "Contexto activo: Listo",
    val sessionMemoryStatus: String = "Historial de sesión: 5 interacciones recientes",
    val longTermMemoryStatus: String = "Base de datos / Almacenamiento vectorial: Conectado (Local)",
    val knowledgeStatus: String = "Puente de Knowledge Base de Obsidian: Preparado"
)

class MemoryViewModel : BaseViewModel<MemoryUiState>(MemoryUiState()) {
}
