# Device Control Agent & AppManager

The `DeviceAgent` and `AppManager` components handle local Android device interactions safely and modularly without requiring elevated or intrusive permissions.

---

## Architecture & Responsibilities

```
+-------------------------------------------------------------+
|                     Orchestrator Pipeline                   |
| (Detects IntentType.DEVICE -> Selects DeviceAgent & Tools) |
+-------------------------------------------------------------+
                              │
                              ▼
+-------------------------------------------------------------+
|                         DeviceAgent                         |
| (Processes device prompts: apps, settings, system info)    |
+-------------------------------------------------------------+
                              │
                              ▼
+-------------------------------------------------------------+
|                         AppManager                          |
| (Official Android APIs: PackageManager, Intent, Battery)   |
+-------------------------------------------------------------+
```

---

## Implemented Actions (Phase 8A & Phase 8B)

### 1. App Control (`isAppInstalled`, `launchApp`)
- **Detect Installed Apps**: Verifies whether an app or package exists on the device via `PackageManager`.
- **Launch Apps**: Opens installed applications using standard launch `Intent` with `FLAG_ACTIVITY_NEW_TASK`.
- **Error Messages**:
  - Missing app: `"No encontré esa aplicación instalada."`
  - Unlaunchable app: `"No pude abrir esa aplicación."`

### 2. Device Settings (`openSettings`)
- **General Settings**: `Settings.ACTION_SETTINGS`
- **Wi-Fi Settings**: `Settings.ACTION_WIFI_SETTINGS`
- **Bluetooth Settings**: `Settings.ACTION_BLUETOOTH_SETTINGS`
- **Sound Settings**: `Settings.ACTION_SOUND_SETTINGS`

### 3. System & Device Information
- **Device Model**: Resolves manufacturer and model via `Build.MANUFACTURER` and `Build.MODEL`.
- **Android Version**: Resolves release version and API level via `Build.VERSION.RELEASE` and `Build.VERSION.SDK_INT`.
- **Battery Level**: Consults `BatteryManager.BATTERY_PROPERTY_CAPACITY` or `ACTION_BATTERY_CHANGED`.

---

## Device Tools Registered in `ToolRegistry`

1. `check_app_installed`: Checks app presence on device.
2. `launch_app`: Launches specified app package.
3. `open_settings`: Opens general, Wi-Fi, Bluetooth, or sound settings.
4. `get_device_info`: Retrieves model, Android version, or battery percentage.
5. `system_info`: Returns high-level system overview.

---

## Security & Permission Guardrails

- **No Dangerous Permissions Required**: All actions rely on public, unprivileged Android APIs and Intents.
- **Explicit Authorization Messages**: If a `SecurityException` is caught, a clear message is returned:
  `"Esta acción requiere autorización del usuario o un permiso especial no concedido."`
- **Non-Intrusive**: Does not use `AccessibilityService`, root, remote control, or administrative APIs.
