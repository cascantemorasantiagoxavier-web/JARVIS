#!/usr/bin/env python3
"""Convierte errores de un log de Gradle en anotaciones de GitHub Actions (::error ...).

Las anotaciones se pueden leer con la API pública de GitHub en repositorios públicos, sin token,
lo que permite diagnosticar la compilación sin descargar el log completo.
Uso: python3 scripts/ci/annotate_gradle_errors.py build-*.log
"""
import pathlib
import re
import sys

WORKSPACE_MARK = "/app/Android/"
MAX = 45  # GitHub muestra hasta 50 anotaciones por paso
kotlin = re.compile(r"^e: (?:file://)?(\S+?\.kts?):(\d+):(\d+) (.+)$")
ksp = re.compile(r"^e: \[ksp\] (\S+?\.kt):(\d+): (.+)$")
javac = re.compile(r"^(\S+?\.java):(\d+): error: (.+)$")
aapt = re.compile(r"^ERROR: ?(\S+?\.xml):(\d+): (.+)$")


def rel(path):
    i = path.find(WORKSPACE_MARK)
    return "app/Android/" + path[i + len(WORKSPACE_MARK):] if i >= 0 else path


def esc(s):
    return s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")[:900]


seen, out = set(), []
for log in sys.argv[1:]:
    p = pathlib.Path(log)
    if not p.exists():
        continue
    lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
    for line in lines:
        for rx in (kotlin, ksp, javac, aapt):
            m = rx.match(line.strip())
            if m:
                g = m.groups()
                file, ln = rel(g[0]), g[1]
                col = g[2] if rx is kotlin else None
                msg = g[-1]
                key = (file, ln, msg)
                if key not in seen:
                    seen.add(key)
                    loc = f"file={file},line={ln}" + (f",col={col}" if col else "")
                    out.append(f"::error {loc},title={p.stem}::{esc(msg)}")
                break
    # Bloque "What went wrong" de Gradle (dependencias que no resuelven, plugins, etc.)
    text = "\n".join(lines)
    for block in re.findall(r"\* What went wrong:\n(.+?)(?:\n\* Try:|\Z)", text, re.S):
        msg = block.strip()
        if msg and msg not in seen:
            seen.add(msg)
            out.append(f"::error title={p.stem} (Gradle)::{esc(msg)}")
for line in out[:MAX]:
    print(line)
if len(out) > MAX:
    print(f"::warning::{len(out) - MAX} errores más en los logs")
print(f"{len(out)} errores anotados", file=sys.stderr)
