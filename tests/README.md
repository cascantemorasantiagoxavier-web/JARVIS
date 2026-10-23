# Pruebas de J.A.R.V.I.S.

Las pruebas viven junto al código de cada módulo. Esta carpeta es el mapa y el plan manual.
Ejecuta todo lo posible con `scripts/test-all.sh`. Clasificación: ver `.claude/rules/testing.md`.

## Automáticas

| Archivo | Qué cubre |
|---|---|
| `backend/test/config.test.js` | Variables de entorno, token obligatorio, valores por defecto, redacción de secretos en logs |
| `backend/test/app.test.js` | Token, `/health` sin secretos, validación, JSON inválido, 413, 404, 502, cabeceras, 429, la clave nunca aparece en respuestas |
| `backend/test/realtime.test.js` | Client secret efímero, llamada unificada (SDP multipart), errores de OpenAI, vigilante que cuelga al vencer, límites de costo |
| `backend/test/chat.test.js` | Responses API con herramientas, esfuerzo de razonamiento por modo, sin clave/límite diario, respuestas incompletas |
| `backend/test/search.test.js` | Proveedores de búsqueda, normalización, deduplicación, extractor de páginas, protección SSRF |
| `backend/test/business.test.js` | Overpass/Nominatim, categorías en español, zonas de Costa Rica, estado de cada dato, fusión de duplicados |
| `backend/test/media.test.js` | Visión (imagen en data URL, Structured Outputs estricto, campos vacíos → null, rechazo, incompleta), transcripción multipart, tipo por bytes, 401 antes de leer cuerpos grandes, límites diarios |
| `backend/test/live/live.test.js` | **En vivo** (opt-in): OpenAI, OSM y búsqueda reales |
| `core/.../LocalToolsTest.kt` | Hora, fechas en español ("mañana a las 3"), cálculos, conversiones |
| `core/.../ToolsInfraTest.kt` | Catálogo `shared/tools.json`, validación de argumentos, permisos, confirmaciones, registro |
| `core/.../IntentDetectorTest.kt` | Intenciones en español: negocios, mapa, tareas, memoria, web, notas, apps, clima |
| `core/.../MemoryNotesTasksTest.kt` | Memoria persistente (cerrar y reabrir), notas privadas, fragmentado Markdown, tareas |
| `core/.../BrainTest.kt` | Ruta local vs. modelo, bucle de herramientas, respaldo honesto sin servidor |
| `core/.../RealtimeTest.kt` | Controlador Realtime: herramientas, interrupción, inactividad, reconexión, `end_conversation`, costo |
| `core/.../GlobeTest.kt` | Máscara de tierra, proyección, capitales; genera `core/build/globe-preview.png` |
| `core/.../BackendIntegrationTest.kt` | Cliente Kotlin contra el backend Node REAL (proceso) con OpenAI/OSM/SearXNG simulados; habilidades de tarjeta y nota de voz de punta a punta |
| `core/.../SkillContractTest.kt` | Las 44 habilidades: manifiestos válidos, activación por ejemplos, entrada inválida, sin servidor/Internet, sin permisos, cancelación, tiempo límite, salida conforme al esquema, herramientas no declaradas bloqueadas |
| `core/.../SkillsProductivityTest.kt` | Editor, hojas de cálculo, documentos (DOCX/XLSX/ODT), informes, copiloto de código, inventarios, ventas |
| `core/.../SkillsAssistantResearchTest.kt` | Resumen del día, tareas, memoria, diagnóstico, mapas, Obsidian, reuniones, rutinas, investigación, precios, correo, mensajería, redes, marketing, sitios web |
| `core/.../SkillsFinanceLifeTest.kt` | Gastos, finanzas, flujo de caja, cotizaciones, CRM, negociación, contratos, competencia, proveedores, agenda, renovaciones, viajes, comidas, tutor, idiomas, códigos de barras/QR, notas de voz, visión |
| `app/src/test/.../RoomPersistenceTest.kt` | Room real (Robolectric): memoria tras reabrir, prospectos únicos, historial con tarjetas, FTS sin tildes, datos de habilidades (`skill_records`) |
| `app/src/test/.../HomeUiTest.kt` | UI (Robolectric): arranca en el núcleo y no en el mapa, chat local, negocios sin mapa, mapa bajo petición, ajustes |

## Manuales (EMULADOR / DISPOSITIVO FÍSICO)

Anota modelo de teléfono, versión de Android y resultado en `JARVIS-KB/08-TESTS/`.

1. **Arranque:** abre la app → se ve el núcleo holográfico, no el mapa. Gira el teléfono: el diseño
   horizontal muestra las tres columnas.
2. **Sin servidor:** "¿qué hora es?", "recuérdame en 2 minutos tomar agua" → llega la notificación.
3. **Servidor:** Ajustes → URL + token → Probar conexión → "En línea".
4. **Voz Realtime:** toca el núcleo → saluda; pregunta el clima; interrumpe mientras habla → se
   calla; espera 30 s en silencio → se cierra solo. Revisa el costo en Ajustes.
5. **Herramientas por voz:** "busca ferreterías en Heredia" → tarjetas sin abrir el mapa; "muéstralas
   en el mapa" → se abre el mapa; "guarda la primera como prospecto".
6. **Confirmación:** "olvida mi preferencia de café" → aparece el diálogo; Cancelar no borra nada.
7. **Palabra clave** (APK con Sherpa): actívala → notificación persistente; di "JARVIS" con la
   pantalla apagada → inicia la conversación. Pulsa Detener en la notificación.
8. **Obsidian:** vincula la bóveda → Reindexar → busca una nota; confirma que una nota en
   `Privado/` no aparece en las respuestas de la IA.
9. **Reinicio:** reinicia el teléfono con un recordatorio pendiente → la alarma se reprograma.
10. **Llamar/compartir:** en una tarjeta con teléfono, "Llamar" abre el marcador (no llama solo).
11. **Habilidades** (pantalla *Skills*): los estados coinciden con la configuración (sin servidor,
    las que lo requieren dicen REQUIERE CONFIG.). Tocar un ejemplo lo envía al chat.
12. **Cámara:** "escanea este código de barras" → abre la cámara del sistema → muestra el código y,
    si existe, el producto de Open Food Facts. "Lee esta tarjeta" con servidor → contacto marcado
    "verificar"; guardar pide confirmación.
13. **Recibo:** "registra este recibo" → foto → confirmación con monto y comercio → aparece en
    "¿cuánto gasté este mes?".
14. **Nota de voz:** "graba una nota de voz" → diálogo con tiempo y Guardar/Cancelar → transcripción
    en la bandeja; Cancelar no envía nada al servidor.
15. **Documentos:** "analiza este Excel" → selector del sistema → resumen; "crea una cotización…" →
    archivo en Descargas/JARVIS que se abre con "abrir".
16. **Correo/mensajes:** "redacta un correo a…" → confirmación → la app de correo abre el borrador
    sin enviarlo; WhatsApp abre el chat con el texto, sin enviarlo.
