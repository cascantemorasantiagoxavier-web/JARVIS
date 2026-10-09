package com.example.jarvis.core.common

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Error(val exception: Throwable, val message: String? = null) : AppResult<Nothing>
    data object Loading : AppResult<Nothing>
}
