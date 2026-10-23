# Habilidades de J.A.R.V.I.S.

Una **habilidad** es una capacidad de varias etapas (por ejemplo "registrar un gasto leyendo un recibo")
que combina herramientas del contrato único (`shared/tools.json`) con lógica propia en Kotlin. No es
código arbitrario: el modelo solo puede pedir `run_skill` con un id registrado y una entrada que se
valida contra un esquema.

Catálogo y estado real de cada una: `../JARVIS-KB/02-ARCHITECTURE/habilidades.md`
(generado con `python3 -I scripts/skills/catalog.py --write`).
Decisiones: ADR-013 (sistema de habilidades) y ADR-014 (riesgo y confirmación) en `../JARVIS-KB/05-DECISIONS/`.

> Las "skills" de Claude Code (`.claude/skills/`) son otra cosa: instrucciones para quien desarrolla
> el proyecto. Esta guía trata de las habilidades **de la app**.

## Piezas

| Pieza | Dónde | Qué hace |
|---|---|---|
| Manifiesto | `shared/skills/<id>.json` | Declaración: ejemplos, esquemas de entrada/salida, herramientas, permisos, servicios, costo, riesgo, límites, cancelación, errores, madurez, pruebas |
| Generador | `scripts/skills/gen_manifests.py` | Fuente de los manifiestos (`python3 -I scripts/skills/gen_manifests.py shared/skills`) |
| Implementación | `core/.../skills/impl/*.kt` | Clase que implementa `Skill` (`execute`, `parseText` opcional) |
| Registro | `SkillRegistry` | Comprueba que las herramientas existen y que el riesgo de la habilidad ≥ el de cada herramienta; selecciona por lenguaje natural; calcula el estado |
| Motor | `SkillEngine` | Existe → implementada → valida entrada → configuración → permisos → ejecuta con tiempo límite y cancelación → valida salida → registra |
| Contexto | `SkillContext` | Único puente: `tool()` (solo las declaradas, con tope de llamadas), `confirm`, `permission`, `llm()`, colecciones locales |
| Meta-herramientas | `SkillToolset` | `list_skills`, `describe_skill`, `run_skill` para el chat y la voz |
| Ruta local | `SkillRouter` | Sin modelo (o sin servidor), ejecuta la habilidad si la frase coincide con confianza y `parseText` entiende la entrada |
| App | `AppContainer`, `RoomRecordStore`, `MediaBroker`, pantalla *Skills* | Persistencia en Room (`skill_records`), cámara/selector/grabadora, estado en vivo |

## Estados

| Estado | Significa |
|---|---|
| IMPLEMENTADA | Código completo y pruebas automáticas; disponible con la configuración actual |
| PARCIAL | Funciona, pero hay partes declaradas en `missing` que no existen (y se dicen al usuario) |
| REQUIERE_CONFIGURACIÓN | Falta el servidor JARVIS o la bóveda de Obsidian; la habilidad lo dice en vez de fallar |
| NO_IMPLEMENTADA | No hay implementación; nunca se simula |

Que una habilidad esté IMPLEMENTADA significa que su lógica está verificada con pruebas automáticas
(datos simulados). Lo que depende del teléfono real (cámara, micrófono, selector, intents) sigue
NO TESTEADO hasta probarlo en un dispositivo; el catálogo lo indica por habilidad.

## Niveles de riesgo

| Nivel | Ejemplos | Confirmación |
|---|---|---|
| `read` | consultar, analizar, resumir | no |
| `local_write` | guardar un recuerdo, una tarea, un archivo en Descargas | no (salvo borrar) |
| `external` | abrir un borrador de correo, compartir, abrir WhatsApp con texto | sí, salvo toque directo del usuario en un botón |
| `sensitive` | borrar, olvidar, datos financieros, movimientos de inventario | siempre, también por voz |

La habilidad declara el riesgo máximo de lo que hace; el registro rechaza una habilidad `read` que use
una herramienta `external`. Nada se envía, publica, compra ni borra sin confirmación explícita.

## Añadir una habilidad

1. **Diseño**: comprobar que no duplica otra (`python3 -I scripts/skills/catalog.py`). Separar lectura,
   modificación local y acciones a terceros.
2. **Manifiesto**: añadir un `skill(...)` en `scripts/skills/gen_manifests.py` con al menos 3
   `exampleUtterances`, `inputSchema`/`outputSchema`, `tools`, permisos, servicios, `cost`, `risk`,
   `limits`, `cancellation`, `errorHandling`, `maturity` (`implemented`/`partial` + `missing`), `privacy`
   y `testCases`. Regenerar: `python3 -I scripts/skills/gen_manifests.py shared/skills`.
3. **Herramientas nuevas** (si hacen falta): declararlas en `shared/tools.json` con `risk` (y
   `"exposed": false` si solo las usan habilidades) e implementarlas en `CoreToolset`.
4. **Implementación**: clase en `core/.../skills/impl/` que implementa `Skill`. Usar solo `ctx.tool(...)`
   para efectos, `ctx.confirm(...)` antes de cualquier cambio sensible, `Outcome.*` para resultados
   (`needsConfig`, `denied`, `notConfirmed`, `invalid`, `fail` con `recovery`). Fuentes y avisos en el
   resultado; nunca inventar datos.
5. **Registro**: añadirla a `JarvisSkills.all()`.
6. **Pruebas** (`core/src/test`): `SkillContractTest` ya prueba para todas activación por ejemplos,
   entrada inválida, sin Internet, sin permisos, cancelación, tiempo límite y salida conforme al
   esquema. Añadir pruebas específicas: ejecución real con datos, error externo (`FakeBackend` con 5xx),
   confirmación rechazada y seguridad (datos no confiables, privacidad).
7. **Verificar**: `./gradlew -Pjarvis.coreOnly=true :core:test` y
   `python3 -I .claude/skills/architecture-audit/scripts/audit.py`.
8. **Documentar**: `python3 -I scripts/skills/catalog.py --write` y, si hubo una decisión, un ADR.

## Conexión con el modelo (chat y GPT Realtime)

- Las instrucciones llevan un **catálogo breve** (`SkillRegistry.briefCatalog`): las habilidades más
  relevantes para la petición con una línea cada una, el resto solo por id, y aparte las que requieren
  configuración. En voz no hay consulta previa: lista compacta. Nunca se envían todos los esquemas.
- El modelo pide `describe_skill` para ver los parámetros exactos y luego `run_skill`. El resultado es
  JSON estructurado (`status`, `summary`, `data`, `sources`, `warnings`, `error_code`, `recovery`).
- En voz, `run_skill` pasa por el mismo `ToolExecutor` que el chat: mismas confirmaciones en pantalla.
- Si el servidor no está configurado, `SkillRouter` resuelve en local las frases que coinciden con
  claridad (p. ej. "gasté 3500 en almuerzo"), sin modelo.

## Persistencia y privacidad

- Datos de las habilidades (gastos, inventario, CRM, tarjetas de estudio, rutinas…) en Room
  (`skill_records`), solo en el teléfono, sin copia de seguridad en la nube.
- Al servidor solo va lo imprescindible: texto a reescribir si se pide IA, la imagen o el audio que el
  usuario captó para esa tarea (no se guardan: `store:false`). Las notas privadas nunca.
- Contenido de terceros (web, documentos, imágenes, QR) es dato no confiable: no se siguen instrucciones
  que contenga y los enlaces no se abren solos.
