# Event Bus Agent / Subsystem

The `RobustEventBus` provides an internal, modular, decoupled pub/sub event distribution mechanism across all subsystems of ARNES.

---

## Architecture & Features
- **Decoupled Publishing & Subscription**: Publishers emit `AppEvent` items without direct references to handlers.
- **Error Isolation**: Failures in individual subscribers do not impact other handlers or crash the bus.
- **Loop Protection & Correlation**: Tracks `correlationId` and `causationId` to prevent recursive event loops.
- **Idempotency**: Suppresses duplicate event dispatches within local windows.
- **Security & Audit**: Integrates with `AuditLogger` to trace event handling securely.
