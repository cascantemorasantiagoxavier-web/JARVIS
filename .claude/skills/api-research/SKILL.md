---
name: api-research
description: Investiga APIs y dependencias para J.A.R.V.I.S. con fuentes oficiales (versiones en Maven/npm, contratos de OpenAI, OSM, Open-Meteo, Open Food Facts), precios y límites. Úsala antes de añadir o actualizar una dependencia, al integrar un servicio externo o cuando haya dudas sobre un endpoint.
---

# Investigación de APIs y dependencias

## Regla de oro
Solo fuentes oficiales: documentación del proveedor, su SDK publicado o su repositorio de artefactos.
Nada de "lo recuerdo así". Si la fuente no es accesible, la conclusión es **NO VERIFICABLE**, no una suposición.

## 1. Versiones de dependencias
```bash
S=.claude/skills/api-research/scripts/check-maven-version.sh
$S com.google.zxing:core:3.5.4          # ¿existe esa versión? ¿cuál es la última?
$S npm:openai npm:express@5.3.0         # registro npm
$S --catalog                            # todo gradle/libs.versions.toml
```
AndroidX/Compose/AGP viven en Google Maven: si `dl.google.com` está bloqueado el script lo dice.
Al añadir una dependencia: versión exacta en el catálogo, licencia compatible y nota en la KB si no se verificó.

## 2. Contratos de API (forma de peticiones y respuestas)
1. Preferir los **tipos del SDK oficial** descargado del registro (p. ej. `npm pack openai@<versión>` en un
   directorio temporal y leer `resources/**/*.d.ts`). Así se verificaron Realtime GA, Responses
   (`input_image`, `text.format` json_schema estricto) y `audio/transcriptions`.
2. Después, la página de referencia del proveedor (con fecha de consulta).
3. Escribir una prueba con fetch simulado que fije esa forma (`backend/test/*.test.js`) y, si cruza
   Kotlin ↔ Node, ampliar `BackendIntegrationTest`.

## 3. Servicios usados hoy (detalles en la KB, no aquí)
| Servicio | Uso | Nota KB |
|---|---|---|
| OpenAI Realtime / Responses / Transcriptions | voz, chat, visión, notas de voz | `07-RESEARCH/openai-realtime.md`, `costos.md` |
| Nominatim + Overpass (OSM) | negocios | `07-RESEARCH/osm-costa-rica.md` (1 petición/s, User-Agent, ODbL) |
| SearXNG / Tavily / Brave / Wikipedia | búsqueda web | `07-RESEARCH/busqueda-web.md` |
| Open-Meteo | clima y pronóstico diario | sin clave; atribución CC BY 4.0 |
| Open Food Facts | productos por código de barras | ODbL; User-Agent identificable |

## 4. Entregable
Nota en `../JARVIS-KB/07-RESEARCH/<tema>.md` con: fuente y fecha, versión/endpoint verificado, costos y
límites, lo que quedó sin verificar y por qué. Si cambia una decisión, ADR en `05-DECISIONS/`.
Nunca copiar claves ni respuestas con datos personales a la KB.
