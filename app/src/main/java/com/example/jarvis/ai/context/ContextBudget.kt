package com.example.jarvis.ai.context

data class ContextBudget(
    val maxTokens: Int = 2048,
    val reservedTokens: Int = 256
) {
    fun optimize(items: List<ContextItem>): List<ContextItem> {
        val availableTokens = maxTokens - reservedTokens
        val sorted = items.sortedWith(compareByDescending<ContextItem> { it.priority.weight }.thenByDescending { it.estimatedTokens })

        val accepted = mutableListOf<ContextItem>()
        var currentTokens = 0

        for (item in sorted) {
            if (item.priority == ContextPriority.CRITICAL || currentTokens + item.estimatedTokens <= availableTokens) {
                accepted.add(item)
                currentTokens += item.estimatedTokens
            }
        }
        return accepted
    }
}
