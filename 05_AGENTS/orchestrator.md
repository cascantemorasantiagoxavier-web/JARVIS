# 05_AGENTS — Orchestrator Architecture Specification

## Overview

The Orchestrator serves as the central brain of JARVIS. It is responsible for orchestrating user queries through intent classification, context retrieval, agent and tool selection, result validation, and memory persistence decisions.

---

## Modular Component Interfaces

### 1. Request & Response Models (`com.example.jarvis.ai.orchestrator.models`)
- `OrchestratorRequest`: Input container holding `requestId`, `input`, `userId`, `sessionId`, and `metadata`.
- `OrchestratorResponse`: Output container holding `requestId`, `output`, `isSuccess`, `detectedIntent`, `agentSelection`, `toolSelection`, `validationResult`, `memoryDecision`, `error`, and `metadata`.

### 2. Intent Detection (`DetectedIntent`, `IntentType`)
Standardized intent types:
- `CODING`
- `DEVICE`
- `MEMORY`
- `OBSIDIAN`
- `PLANNING`
- `PRODUCTIVITY`
- `RESEARCH`
- `GENERAL`
- `UNKNOWN`

### 3. Selection & Resolution Abstractions
- **`AgentSelector`**: Resolves `AgentSelection` using `AgentRegistry`.
- **`ToolSelector`**: Resolves `ToolSelection` using `ToolRegistry`.
- **`ContextRequirementResolver`**: Determines required `ContextLevel`s for the query.
- **`ResultValidator`**: Validates execution output before responding or storing in memory.
- **`MemoryDecider`**: Evaluates whether execution results should be committed to `AiMemoryManager`.

### 4. Brain Decoupling (`OrchestratorBrain`)
`OrchestratorBrain` provides provider-agnostic request processing:
- `suspend fun processRequest(request: OrchestratorRequest): OrchestratorResponse`
- `fun setProvider(provider: AiProvider)`

---

## Flow Diagram

```
User Input (OrchestratorRequest)
       │
       ▼
[1] Intent Detection -> DetectedIntent (IntentType)
       │
       ▼
[2] Context Requirements -> ContextRequirements (ContextLevels 0-5)
       │
       ▼
[3] Memory Retrieval -> Recall query history & knowledge
       │
       ▼
[4] Agent Selection -> AgentSelection (AgentRegistry)
       │
       ▼
[5] Tool Selection -> ToolSelection (ToolRegistry)
       │
       ▼
[6] Execution -> Agent.process() / Tool.execute()
       │
       ▼
[7] Result Validation -> ValidationResult (isValid, errors)
       │
       ▼
[8] Memory Decision -> MemoryDecision (MEMORIZE / SKIP / UPDATE / DISCARD)
       │
       ▼
Response (OrchestratorResponse)
```
