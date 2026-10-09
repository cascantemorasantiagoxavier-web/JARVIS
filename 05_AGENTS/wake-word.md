# Wake Word Subsystem ("JARVIS")

The `PhoneticKwsWakeWordEngine` provides offline Keyword Spotting (KWS) detection for **"JARVIS"** without cloud audio streaming, integrating with `VoiceViewModel` and Android Foreground Service (`WakeWordService`).

---

## Technical Classification & KWS Architecture
- **Detector Classification**: Heurística fonética espectral creada manualmente (extracción de Tasa de Cruces por Cero - ZCR, energía RMS y envolturas espectrales PCM para la secuencia `JH -> AA -> R -> V -> IH -> S`).
- **Offline Keyword Spotting**: Distingue patrones de frecuencia y fonemas de **"JARVIS"** frente a silencio, aplausos o palabras distintas ("ALEXA", "HELLO") sin requerir procesamiento en la nube.
- **State Machine & Transitions**: `DISABLED` -> `LISTENING` -> `DETECTED` -> `COMMAND_LISTENING` -> `PROCESSING` -> `SPEAKING` -> `LISTENING`.
- **Microphone Conflict Prevention**: Pausa explícitamente `WakeWordEngine` durante la grabación de comandos y sesiones de OpenAI Realtime para evitar conflictos de hardware por captura doble de micrófono.
- **Foreground Service**: `WakeWordService` opera como Android Foreground Service con notificación transparente para monitoreo en segundo plano.
- **Validation Status**:
  - **Pruebas JVM & Integración**: 100% VALIDADAS (Pruebas unitarias de discriminación de fonemas, transiciones de estado e idempotencia pasadas en verde).
  - **Prueba con Micrófono Real**: PENDIENTE (Requiere dispositivo físico/emulador con entrada de audio en vivo).
