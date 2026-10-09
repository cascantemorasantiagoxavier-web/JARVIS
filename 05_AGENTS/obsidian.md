# 05_AGENTS — Obsidian Vault Architecture Specification

## Overview

The Obsidian integration provides access to documentary Markdown notes stored in an Obsidian Vault on the user's Android device.

---

## 1. Storage Access Framework (SAF) Integration

`AndroidVaultProvider` implements Android's Storage Access Framework:
- **Tree URI Permissions**: Requests persistable URI read/write permissions (`takePersistableUriPermission`).
- **Path Independence**: Operates directly on Document URIs built from the tree root, eliminating hardcoded file paths.
- **Permission Validation**: `validateVault()` verifies active persisted read/write permissions before executing IO operations.

---

## 2. Vault Core Abstractions

- **`VaultProvider`**: Handles vault selection, validation, and listing.
- **`VaultManager`**: Unified CRUD manager for vault operations:
  - `readFile(path: String): String?`
  - `writeFile(path: String, content: String)`
  - `listFiles(): List<String>`
  - `search(query: String): List<String>`
  - `fileExists(path: String): Boolean`
  - `deleteFile(path: String): Boolean`
- **`VaultSearcher`**: Enhanced search interface extracting line-level `VaultSnippet` matches (`filePath`, `lineContent`, `lineNumber`).

---

## 3. Strict Separation: Knowledge vs. Memory

- **Obsidian Vault = Knowledge**: Holds user documentation, Markdown notes, ideas, and external documents.
- **Memory Repository = Memory**: Holds personal user preferences, facts, conversational history, and agent decisions.
- Obsidian Vault contents are queried as `MemoryLevel.KNOWLEDGE` and are never mixed into personal `LONG_TERM` memory automatically.
