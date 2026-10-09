# Web Search & Open Web Specification (Phase 28.1)

## Overview
Provides secure web search integration via Research Engine provenance and safe URL navigation (`OpenWebTool`).

---

## Components
- **`WebSearchTool` / `ResearchTool`**: Investigates topics, gathering web sources and evidence with credibility scoring and provenance tracking.
- **`OpenWebTool`**: Securely opens external web URLs via `Intent.ACTION_VIEW` after validating protocol safety (allowing `http`/`https` and rejecting dangerous schemes like `javascript:`, `file:`, `content:`, `intent:`).
- **Orchestrator Integration**: `DefaultIntentDetector` classifies web research and open web requests, routing them to the appropriate tools through `DefaultToolSelector`.
