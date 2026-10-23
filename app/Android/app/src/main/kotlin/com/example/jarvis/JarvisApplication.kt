package com.example.jarvis

import android.app.Application
import com.example.jarvis.core.model.UiEvent
import com.example.jarvis.notes.VaultIndexer
import com.example.jarvis.reminders.Notifications
import kotlinx.coroutines.launch

class JarvisApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.scope.launch {
            container.orchestrator.loadHistory()
            if (container.settings.current.vaultUri != null) VaultIndexer.schedulePeriodic(this@JarvisApplication)
        }
        // "end_conversation" pedido desde voz: cerrar la sesión cuando JARVIS termine de despedirse
        container.scope.launch {
            container.uiEvents.collect { e ->
                if (e is UiEvent.EndVoiceSession && !container.realtime.isActive) container.voice.stopConversation("fin de conversación")
            }
        }
    }
}
