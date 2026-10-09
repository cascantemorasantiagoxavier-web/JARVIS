package com.example.jarvis.ai.context

class DefaultContextManager : ContextManager {
    private val contextMap = mutableMapOf<String, String>()
    val conversationContext = ConversationContext()

    override fun getCurrentContext(): Map<String, String> {
        val map = contextMap.toMutableMap()
        map["conversation_summary"] = conversationContext.getSummarizedContext()
        conversationContext.activeEntity?.let {
            map["active_entity"] = it.name
            map["active_entity_type"] = it.type
        }
        return map
    }

    override fun updateContext(key: String, value: String) {
        contextMap[key] = value
    }
}
