---
name: realtime-testing
description: Prueba la voz GPT Realtime de J.A.R.V.I.S. (backend de sesiones/llamadas, controlador Kotlin, WebRTC, herramientas por voz, interrupciones, costos). Úsala al cambiar algo de voz, Realtime, WebRTC o la persona, o cuando el usuario diga que la voz no funciona.
---

# Pruebas de GPT Realtime

## 1. Automáticas (sin red, siempre)
```bash
.claude/skills/realtime-testing/scripts/smoke.sh offline
```
Ejecuta `backend/test/realtime.test.js` y `RealtimeTest` + `BackendIntegrationTest` del núcleo (node real +
OpenAI simulado). Clasificación: AUTOMATIZADO, con datos simulados (no prueba el servicio real).

## 2. Contra un servidor en marcha
```bash
export JARVIS_URL=http://127.0.0.1:8787        # o la URL del hosting
export JARVIS_CLIENT_TOKEN=...                  # del backend/.env del usuario; nunca en el chat
.claude/skills/realtime-testing/scripts/smoke.sh live                  # salud, 401, contadores
.claude/skills/realtime-testing/scripts/smoke.sh live --create-secret  # + client secret efímero real
```
`--create-secret` habla con OpenAI y suma una sesión a los límites del servidor; pedir permiso antes.

## 3. En el teléfono (DISPOSITIVO FÍSICO; no se puede automatizar aquí)
Anotar modelo y versión de Android en `../JARVIS-KB/08-TESTS/`.
- [ ] Al abrir la app NO se inicia ninguna sesión (`/api/usage` no sube).
- [ ] Toque en el núcleo → "Conectando…" → respuesta hablada en < 3 s.
- [ ] Interrumpir hablando encima: JARVIS se calla (`output_audio_buffer.clear`).
- [ ] "¿Qué hora es?" usa `get_datetime`; "busca ferreterías en Heredia" muestra tarjetas.
- [ ] Acción sensible por voz ("olvida que…") pide confirmación en pantalla.
- [ ] 30 s de silencio → cierre por inactividad con aviso.
- [ ] Modo avión a mitad → aviso y paso a voz local, nunca en silencio.
- [ ] Palabra clave "JARVIS" solo si el usuario la activó; notificación con "Detener".
- [ ] Altavoz/auriculares Bluetooth: sin eco (AEC por hardware).

## 4. Diagnóstico rápido
| Síntoma | Mirar |
|---|---|
| 503 `not_configured` | falta `OPENAI_API_KEY` en `backend/.env` |
| 502 `upstream_model` | modelo no disponible para la cuenta (`OPENAI_REALTIME_MODEL`) |
| 429 `rate_limited` | límites `REALTIME_MAX_SESSIONS_PER_*` |
| Conecta pero sin audio | permisos de micrófono, ruta de audio (`AudioRouting`), log `WebRtcRealtime` |
| Herramientas por voz no responden | `function_call_output` + `response.create` en `RealtimeSessionController` |

Contrato y eventos: `.claude/rules/realtime-voice.md` y `../JARVIS-KB/07-RESEARCH/openai-realtime.md`.
