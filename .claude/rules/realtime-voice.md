# Reglas: voz y OpenAI Realtime

## Contrato (GA, verificado contra los tipos del SDK oficial `openai` de npm 7.43.0)
- Conexión WebRTC. Llamada: `POST /v1/realtime/calls` (multipart `sdp` + `session`, o SDP con
  client secret). Colgar: `POST /v1/realtime/calls/{call_id}/hangup`.
- Client secret efímero: `POST /v1/realtime/client_secrets`, TTL 10–7200 s.
- Canal de datos `oai-events`. Eventos usados: `session.update`, `response.create`,
  `response.cancel`, `output_audio_buffer.clear`, `conversation.item.create`
  (`function_call_output`), `response.function_call_arguments.done`, `response.done` (con `usage`),
  `output_audio_buffer.started|stopped`, `input_audio_buffer.speech_started`, `error`.
- Detección de turnos: `semantic_vad` con `interrupt_response: true` (el usuario puede interrumpir).
- Modelo por defecto `gpt-realtime-2.1-mini`; voz por defecto `cedar` (alternativa `marin`).

## Modos de conexión
- `unified` (por defecto): el teléfono envía su oferta SDP al backend (`/api/realtime/call`), el
  backend la reenvía a OpenAI con la clave permanente y devuelve la respuesta SDP. El teléfono nunca
  ve credenciales de OpenAI.
- `ephemeral`: el backend emite un client secret de vida corta y el teléfono negocia directamente.
  Útil si el servidor está lejos; el secreto caduca y no sirve para otra cosa.

## Comportamiento
- Nunca iniciar una sesión sin acción del usuario (toque o palabra clave que él activó).
- Cierre automático por inactividad (`idleTimeoutSeconds`, 30 s por defecto) y duración máxima
  (`maxSessionMinutes`, 10). Reconexiones limitadas (`maxReconnects`).
- `end_conversation` cierra la sesión después de que termine de sonar la despedida.
- Las herramientas de voz usan el mismo `ToolExecutor` que el chat: mismas confirmaciones y permisos.
  Una respuesta con varias llamadas espera a que todas terminen antes de `response.create`.
- Si Realtime falla, se pasa a voz local (SpeechRecognizer + TTS) avisando al usuario; nunca en
  silencio.
- Audio: AEC y supresión de ruido por hardware (JavaAudioDeviceModule); ruta de audio con
  `setCommunicationDevice` en API 31+.
- Palabra clave "JARVIS": local con Sherpa-ONNX (sin enviar audio a la nube). Si el AAR o el modelo
  faltan, la función se muestra como no disponible con el motivo.

## Costos
- Registrar `usage` de cada `response.done` y mostrar el consumo del día.
- No transcribir la voz del usuario si `OPENAI_REALTIME_TRANSCRIPTION_MODEL=none`.
- Precios en `../JARVIS-KB/07-RESEARCH/costos.md`; verificarlos en la página oficial antes de
  presupuestar.
