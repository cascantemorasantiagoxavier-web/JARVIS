package com.example.jarvis.ai.memory

import android.content.Context
import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.memory.MemoryRepository

fun <T> AppResult<T>.getOrNull(): T? {
    return if (this is AppResult.Success) this.data else null
}

object PersistenceMemoryHolder {
    @Volatile
    var repository: MemoryRepository = PersistentMemoryRepository(null)

    @Volatile
    var personalityManager: PersonalityManager = PersonalityManager(null)

    fun initialize(context: Context) {
        val persistentRepo = PersistentMemoryRepository(context.applicationContext)
        repository = persistentRepo
        personalityManager = PersonalityManager(context.applicationContext)
    }
}
