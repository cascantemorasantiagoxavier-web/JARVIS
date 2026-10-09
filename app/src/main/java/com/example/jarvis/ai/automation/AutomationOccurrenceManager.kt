package com.example.jarvis.ai.automation

import android.content.Context
import android.content.SharedPreferences

class AutomationOccurrenceManager(private val context: Context?) {
    private val prefs: SharedPreferences? by lazy {
        try {
            context?.getSharedPreferences("jarvis_automation_persistence", Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
    }
    private val memoryStore = mutableSetOf<String>()

    fun isExecuted(occurrenceKey: String): Boolean {
        prefs?.let {
            return it.getBoolean(occurrenceKey, false)
        }
        return memoryStore.contains(occurrenceKey)
    }

    fun markExecuted(occurrenceKey: String) {
        prefs?.let {
            it.edit().putBoolean(occurrenceKey, true).apply()
        }
        memoryStore.add(occurrenceKey)
    }

    fun clear() {
        prefs?.let {
            it.edit().clear().apply()
        }
        memoryStore.clear()
    }
}
