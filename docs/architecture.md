# JARVIS Architecture

JARVIS follows a Clean Architecture approach organized into clear layers:

- **Core**: Common utilities, error handling, logging, security (Keystore), and permissions.
- **Data**: Models, repositories, and abstracted remote services (`RealtimeSessionService`).
- **Domain**: Business entities, agent interfaces, memory abstractions, tool definitions, and AI provider interfaces (`AiProvider`).
- **AI**: Orchestrator, context manager, intent detector, agent/tool registries, and realtime client abstractions (`RealtimeClient`, `RealtimeEvent`, `OpenAiWebSocketRealtimeClient`, `AudioRecorder`, `AudioPlayer`).
- **Automation & Scheduling (`AutomationEngine`, `CronOccurrenceCalculator`, `AutomationWorker`, `ScheduledAutomationRunner`)**: Declarative workflow engine with real CRON occurrence calculation, persistent occurrence idempotency (`AutomationOccurrenceManager`), missed schedule catch-up (`maxCatchUp = 3`), successful-only `markExecuted()` timing, and security guardrails (`RiskEvaluator`, `ConfirmationToken`).
- **Smart Home & IoT Engine (`IoTDevice`, `IoTOperation`, `IoTProvider`, `HomeAssistantProvider`, `IoTTool`, `FakeIoTProvider`)**: Smart home integration layer supporting Home Assistant REST API, capability mapping, secure token auth, risk evaluation (`RiskLevel`), audit logging, and orchestrator tool routing.
- **Web, Maps & Advanced Device Control (`WebSearchTool`, `ResearchTool`, `OpenWebTool`, `MapsTool`, `DeviceControlTool`, `JarvisAccessibilityService`, `DeviceControlProvider`)**: Web search provenance, secure web browsing, Google Maps integration (search, directions, navigation), and system/UI control via Android Accessibility service with `RiskEvaluator` and `ConfirmationToken` governance.
- **Backend (`backend/`)**: Secure Express server (`POST /api/realtime/session`, `GET /health`) negotiating ephemeral client secrets with OpenAI Realtime (`gpt-realtime-2.1`).
- **Obsidian**: Vault provider, manager, reader, writer, and search interfaces.
- **Features**: Modular feature packages with futuristic HUD interfaces.
