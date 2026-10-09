package com.example.jarvis.domain.agents

interface ObsidianAgent : Agent {
    suspend fun syncWithVault()
}
