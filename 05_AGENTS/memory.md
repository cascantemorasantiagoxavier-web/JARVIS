# 05_AGENTS — Memory Architecture Specification

## Overview

The Memory System in JARVIS manages tiered context retention and structured user knowledge, keeping personal memory strictly separated from documentary knowledge stored in Obsidian.

---

## 1. Memory Tiers (`MemoryLevel`)

1. **`WORKING`**: In-memory active context during the current conversation interaction.
2. **`SESSION`**: Conversation history timeline for the active session.
3. **`LONG_TERM`**: Persisted facts, user preferences, project decisions, and user profile details.
4. **`KNOWLEDGE`**: Documentary knowledge retrieved from external sources like Obsidian notes.

---

## 2. Memory Entry Structure (`MemoryEntry`)

- `id`: Unique identifier (UUID).
- `content`: Raw text content of the memory.
- `level`: `MemoryLevel` (`WORKING`, `SESSION`, `LONG_TERM`, `KNOWLEDGE`).
- `importance`: Float score (0.0 to 1.0) defining retention priority.
- `timestamp`: Creation/modification epoch timestamp.
- `tags`: List of strings for categorical filtering.
- `source`: Source identifier (`user`, `agent`, `obsidian:file.md`, etc.).
- `confidence`: Confidence rating (0.0 to 1.0).
- `metadata`: `MemoryMetadata` container.

---

## 3. Memory Repository (`MemoryRepository`)

Decoupled storage interface providing standard CRUD and query operations:
- `save(entry: MemoryEntry): AppResult<MemoryEntry>`
- `update(entry: MemoryEntry): AppResult<MemoryEntry>`
- `delete(id: String): AppResult<Boolean>`
- `get(id: String): AppResult<MemoryEntry?>`
- `search(query: MemoryQuery): AppResult<MemoryResult>`
- `listRelevant(input: String, limit: Int): AppResult<List<MemoryEntry>>`

---

## 4. Memory Persistence Policy (`MemoryPolicy`)

Before committing candidate information to long-term memory, `MemoryPolicy` evaluates:
- **Triviality/Noise**: Ignores empty input or simple greetings ("hola", "gracias").
- **Deduplication**: Prevents duplicate entries with identical content in the same tier (`IGNORE`).
- **Topic Update**: Automatically updates outdated entries when a key topic changes (`UPDATE`).
- **Destructive Operations**: Flags deletion requests as `DELETE_REVIEW` requiring `requiresConfirmation = true`.

---

## 5. Selective Knowledge Retrieval (`KnowledgeRetriever`)

To prevent loading whole vaults into memory:
1. `VaultSearcher` locates matching files and line numbers.
2. `KnowledgeRetriever` extracts matching text snippets.
3. Packages results as `MemoryResult` with level `KNOWLEDGE`.
