package com.example.jarvis.features.projects

import com.example.jarvis.core.viewmodel.BaseViewModel
import com.example.jarvis.obsidian.vault.MockVaultManager
import kotlinx.coroutines.flow.update
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

data class ProjectsUiState(
    val vaultPath: String = "",
    val files: List<String> = emptyList()
)

class ProjectsViewModel : BaseViewModel<ProjectsUiState>(ProjectsUiState()) {
    private val vaultManager = MockVaultManager()

    init {
        loadVault()
    }

    fun loadVault() {
        viewModelScope.launch {
            val path = vaultManager.vaultPath ?: ""
            val fileList = vaultManager.listFiles()
            _uiState.update { it.copy(vaultPath = path, files = fileList) }
        }
    }
}
