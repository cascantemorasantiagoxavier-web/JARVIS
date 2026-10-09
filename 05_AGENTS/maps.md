# Google Maps Integration Specification (Phase 28.2)

## Overview
Enables JARVIS to interact with Google Maps for place search, directions, and navigation.

---

## Components
- **`MapsTool`**: Supports actions including `search`, `directions`, `navigation`, and `show_map`.
- **URL Encoding**: Safely encodes query parameters (`URLEncoder.encode`) and constructs official Google Maps API URIs (`https://www.google.com/maps/search/?api=1&query=...`, `https://www.google.com/maps/dir/?api=1&destination=...&origin=...&travelmode=...`, and `google.navigation:q=...`).
- **Fallbacks**: Gracefully falls back to browser web maps if the Google Maps app is unavailable.
- **Orchestrator Integration**: `DefaultIntentDetector` recognizes map/navigation intents and routes them to `MapsTool`.
