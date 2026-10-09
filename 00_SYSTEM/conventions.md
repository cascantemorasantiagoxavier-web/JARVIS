# JARVIS Development Conventions & Architecture Standards

JARVIS follows idiomatic Kotlin and modern Android architecture guidelines to ensure maintainability, testability, and scalability.

---

## 1. Architectural Layers & Separation of Concerns

- **Clean Architecture**: Clear separation between Domain (entities, agent interfaces, use cases), Data (repositories, data sources), AI (orchestrator, orchestrator brain, prompt/tool selection), Core (device managers, dispatchers, error handling, security), and UI (Jetpack Compose, ViewModels).
- **Pure Logic vs. Framework Separation**: Code that interacts directly with Android framework classes (`Context`, `PackageManager`, `Settings`, `Intent`, `BatteryManager`) is cleanly isolated behind interfaces (e.g. `AppManager`) to enable robust JVM unit testing using Fakes/Mocks (e.g. `DeviceControlPhase8BTest`).

---

## 2. Kotlin & Coroutines Best Practices

- **Idiomatic Kotlin**: Use Kotlin data classes, sealed interfaces/classes, null safety (`Result<T>`), and expression bodies where appropriate.
- **Coroutines & Threading**: Suspend functions for asynchronous work. Use `AppDispatchers` for structured concurrency dispatchers (`IO`, `Main`, `Default`). Never use `GlobalScope` or blocking waits (`Thread.sleep`).
- **Result Handling**: Operations returning results leverage Kotlin's built-in `Result<T>` or `AppResult<T>` pattern, ensuring error propagation and controlled recovery.

---

## 3. Testing Conventions

- **Deterministic Unit Tests**: All agents, tools, orchestrator steps, and manager abstractions must have comprehensive unit test coverage.
- **Fakes & Mouts**: Use clean test fakes (such as `FakeAppManager`) to simulate Android framework behaviors in unit tests without requiring instrumented tests for pure business logic.
- **Zero Broken Tests**: All unit tests must pass successfully (`0 failed tests`).
