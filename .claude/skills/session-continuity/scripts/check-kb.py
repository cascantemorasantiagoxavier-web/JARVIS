#!/usr/bin/env python3
"""Comprueba que la base de conocimiento quedó al día antes de cerrar una sesión de trabajo.

Uso: python3 -I check-kb.py [--date AAAA-MM-DD] [--kb RUTA] [--verbose]
Solo lee. Código 1 si falta algo obligatorio; imprime qué actualizar.
"""
import argparse
import datetime
import pathlib
import re
import subprocess
import sys

here = pathlib.Path(__file__).resolve()
repo = here.parents[4]
ap = argparse.ArgumentParser()
ap.add_argument("--date", default=datetime.date.today().isoformat())
ap.add_argument("--kb", default=str(repo.parent / "JARVIS-KB"))
ap.add_argument("--verbose", action="store_true", help="lista también lo que está bien")
args = ap.parse_args()
KB = pathlib.Path(args.kb).resolve()
today = args.date
todo, ok = [], []


def need(cond, good, bad):
    (ok if cond else todo).append(good if cond else bad)


def updated(path):
    if not path.exists():
        return None
    m = re.search(r"^actualizado:\s*(\d{4}-\d{2}-\d{2})", path.read_text(encoding="utf-8"), re.M)
    return m.group(1) if m else None


cont = KB / "06-TASKS/CONTINUIDAD.md"
need(updated(cont) == today, "CONTINUIDAD.md actualizada hoy", f"Actualizar 06-TASKS/CONTINUIDAD.md (frontmatter 'actualizado: {today}', dónde quedó y próxima tarea)")
need(updated(KB / "06-TASKS/estado.md") == today, "estado.md actualizado hoy", "Actualizar 06-TASKS/estado.md")
need((KB / f"09-DAILY-LOG/{today}.md").exists(), "bitácora del día", f"Crear 09-DAILY-LOG/{today}.md (qué se hizo, qué falló, decisiones)")
tests = sorted((KB / "08-TESTS").glob(f"{today}*.md"))
need(bool(tests), "resultados de pruebas del día", f"Registrar resultados en 08-TESTS/{today}-resultados.md con la clasificación (AUTOMATIZADO/EMULADOR/DISPOSITIVO FÍSICO/NO TESTEADO)")
if tests:
    t = "\n".join(p.read_text(encoding="utf-8") for p in tests)
    need(re.search(r"AUTOMATIZADO|EMULADOR|DISPOSITIVO F[IÍ]SICO|NO TESTEADO", t), "resultados clasificados", "Los resultados del día no usan las etiquetas de clasificación")

# Cada ADR aparece en el registro y en el índice no hay enlaces a notas inexistentes
registro = (KB / "05-DECISIONS/registro-de-decisiones.md").read_text(encoding="utf-8")
for adr in sorted((KB / "05-DECISIONS").glob("ADR-*.md")):
    need(adr.stem in registro, f"{adr.stem} en el registro", f"Añadir {adr.stem} a 05-DECISIONS/registro-de-decisiones.md")
index = (KB / "00-INDEX.md").read_text(encoding="utf-8")
for target in re.findall(r"\[\[([^\]|#]+)", index):
    t = target.strip().rstrip("\\")
    need((KB / f"{t}.md").exists(), f"índice → {t}", f"00-INDEX.md enlaza a {t}, que no existe")
for p in tests:
    need(p.stem in index, f"índice enlaza {p.stem}", f"Enlazar 08-TESTS/{p.stem} desde 00-INDEX.md")

# Cambios sin commit en el repositorio de código
try:
    dirty = subprocess.run(["git", "-C", str(repo), "status", "--porcelain"], capture_output=True, text=True, check=True).stdout.strip()
    need(not dirty, "repositorio sin cambios pendientes", "Hay cambios sin commit en JARVIS (revisar con scan-secrets.sh --staged y hacer commit)")
except Exception:
    todo.append("No se pudo consultar git")

print(f"Base de conocimiento: {KB}  ·  fecha: {today}")
if args.verbose:
    for o in ok:
        print(f"  OK     {o}")
else:
    print(f"  OK     {len(ok)} comprobaciones")
for t in todo:
    print(f"  FALTA  {t}")
sys.exit(1 if todo else 0)
