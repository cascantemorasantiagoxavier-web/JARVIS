# JARVIS - Android Personal AI Assistant

JARVIS is a modular, AI-powered personal assistant built for Android using Kotlin, Jetpack Compose, and Material 3, featuring a futuristic holographic HUD interface and a secure modular Realtime AI foundation.

## Architecture & Realtime Foundation
- **Clean Architecture**: Decoupled layers for UI, ViewModels, Domain, Data, and AI.
- **Realtime AI Foundation**: Secure session service abstraction (`RealtimeSessionService`), `AiProvider` domain abstraction, and `RealtimeClient` for future OpenAI Realtime streaming without embedding secrets or API keys in the client APK.
- **Secure Backend (`backend/`)**: Node.js & Express server handling ephemeral session creation with OpenAI Realtime API (`gpt-realtime-2.1`) on the server side.
- **Obsidian Vault Integration**: Real file access via Android Storage Access Framework (SAF).
