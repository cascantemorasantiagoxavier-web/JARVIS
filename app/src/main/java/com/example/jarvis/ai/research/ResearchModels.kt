package com.example.jarvis.ai.research

import java.util.UUID

enum class ResearchDepth {
    QUICK,
    STANDARD,
    DEEP
}

enum class SourceType {
    OFFICIAL,
    DOCUMENTATION,
    ACADEMIC,
    NEWS,
    GOVERNMENT,
    DATABASE,
    BLOG,
    COMMUNITY,
    OTHER
}

enum class ClaimStatus {
    SUPPORTED,
    CONTESTED,
    UNCERTAIN,
    UNSUPPORTED
}

data class ResearchRequest(
    val query: String,
    val objective: String = "",
    val scope: String = "general",
    val depth: ResearchDepth = ResearchDepth.STANDARD,
    val freshnessRequirement: String = "any",
    val maxSources: Int = 5,
    val relatedProject: String? = null,
    val saveToKnowledge: Boolean = false
)

data class Evidence(
    val sourceId: String,
    val claim: String,
    val excerpt: String,
    val confidence: Float = 0.8f,
    val timestamp: Long = System.currentTimeMillis()
)

data class Claim(
    val statement: String,
    val supportingSources: List<String> = emptyList(),
    val contradictingSources: List<String> = emptyList(),
    val confidence: Float = 0.8f,
    val status: ClaimStatus = ClaimStatus.SUPPORTED
)

data class ResearchSource(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val domain: String,
    val retrievedAt: Long = System.currentTimeMillis(),
    val publicationDate: String? = null,
    val sourceType: SourceType = SourceType.DOCUMENTATION,
    val credibilityScore: Float = 0.8f,
    val relevance: Float = 0.9f,
    val extractedEvidence: List<Evidence> = emptyList()
)

data class ResearchConflict(
    val claimA: String,
    val sourceA: String,
    val claimB: String,
    val sourceB: String
)

data class ResearchResult(
    val query: String,
    val objective: String,
    val summary: String,
    val claims: List<Claim> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
    val sources: List<ResearchSource> = emptyList(),
    val conflicts: List<ResearchConflict> = emptyList(),
    val confidence: Float = 0.85f,
    val generatedAt: Long = System.currentTimeMillis(),
    val depth: ResearchDepth = ResearchDepth.STANDARD,
    val isCached: Boolean = false
)
