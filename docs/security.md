# JARVIS Security Policy

- **No Hardcoded Secrets**: No API keys, permanent tokens, passwords, or secrets (e.g. `OPENAI_API_KEY`) are stored in code repositories, APKs, assets, strings.xml, or frontend code.
- **Secure Backend Session Flow**: Temporary ephemeral session credentials are requested dynamically from a secure backend via `RealtimeSessionService` and used for WebSocket authentication with OpenAI Realtime.
- **Audio Privacy & Permissions**: `RECORD_AUDIO` permission is explicitly requested before microphone capture. Audio streams are transmitted securely over WebSocket without logging sensitive data.
- **Android Keystore**: Integrated via security abstractions for credential and data encryption.
- **Risk Governance**: Tool execution is governed by `RiskLevel` to ensure user control and safety.
