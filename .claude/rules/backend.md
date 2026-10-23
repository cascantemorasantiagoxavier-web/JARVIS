# Reglas: backend (`backend/`)

- Node ≥ 22.9, ESM, Express 5. Dependencias fijadas a versión exacta en `package.json`.
- Configuración solo por variables de entorno (`src/config.js` valida con zod). Documentar cada
  variable nueva en `.env.example` con valor vacío si es secreta.
- Todas las rutas `/api/*` exigen `Authorization: Bearer <JARVIS_CLIENT_TOKEN>` (comparación en
  tiempo constante). `/health` es pública y no revela configuración sensible.
- Validar cada cuerpo con zod (`src/routes/schemas.js`). Errores con `AppError`/`Errors`, mensajes
  en español, sin trazas ni datos internos hacia el cliente.
- Logs con `logger` (redacta claves, tokens y cabeceras de autorización). Nunca registrar el
  contenido completo de conversaciones ni secretos.
- Peticiones salientes: `httpClient` con timeout (`OUTBOUND_TIMEOUT_MS`). Para URLs dadas por el
  usuario o por el modelo, `safeFetch` (bloquea IP privadas, `localhost`, `.local`, redirecciones a
  redes internas, tamaño máximo).
- Costos: límites de sesiones Realtime por hora/día y de peticiones de chat por día. `/api/usage`
  expone los contadores. La búsqueda web de OpenAI (de pago) está desactivada por defecto.
- OpenStreetMap: User-Agent identificable con `OSM_CONTACT`, Nominatim a máx. 1 petición/s, caché
  (`BUSINESS_CACHE_TTL_SECONDS`), atribución "© OpenStreetMap contributors, ODbL" en cada fuente.
- Negocios: nunca completar datos faltantes. Cada campo devuelve `value`, `status`, `sources`.
  Los teléfonos de Costa Rica se normalizan a +506; si no se puede, se conservan tal como vienen de
  la fuente. Al extraer correos de páginas web se descartan los dominios de ejemplo (RFC 2606).
- Pruebas: `npm test` no usa red (fetch simulado). Las pruebas reales viven en `test/live` y solo
  corren con `npm run test:live`.
