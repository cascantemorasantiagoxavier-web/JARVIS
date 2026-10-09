package com.example.jarvis.domain.agents

import com.example.jarvis.domain.planning.Plan

interface PlanningAgent : Agent {
    suspend fun createPlan(goal: String): List<String>
    suspend fun planGoal(goalTitle: String, description: String = ""): Plan
}
