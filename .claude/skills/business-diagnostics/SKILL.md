---
name: business-diagnostics
description: Diagnostica la búsqueda de negocios de Costa Rica en J.A.R.V.I.S. (OpenStreetMap vía Nominatim/Overpass, estado por dato, fuentes, verificación, prospectos, business_prospecting y supplier_verifier). Úsala cuando la búsqueda devuelva pocos o malos resultados, falten teléfonos, o al cambiar categorías o normalización.
---

# Búsqueda y diagnóstico de negocios

## Principio
Nunca completar datos. Cada campo devuelve `value`, `status` (`found`, `verified`, `unavailable`,
`conflict`, `approximate`) y `sources`. "No disponible" es una respuesta válida; inventar no lo es.

## 1. Pruebas automáticas (sin red)
```bash
.claude/skills/business-diagnostics/scripts/business-search.sh --offline
```

## 2. Consulta real contra el servidor (una por ejecución; respeta Nominatim 1 pet./s)
```bash
export JARVIS_URL=http://127.0.0.1:8787 JARVIS_CLIENT_TOKEN=...   # token del backend/.env, no en el chat
.claude/skills/business-diagnostics/scripts/business-search.sh "ferreterías" "Heredia"
.claude/skills/business-diagnostics/scripts/business-search.sh --file respuesta.json   # analizar una guardada
```
Muestra el estado de cada dato, una muestra de resultados y **anomalías** (código 1): valor sin fuente,
estado `found` sin valor, teléfono +506 mal formateado, fuente OSM sin atribución ODbL. Nombres repetidos
salen como "Revisar" (pueden ser sucursales).

## 3. Causas frecuentes
| Síntoma | Causa probable | Dónde |
|---|---|---|
| 404 `location_not_found` | Nominatim no reconoce la zona | probar provincia/cantón; `resolveArea` |
| 0 resultados | categoría sin etiqueta OSM equivalente | `backend/src/services/business/categories.js` |
| Muchos `unavailable` en teléfono/web | OSM no tiene el dato (normal en CR) | ofrecer `verify` (sitio oficial) |
| `approximate` en coordenadas | el negocio es un polígono (centro) | correcto; avisar al usuario |
| `conflict` | dos fuentes dicen cosas distintas | mostrar alternativas, no elegir en silencio |
| 429/504 | límites de Overpass/Nominatim | caché (`BUSINESS_CACHE_TTL_SECONDS`), esperar |

## 4. Reglas
- Contactar negocios lo hace el usuario: llamar = marcador; mensajes = borrador. Nunca envíos automáticos.
- Prospectos (`save_prospect`) solo con datos de la fuente; las notas del usuario van aparte.
- Clasificar lo verificado: AUTOMATIZADO (fixtures sintéticos) ≠ prueba real contra OSM.

Detalle: `../JARVIS-KB/07-RESEARCH/osm-costa-rica.md`, `../JARVIS-KB/05-DECISIONS/ADR-009-negocios-estado-por-dato.md`.
