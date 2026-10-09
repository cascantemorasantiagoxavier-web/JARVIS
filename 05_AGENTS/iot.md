# Smart Home & IoT Engine Specification (Phase 27)

## Overview
The Smart Home & IoT Engine enables JARVIS to discover, monitor, and control smart home devices via pluggable providers, starting with official Home Assistant REST API integration.

---

## Architecture & Components

### 1. Domain Models (`com.example.jarvis.domain.iot`)
- **`IoTDevice`**: Generic smart home device representation (`id`, `name`, `type`, `entityId`, `state`, `room`, `provider`, `isAvailable`, `attributes`, `metadata`).
- **`IoTCapability`**: Device capability enumeration (`LIGHT`, `SWITCH`, `FAN`, `CLIMATE`, `LOCK`, `SENSOR`, `MEDIA_PLAYER`, `GENERIC`).
- **`IoTOperation` & `IoTOperationType`**: Strongly typed smart home operations (`READ_STATE`, `TURN_ON`, `TURN_OFF`, `TOGGLE`, `SET_VALUE`, `SET_BRIGHTNESS`, `SET_TEMPERATURE`, `EXECUTE_SERVICE`) with domain, service, and parameters.
- **`IoTProvider`**: Provider abstraction defining contracts for `getDevices()`, `getDevice()`, `getState()`, `executeOperation()`, and `testConnection()`.

### 2. Provider Implementation (`com.example.jarvis.ai.iot.provider`)
- **`HomeAssistantProvider`**: Production-ready implementation interacting with Home Assistant REST API (`/api/states`, `/api/services/{domain}/{service}`) authenticated securely via Bearer token (`Authorization: Bearer TOKEN`).
- **`FakeIoTProvider`**: Mock provider implementation used for unit testing without network dependencies.

### 3. Tool & Orchestrator Integration (`com.example.jarvis.ai.tools.IoTTool`)
- **`IoTTool`**: Registered in `DefaultToolRegistry` as `"iot_tool"`.
- **Security & Governance**: Fully integrated with `RiskEvaluator`, `ConfirmationToken`, `AuditLogger`, and `RobustEventBus`. Destructive or sensitive operations (e.g., locks, security) require explicit confirmation.
- **Intent Detection**: `DefaultIntentDetector` recognizes IoT intents (`IOT`) from natural language and routes them through `DefaultToolSelector` to `IoTTool`.
