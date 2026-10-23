# Reglas: pruebas

## Clasificación obligatoria
Cada resultado se informa con una de estas etiquetas, sin redondear hacia arriba:
- **AUTOMATIZADO**: se ejecutó una prueba automática y pasó (indicar comando y número).
- **EMULADOR**: se probó en un emulador Android (indicar API e imagen).
- **DISPOSITIVO FÍSICO**: se probó en un teléfono real (indicar modelo y versión de Android).
- **NO TESTEADO**: no se ejecutó. Decir por qué.

Una prueba con datos simulados verifica la lógica, no el servicio real: decirlo.

## Suites
| Suite | Comando | Red | Notas |
|---|---|---|---|
| Backend | `cd backend && npm test` | No | fetch simulado; arranca la app Express real |
| Backend en vivo | `npm run test:live` | Sí | requiere `.env` con claves; opt-in |
| Núcleo | `cd app/Android && ./gradlew -Pjarvis.coreOnly=true :core:test` | No | incluye integración con `node src/server.js` + MockWebServer |
| App (Robolectric) | `./gradlew :app:testDebugUnitTest` | Google Maven | Room, navegación, chat local |
| App instrumentada | emulador o teléfono | — | audio, WebRTC, permisos, servicio |

`scripts/test-all.sh` ejecuta todo lo que el entorno permita y dice qué omitió.

## Buenas prácticas
- Hora fija en pruebas (`TestKit`: 2025-09-29T13:42Z = 07:42 en Costa Rica).
- `runBlocking` cuando hay E/S real (procesos, sockets); `runTest` solo con tiempo virtual.
- Sin red en las suites normales. Los fixtures sintéticos se etiquetan como sintéticos.
- Cada bug corregido deja una prueba que lo habría detectado.
- Registrar los resultados en `../JARVIS-KB/08-TESTS/` con fecha.
