#!/usr/bin/env python3
"""Genera ../JARVIS-KB/02-ARCHITECTURE/herramientas.md desde shared/tools.json.
Uso: python3 -I scripts/skills/tools_doc.py
"""
import datetime
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
tools = json.loads((ROOT / "shared/tools.json").read_text(encoding="utf-8"))["tools"]
RISK = {"read": "lectura", "local_write": "modifica local", "external": "externa", "sensitive": "sensible"}
exposed = [t for t in tools if t.get("exposed", True)]
internal = [t for t in tools if not t.get("exposed", True)]


def table(rows):
    out = ["| Herramienta | Dónde | Riesgo | Confirma | Permisos | Descripción |", "|---|---|---|---|---|---|"]
    for t in rows:
        out.append(f"| `{t['name']}` | {t['site']} | {RISK[t['risk']]} | {'sí' if t.get('confirm') else 'no'} | {', '.join(t.get('permissions', [])) or '—'} | {t['description']} |")
    return "\n".join(out)


doc = f"""---
tipo: arquitectura
actualizado: {datetime.date.today().isoformat()}
generado-desde: JARVIS/shared/tools.json (scripts/skills/tools_doc.py)
---
# Catálogo de herramientas

{len(tools)} herramientas en `JARVIS/shared/tools.json`, la fuente única para chat, voz Realtime, botones y
habilidades. Si esta nota y el archivo difieren, manda el archivo. Regenerar con
`python3 -I scripts/skills/tools_doc.py`.

- **Dónde**: `device` = teléfono; `backend` = el teléfono llama al servidor.
- **Riesgo** ([[05-DECISIONS/ADR-014-riesgo-y-confirmacion|ADR-014]]): lectura · modifica local · externa
  (confirmación salvo toque directo) · sensible (confirmación siempre).
- **Confirma**: `confirm: true`, confirmación explícita siempre, también por voz.

## Expuestas al modelo ({len(exposed)})
{table(exposed)}

## Internas, solo para habilidades ({len(internal)})
No se ofrecen al modelo directamente; las usan las habilidades a través de `SkillContext.tool()`.

{table(internal)}
"""
(ROOT.parent / "JARVIS-KB/02-ARCHITECTURE/herramientas.md").write_text(doc, encoding="utf-8")
print(f"{len(tools)} herramientas ({len(exposed)} expuestas, {len(internal)} internas)")
