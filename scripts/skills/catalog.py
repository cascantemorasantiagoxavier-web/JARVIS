#!/usr/bin/env python3
"""Genera el catálogo de habilidades con su estado real a partir de los manifiestos y las pruebas.

Uso: python3 -I scripts/skills/catalog.py [--write]
  sin --write: imprime la tabla en Markdown
  --write:     escribe ../JARVIS-KB/02-ARCHITECTURE/habilidades.md

El estado replica SkillRegistry.availability: NO_IMPLEMENTADA si no hay implementación;
REQUIERE_CONFIGURACIÓN si falta un servicio configurable (servidor, bóveda) o el modelo obligatorio;
PARCIAL si el manifiesto es "partial"; si no, IMPLEMENTADA.
"""
import datetime
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
KB = ROOT.parent / "JARVIS-KB"
CONFIGURABLE = {"jarvis_server": "servidor", "obsidian_vault": "bóveda"}
RISK = {"read": "lectura", "local_write": "modifica local", "external": "acción externa", "sensitive": "sensible"}

manifests = [json.loads(p.read_text(encoding="utf-8")) for p in sorted((ROOT / "shared/skills").glob("*.json"))]
impl = "\n".join(p.read_text(encoding="utf-8") for p in (ROOT / "app/Android/core/src/main/kotlin/com/example/jarvis/core/skills/impl").glob("*.kt"))
implemented = set(re.findall(r'override val id = "([a-z_]+)"', impl)) | set(re.findall(r'Skill\("([a-z_]+)"', impl))

# Partes que dependen de Android real (solo verificables en un teléfono)
DEVICE_TOOLS = {"compose_email": "correo", "compose_message": "SMS/WhatsApp", "save_document": "guardar archivo",
                "open_document": "abrir archivo", "open_map_point": "mapa", "open_location": "mapa", "get_directions": "mapa",
                "share_text": "compartir", "create_calendar_event": "calendario", "calendar_events": "calendario",
                "get_device_status": "estado del teléfono", "call_phone": "marcador", "open_app": "abrir apps",
                "create_task": "avisos", "search_notes": "bóveda (SAF)"}
PERMS = {"camera": "cámara", "microphone": "micrófono", "calendar.read": "calendario", "location": "ubicación"}
media_use = {}
for f in (ROOT / "app/Android/core/src/main/kotlin/com/example/jarvis/core/skills/impl").glob("*.kt"):
    for block in re.split(r"\n(?=class |object |internal class )", f.read_text(encoding="utf-8")):
        ids = re.findall(r'override val id = "([a-z_]+)"', block)
        uses = set()
        if re.search(r"pickFile\(|pickDocument\(", block): uses.add("selector de archivos")
        if re.search(r"capturePhoto\(|ctx\.image\(", block): uses.add("cámara")
        if "recordAudio(" in block: uses.add("micrófono")
        for i in ids: media_use.setdefault(i, set()).update(uses)


def device_parts(m):
    parts = set(media_use.get(m["id"], set()))
    parts |= {PERMS[p] for p in m.get("requiredPermissions", []) + m.get("optionalPermissions", []) if p in PERMS}
    parts |= {DEVICE_TOOLS[t] for t in m.get("tools", []) if t in DEVICE_TOOLS}
    if "obsidian_vault" in m.get("requiresExternalService", []) + m.get("optionalServices", []):
        parts.add("bóveda (SAF)")
    if m["id"] == "routine_automation":
        parts.add("según los pasos")
    return ", ".join(sorted(parts)) or "—"


# Pruebas específicas por habilidad: funciones @Test cuyo cuerpo menciona el id
tests = {}
for f in (ROOT / "app/Android/core/src/test/kotlin").rglob("*Test.kt"):
    text = f.read_text(encoding="utf-8")
    for m in re.finditer(r"@Test fun (\w+)\(\)(.*?)(?=@Test fun |\Z)", text, re.S):
        for sid in re.findall(r'"([a-z_]+)"', m.group(2)):
            tests.setdefault(sid, set()).add(f"{f.stem}.{m.group(1)}")
contract = len(re.findall(r"@Test fun", (ROOT / "app/Android/core/src/test/kotlin/com/example/jarvis/core/SkillContractTest.kt").read_text(encoding="utf-8")))


def state(m, server, vault):
    if m.get("maturity") == "not_implemented" or m["id"] not in implemented:
        return "NO_IMPLEMENTADA", m.get("missing", [])
    have = {"jarvis_server": server, "obsidian_vault": vault}
    missing = [f"requiere {CONFIGURABLE[s]}" for s in m.get("requiresExternalService", []) if s in CONFIGURABLE and not have[s]]
    if m.get("model") == "required" and not server and "jarvis_server" not in m.get("requiresExternalService", []):
        missing.append("requiere servidor (IA)")
    if missing:
        return "REQUIERE_CONFIGURACIÓN", missing
    if m.get("maturity") == "partial":
        return "PARCIAL", m.get("missing", [])
    return "IMPLEMENTADA", []


rows = []
for m in manifests:
    bare, why = state(m, False, False)
    full, pend = state(m, True, True)
    rows.append((m, bare, why, full, pend, sorted(tests.get(m["id"], set()) - {f"SkillContractTest.{x}" for x in []})))

counts_bare, counts_full = {}, {}
for _, b, _, f, _, _ in rows:
    counts_bare[b] = counts_bare.get(b, 0) + 1
    counts_full[f] = counts_full.get(f, 0) + 1

out = []
out.append(f"Total: {len(rows)} habilidades · pruebas genéricas de contrato para todas: {contract} (SkillContractTest)\n")
out.append("Resumen sin configurar nada: " + ", ".join(f"{k} {v}" for k, v in sorted(counts_bare.items())))
out.append("\nResumen con servidor y bóveda: " + ", ".join(f"{k} {v}" for k, v in sorted(counts_full.items())) + "\n")
out.append("| Habilidad | Categoría | Riesgo | Sin configurar | Con servidor y bóveda | Pendiente | Pruebas específicas | Sin probar en teléfono |")
out.append("|---|---|---|---|---|---|---|---|")
for m, bare, why, full, pend, t in rows:
    out.append(f"| `{m['id']}` {m['name']} | {m['category']} | {RISK[m['risk']]} | {bare}{' (' + '; '.join(why) + ')' if why and bare == 'REQUIERE_CONFIGURACIÓN' else ''} | {full} | {'; '.join(pend) if pend else '—'} | {len(t)} | {device_parts(m)} |")
table = "\n".join(out)

if "--write" in sys.argv:
    today = datetime.date.today().isoformat()
    doc = f"""---
tipo: catalogo
actualizado: {today}
generado: JARVIS/scripts/skills/catalog.py
---
# Catálogo de habilidades

Generado desde `JARVIS/shared/skills/*.json` y las pruebas de `:core`. **No editar a mano**: ejecutar
`python3 -I scripts/skills/catalog.py --write`.

El estado depende de la configuración del teléfono (la app lo calcula en vivo en la pantalla
*Skills*). "Sin probar en teléfono" lista las partes que dependen de Android real (cámara, micrófono, selector,
intents) y que por eso siguen NO TESTEADO. "Pruebas específicas" cuenta las pruebas automáticas que ejecutan esa habilidad además de
las genéricas de contrato. Todas son AUTOMATIZADO con datos simulados: verifican la lógica, no los
servicios reales (OpenAI, OSM, cámara, micrófono), que quedan NO TESTEADO hasta probar en un teléfono.

Cómo añadir una habilidad: `JARVIS/docs/SKILLS.md`. Decisiones: [[05-DECISIONS/ADR-013-sistema-de-habilidades|ADR-013]],
[[05-DECISIONS/ADR-014-riesgo-y-confirmacion|ADR-014]].

{table}
"""
    (KB / "02-ARCHITECTURE/habilidades.md").write_text(doc, encoding="utf-8")
    print(f"Escrito {KB / '02-ARCHITECTURE/habilidades.md'}")
else:
    print(table)
