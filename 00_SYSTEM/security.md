# JARVIS Security & Risk Governance Policy

JARVIS adheres to strict security, privacy, and risk governance principles across all subsystems, specifically including Device Control and App Management.

---

## 1. Core Security Principles

- **Zero Hardcoded Secrets**: No API keys, tokens, or credentials are hardcoded in the codebase, resources, or frontend assets. Ephemeral authentication tokens are negotiated dynamically via secure backend services.
- **Principle of Least Privilege**: Device Control and App Management operate exclusively through public, official Android APIs (`PackageManager`, official Settings Intents, `BatteryManager`) without requesting dangerous permissions (`root`, `accessibility`, or administrative device owner privileges).
- **Graceful Error Recovery**: All system operations (`launchApp`, `openSettings`, `getBatteryLevel`) catch security and platform exceptions (`SecurityException`, `ActivityNotFoundException`, `PackageManager.NameNotFoundException`) and return safe `Result<T>` or controlled error messages without crashing the application or exposing sensitive stack traces.

---

## 2. Risk Governance (`RiskLevel`)

Tool executions are classified into three risk levels:
1. **`READ`**: Non-destructive queries such as `check_app_installed`, `get_device_info`, `system_info`, and `open_settings` (opening settings screens requires no privileged permissions).
2. **`WRITE`**: User-facing operations such as `launch_app` or task creation.
3. **`DESTRUCTIVE`**: High-impact or destructive operations (e.g. data deletion, system modifications). **Strict Rule:** Destructive operations are currently disabled and will never be executed automatically without explicit user authorization.
