package com.example.jarvis.features.home

import com.example.jarvis.core.viewmodel.BaseViewModel
import kotlinx.coroutines.flow.update

data class HomeUiState(
    val assistantName: String = "JARVIS",
    val systemStatus: String = "Operativo - Sistemas nominales",
    val connectionStatus: String = "Conectado (Red Local / Abstraída)",
    val activeModules: Int = 7
)

class HomeViewModel : BaseViewModel<HomeUiState>(HomeUiState()) {
    fun refreshSystemStatus() {
        _uiState.update { it.copy(systemStatus = "Optimizado y sincronizado a las ${System.currentTimeMillis() % 10000}") }
    }
}
