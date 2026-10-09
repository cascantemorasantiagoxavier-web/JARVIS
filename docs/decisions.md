# Architectural Decisions & Trade-offs (ADR)

## ADR 1: Secure Backend Delegation for Realtime AI Sessions
- **Context**: OpenAI Realtime API requires authentication credentials. Embedding permanent API keys in client mobile apps is insecure and vulnerable to extraction.
- **Decision**: Implement a `RealtimeSessionService` abstraction on the client that requests temporary, time-bound ephemeral session tokens from a secure backend.
- **Consequences**: Enhanced security, zero secret leakage in client builds, but requires a functional backend endpoint in production.

## ADR 2: Decoupled AI Provider Abstraction (`AiProvider` & `RealtimeClient`)
- **Context**: JARVIS should support multiple AI backends or future transport mechanisms (WebSocket, WebRTC) without modifying the central Orchestrator.
- **Decision**: Introduce `AiProvider` and `RealtimeClient` domain interfaces.
- **Consequences**: High modularity and testability with simple fakes/mocks, at the cost of slight initial abstraction overhead.
