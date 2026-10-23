# Servidor de J.A.R.V.I.S.

Node 22 + Express 5. Guarda la clave de OpenAI y hace de intermediario seguro para la app Android.
Instalación paso a paso: [`../docs/SETUP.md`](../docs/SETUP.md). Hosting: [`../docs/HOSTING.md`](../docs/HOSTING.md).

```bash
npm ci
cp .env.example .env      # rellena JARVIS_CLIENT_TOKEN y OPENAI_API_KEY
npm run gen-token
npm start                 # http://localhost:8787/health
npm test                  # sin red
npm run test:live         # con red y claves reales (opt-in)
```

## Rutas

Todas las de `/api` requieren `Authorization: Bearer <JARVIS_CLIENT_TOKEN>`.

| Método | Ruta | Uso |
|---|---|---|
| GET | `/health` | Estado y funciones disponibles (sin secretos) |
| POST | `/api/realtime/session` | Client secret efímero de OpenAI Realtime (modo "ephemeral") |
| POST | `/api/realtime/call` | Negocia la llamada WebRTC con OpenAI y devuelve el SDP (modo "unified") |
| POST | `/api/realtime/call/:callId/hangup` | Cuelga una llamada |
| GET | `/api/usage` | Contadores del día (voz y chat) y límites |
| POST | `/api/chat` | Turno de chat con herramientas (Responses API, `store:false`) |
| POST | `/api/web/search` | Búsqueda web con fuentes (SearXNG, Tavily, Brave, OpenAI opcional, Wikipedia) |
| POST | `/api/web/fetch` | Lee una página pública (protección SSRF, tamaño máximo) |
| POST | `/api/business/search` | Negocios en OpenStreetMap con estado y fuente de cada dato |
| GET | `/api/business/:id` | Ficha de un negocio |
| POST | `/api/business/:id/verify` | Contrasta teléfono y correo con el sitio web del negocio (verificado o conflicto); sin web, lista candidatos NO verificados |

## Estructura

```
src/config.js                 variables de entorno validadas con zod
src/logger.js                 logs JSON con redacción de secretos
src/http/                     errores, autenticación, validación, cliente HTTP, safeFetch (SSRF)
src/services/openai/          persona, realtime, chat
src/services/search/          proveedores, normalización, extractor de páginas
src/services/business/        categorías, clientes OSM, normalización, servicio
src/services/usage.js         límites de costo (en memoria)
src/routes/                   esquemas zod y rutas
```

La app envía en cada petición las herramientas (`../shared/tools.json`) y la personalidad
(`../shared/persona-es.md`); el servidor valida los nombres y tiene una persona mínima de respaldo.
