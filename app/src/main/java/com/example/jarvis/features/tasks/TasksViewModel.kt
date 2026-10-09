package com.example.jarvis.features.tasks

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.core.viewmodel.BaseViewModel
import com.example.jarvis.data.local.LocalDataSourceImpl
import com.example.jarvis.data.models.TaskModel
import com.example.jarvis.data.remote.RemoteDataSourceImpl
import com.example.jarvis.data.repository.DefaultRepository
import kotlinx.coroutines.flow.update
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

data class TasksUiState(
    val tasks: List<TaskModel> = emptyList(),
    val isLoading: Boolean = false
)

class TasksViewModel : BaseViewModel<TasksUiState>(TasksUiState()) {
    private val repository = DefaultRepository(LocalDataSourceImpl(), RemoteDataSourceImpl())

    init {
        loadTasks()
    }

    fun loadTasks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = repository.getTasks()) {
                is AppResult.Success -> _uiState.update { it.copy(tasks = result.data, isLoading = false) }
                is AppResult.Error -> _uiState.update { it.copy(isLoading = false) }
                is AppResult.Loading -> {}
            }
        }
    }
}
