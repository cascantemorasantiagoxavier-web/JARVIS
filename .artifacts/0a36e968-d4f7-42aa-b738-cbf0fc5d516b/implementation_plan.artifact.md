# JARVIS — Reference Image HUD UI Reconstruction & Settings Audit Plan

This plan governs the complete UI reconstruction matching the reference image layout in Compose (futuristic golden/amber neon HUD theme, 3D holographic sphere core, top arched clock module, 15-app launcher grid, recent activities, Iron Man avatar badge, bottom radar, and capsule voice bar) and the comprehensive audit of all UI buttons, navigation, and Settings screen error items.

## User Review Required

> [!IMPORTANT]
> **Faithful Visual HUD & Zero Dead Buttons**:
> - Reconstruct Compose HUD layout following all 10 visual sections from the reference image.
> - Ensure every button across Home, Chat, Voice, Memory, Tasks, and Settings is 100% interactive or clearly disabled, removing any dead "Error" states in Settings.

## Proposed Scope

### 1. HUD Visual Theme & Components (`ReferenceHudComponents.kt`, `JarvisHomeScreen.kt`)
- Refine cut-corner tech panel frames with golden/amber neon borders (`#FF8C00`, `#FFB300`).
- Ensure dynamic system clock & date in top center arched module.
- Reconstruct 15-app launcher grid, recent activity log, Iron Man badge, bottom radar telemetry, and capsule voice bar.

### 2. Settings & Navigation Audit (`JarvisSettingsScreen.kt`, `SettingsViewModel.kt`)
- Audit all settings items, diagnostic buttons, and vault selection handlers to ensure zero dead clicks.

### 3. Unified Brain Verification & Build
- Verify Chat and Voice use `JarvisBrain.orchestrator`.
- Build debug APK (`dist/JARVIS-debug.apk`) and deliver report with 100% honest testing status tags.

## Verification Plan

### Automated Tests
- Run Gradle unit tests (`testDebugUnitTest`).

### Manual Verification
- Categorized status reporting (`TEST AUTOMATIZADO`, `EMULADOR`, `DISPOSITIVO FÍSICO`, `NO TESTEADO`).
