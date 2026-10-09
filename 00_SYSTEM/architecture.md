# JARVIS System Architecture

JARVIS is a modular, privacy-centric AI Personal Assistant for Android built using Clean Architecture principles and Jetpack Compose.

---

## 1. High-Level Architecture Layers

```
+-------------------------------------------------------------------+
|                        UI / Features Layer                        |
|  (Compose screens, HUD, ViewModels: Chat, Home, Memory, Tasks)    |
+-------------------------------------------------------------------+
                                  │
                                  ▼
+-------------------------------------------------------------------+
|                     Orchestrator / Brain Layer                    |
|  (Phase 4: Intent, Router, Context, Agent/Tool Selectors)         |
+-------------------------------------------------------------------+
                                  │
          ┌───────────────────────┼───────────────────────┐
          ▼                       ▼                       ▼
+-------------------+   +-------------------+   +-------------------+
|    Agent Layer    |   |    Tool Layer     |   |   Memory Layer    |
| (8 Domain Agents) |   | (System, Vault)   |   | (4 Memory Tiers)  |
+-------------------+   +-------------------+   +-------------------+
                                  │
                                  ▼
+-------------------------------------------------------------------+
|               Obsidian SAF Vault & Storage Layer                  |
|  (VaultProvider, VaultManager, VaultSearcher, SAF Android URI)    |
+-------------------------------------------------------------------+
                                  │
                                  ▼
+-------------------------------------------------------------------+
|                     Provider Abstraction Layer                    |
|         (AiProvider, RealtimeClient, Remote Backend Services)     |
+-------------------------------------------------------------------+
```

---

## 2. Phase 4 — Orchestrator / Brain Pipeline (10 Steps)

1. **User Input Ingestion** (`OrchestratorRequest`)
2. **Intent Detection** (`IntentDetector` & `DetectedIntent`)
3. **Context Requirements** (`ContextRouter` & `ContextRequirement`)
4. **Model Routing** (`ModelRouter` & `ModelSelection`)
5. **Memory Retrieval** (`AiMemoryManager` / `MemoryRepository`)
6. **Agent Selection** (`AgentSelection` & `AgentSelector`)
7. **Tool Selection** (`ToolSelection` & `ToolSelector`)
8. **Execution** (`Agent` / `Tool` execution)
9. **Result Validation** (`ResultValidator` & `ValidationResult`)
10. **Memory Decision** (`MemoryDecider` & `MemoryDecision` / `MemoryPolicy`)
11. **Response Assembly** (`OrchestratorResponse`)

---

## 3. Phase 15 — Context Engine & Token Optimization

- **Context Levels (`ContextLevel`)**: Explicit 6-tier hierarchy (`LEVEL_0_IDENTITY`, `LEVEL_1_SESSION`, `LEVEL_2_WORKING_MEMORY`, `LEVEL_3_LONG_TERM_MEMORY`, `LEVEL_4_OBSIDIAN_VAULT`, `LEVEL_5_HISTORY`).
- **Context Router (`ContextRouter`, `DefaultContextRouter`)**: Deterministic routing mapping incoming intent types and queries to precise context requirements without loading unnecessary vaults or history.
- **Context Budget & Priority (`ContextBudget`, `ContextPriority`)**: Enforces token budgets by prioritizing items into `CRITICAL`, `HIGH`, `NORMAL`, and `LOW`, dropping non-essential low-priority context while preserving critical system instructions.
- **Selective Retrieval**: Loads only relevant snippets from memory or Obsidian vaults on demand.

---

## 4. Phase 16 — Memory 2.0 (Long-Term Memory Architecture)

- **Memory Record Model (`MemoryRecord`)**: Rich, strongly-typed memory entries with metadata (`id`, `type`, `content`, `importance`, `confidence`, `source`, `status`, `expiration`, `relatedProject`).
- **Memory Types (`MemoryType`)**: Categorized semantics (`USER_PREFERENCE`, `USER_CONTEXT`, `PERSON`, `PROJECT_CONTEXT`, `DECISION`, `HABIT`, `GOAL`, `IMPORTANT_FACT`, `TEMPORARY_CONTEXT`).
- **Memory Write Policy (`Memory2Policy`)**: Evaluates candidate stability, filters trivial input, performs deduplication, detects temporal expiration, and flags ambiguities or contradictions (`SHOULD_STORE`, `SHOULD_UPDATE`, `SHOULD_IGNORE`, `REQUIRES_REVIEW`).
- **Obsidian Separation**: Memory 2.0 maintains personal context, decisions, and preferences, while Obsidian SAF remains the dedicated knowledge base for documentary notes.

---

## 5. Phase 17 — Productivity & Projects Engine

- **Productivity Domain (`Task`, `TaskRepository`, `DefaultTaskRepository`)**: Manages task lifecycles (`INBOX`, `TODO`, `IN_PROGRESS`, `BLOCKED`, `COMPLETED`, `CANCELLED`, `ARCHIVED`), priorities (`CRITICAL`, `HIGH`, `NORMAL`, `LOW`), deadlines, recurrences, and dependencies.
- **Projects Domain (`Project`, `ProjectRepository`, `DefaultProjectRepository`)**: Groups tasks into structured initiatives with goals, deadlines, next actions, Obsidian references, and related memories.
- **Deterministic Prioritizer (`DefaultTaskPrioritizer`)**: Scores and ranks tasks based on explicit priority, deadline proximity, dependency status, task age, and project health factor.

---

## 6. Phase 18 — Research Engine

- **Research Domain (`ResearchRequest`, `ResearchResult`, `ResearchSource`, `Evidence`, `Claim`, `ResearchConflict`)**: Provides multi-source information gathering, source evaluation, evidence extraction, synthesis, conflict detection, and provenance tracking.
- **Production Web Search Adapter**: `NOT IMPLEMENTED` (External web search API not coupled).
- **Mock Web Search Adapter**: `IMPLEMENTED FOR DETERMINISTIC TESTING` (`MockWebSearchClient`).
- **Research Cache**: Local session-scoped in-memory caching to avoid redundant investigations on recent queries.
- **Knowledge Persistence (`saveToKnowledge`)**: Optionally persists synthesized research directly into Obsidian Vault as structured markdown notes when explicitly requested.

---

## 7. Phase 19 — Proactive Engine

- **Proactive Domain (`ProactiveEngine`, `DefaultProactiveEngine`, `ProactivePolicy`, `ProactivePreferences`)**: Detects relevant internal events (`TASK_OVERDUE`, `DEADLINE_APPROACHING`, `ROUTINE_TRIGGERED`) and evaluates relevance, anti-spam cooldowns, quiet hours, and user preferences to produce contextual suggestions (`ProactiveSuggestion`) without unprompted intrusive autonomy.

---

## 8. Phase 20, 24.1 & 24.2 — Calendar & Email Engines (Google Integration)

- **Calendar Domain (`CalendarEvent`, `CalendarStatus`, `CalendarProvider`, `GoogleCalendarProvider`)**: Manages calendar events, scheduling queries, and time conflict detection, integrated with Google Calendar via OAuth2 (`GoogleOAuthToken`).
- **Email Domain (`EmailMessage`, `EmailProvider`, `GmailProvider`)**: Manages email search, thread retrieval, draft preparation, and safe send confirmation workflows (`confirmed = true`), integrated with Gmail via OAuth2 (`GoogleOAuthToken`).
- **Mock Providers**: `MockCalendarProvider` and `MockEmailProvider` for offline testing.

---

## 9. Phase 21 & 24.3 — Communications Engine (Real Messaging Providers)

- **Communications Domain (`Conversation`, `CommunicationMessage`, `CommunicationDraft`, `CommunicationProvider`)**: Unifies messaging across multiple platforms via decoupled provider abstractions and normalized domain models.
- **Telegram Provider (`TelegramProvider`)**: Integrates with official Telegram Bot API using token configuration and audit logging.
- **WhatsApp Business Provider (`WhatsAppBusinessProvider`)**: Integrates with official Meta WhatsApp Cloud API using access token and phone number ID configuration.
- **Slack Provider (`SlackProvider`)**: Integrates with official Slack Web API using bot token configuration.
- **Mock Providers**: `MockWhatsAppProvider`, `MockTelegramProvider`, `MockSlackProvider` for offline testing.
- **Confirmation Policy**: External message sending (`sendMessage`) strictly requires explicit user confirmation (`confirmed = true`), protected by `RiskEvaluator`.

---

## 10. Phase 22 — Automation & Workflow Engine

- **Workflow Domain (`Workflow`, `Trigger`, `Condition`, `WorkflowStep`)**: Connects internal application events to declarative automation workflows, evaluating conditions and executing multi-step agent actions and tools safely.
- **Security & Risk Guardrails**: All automated tool executions pass through `RiskEvaluator` to prevent unauthorized actions.

---

## 11. Phase 23 — Event Bus

- **Event Bus Domain (`AppEvent`, `EventType`, `EventSubscription`, `RobustEventBus`)**: Provides an internal, modular, decoupled pub/sub event distribution mechanism featuring error isolation between subscribers, loop protection (`correlationId`, `causationId`, max depth), local idempotency, and secure audit logging.

---

## 12. Phase 25 — Offline Wake Word Engine ("JARVIS")

- **Wake Word Domain (`WakeWordEngine`, `LocalWakeWordEngine`, `WakeWordController`, `WakeWordService`)**: Provides offline local keyword detection for **"JARVIS"** with zero cloud audio streaming prior to activation, mutual exclusion against command recording to prevent mic conflicts, and Android Foreground Service (`WakeWordService`) for persistent background monitoring.

---

## 13. Phase 26 — Background WorkManager Automation & Proactive Notifications

- **WorkManager Automation (`AutomationWorker`, `ScheduledAutomationRunner`)**: Connects Android `WorkManager` periodic background work to `AutomationEngine` for scheduled cron workflows (`SCHEDULE`), ensuring background execution, retry policies, loop protection, and security compliance via `RiskEvaluator`.
- **Proactive Notifications (`ProactiveNotificationManager`)**: Delivers Android native notifications for `HIGH` and `URGENT` proactive suggestions on channel `jarvis_proactive_suggestions_channel` with deep links (`PendingIntent`), respecting quiet hours, anti-spam cooldowns, and Android 13+ permission policies.
