# Proactive Engine

The `ProactiveEngine` detects relevant events, evaluates relevance and policy constraints, and generates contextual suggestions without engaging in unprompted, intrusive autonomy.

---

## Architecture & Principles

- **Deterministic Evaluation**: Rules and policies (`DefaultProactivePolicy`) evaluate events locally without redundant LLM calls.
- **Anti-Spam & Cooldown**: Prevents notification fatigue through per-key cooldown windows, daily maximum limits, and grouping.
- **Quiet Hours**: Respects user-defined quiet hours to suppress or queue non-urgent suggestions.
- **User Control**: Full master toggle and feature-level toggles in `ProactivePreferences`.
