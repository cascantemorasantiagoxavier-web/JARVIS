#!/usr/bin/env python3
"""Revisión de una bóveda de Obsidian (la KB del proyecto o una copia de la del usuario).

Uso: python3 -I vault_lint.py <carpeta> [--strict]

Informa: notas, privadas (mismas reglas que NotePrivacy en :core), enlaces [[...]] rotos, títulos
duplicados, notas enormes y posibles SECRETOS. Solo lee. Código 1 si hay secretos (o, con --strict,
enlaces rotos). Nunca imprime el valor de un secreto: solo archivo, línea y tipo.
"""
import pathlib
import re
import sys
import unicodedata

if len(sys.argv) < 2:
    print(__doc__)
    sys.exit(2)
VAULT = pathlib.Path(sys.argv[1]).resolve()
STRICT = "--strict" in sys.argv
EXCLUDED = {"privado", "private", ".obsidian", ".trash"}
PRIVATE_TAGS = {"privado", "private", "confidencial", "no-jarvis"}
SECRET_PATTERNS = [
    ("clave OpenAI", re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}")),
    ("clave Google", re.compile(r"\bAIza[0-9A-Za-z_-]{30,}")),
    ("token GitHub", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}")),
    ("token Slack", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}")),
    ("clave privada", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("asignación de secreto", re.compile(r"(?i)\b(api[_-]?key|secret|password|contraseña|token)\s*[:=]\s*['\"]?[A-Za-z0-9_\-./+]{16,}")),
]


def fold(s):
    return "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")


def frontmatter(text):
    if not text.startswith("---\n"):
        return {}
    end = text.find("\n---", 4)
    if end < 0:
        return {}
    fm = {}
    for line in text[4:end].splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            fm[k.strip().lower()] = v.strip().strip("'\"")
    return fm


def tags(text, fm):
    body = re.sub(r"```.*?```", "", text, flags=re.S)  # bloques de código
    body = re.sub(r"`[^`\n]*`", "", body)                # código en línea: no son etiquetas (como Obsidian)
    found = {t.lower() for t in re.findall(r"(?<![\w/&#])#([\w/-]{2,})", body)}
    raw = fm.get("tags", "")
    found |= {t.strip(" []#").lower() for t in raw.split(",") if t.strip(" []#")}
    return found


def is_private(rel, fm, tg):
    v = {k: fold(x) for k, x in fm.items()}
    if v.get("privado") == "true" or v.get("private") == "true" or v.get("jarvis") in {"private", "privado", "no", "false"}:
        return True
    if tg & PRIVATE_TAGS:
        return True
    return any(seg.lower() in EXCLUDED for seg in rel.parts[:-1])


notes = sorted(p for p in VAULT.rglob("*.md") if ".obsidian" not in p.parts and ".trash" not in p.parts)
names = {}
for p in notes:
    names.setdefault(fold(p.stem), []).append(p)
private, broken, secrets, huge = [], [], [], []
for p in notes:
    rel = p.relative_to(VAULT)
    text = p.read_text(encoding="utf-8", errors="replace")
    fm = frontmatter(text)
    if is_private(rel, fm, tags(text, fm)):
        private.append(rel)
    prose = re.sub(r"`[^`\n]*`", "", re.sub(r"```.*?```", "", text, flags=re.S))  # el código no enlaza
    for target in re.findall(r"\[\[([^\]|#]+)(?:#[^\]|]*)?(?:\|[^\]]*)?\]\]", prose):
        target = target.strip().rstrip("\\")  # [[nota\|alias]] dentro de tablas
        stem = fold(pathlib.PurePosixPath(target).name.removesuffix(".md"))
        if stem and stem not in names and not (VAULT / target).exists():
            broken.append((rel, target))
    for n, line in enumerate(text.splitlines(), 1):
        for kind, rx in SECRET_PATTERNS:
            if rx.search(line) and "<" not in line and "…" not in line:
                secrets.append((rel, n, kind))
    if len(text) > 200_000:
        huge.append((rel, len(text)))
dups = {k: v for k, v in names.items() if len(v) > 1}

print(f"Bóveda: {VAULT}")
print(f"Notas: {len(notes)} · privadas (no se envían al modelo): {len(private)}")
for r in private[:20]:
    print(f"  privada  {r}")
print(f"Enlaces rotos: {len(broken)}")
for r, t in broken[:30]:
    print(f"  {r} → [[{t}]]")
print(f"Títulos duplicados (enlaces ambiguos): {len(dups)}")
for k, v in list(dups.items())[:10]:
    print("  " + " | ".join(str(p.relative_to(VAULT)) for p in v))
if huge:
    print("Notas muy grandes (se indexan por fragmentos; considerar dividirlas):")
    for r, n in huge:
        print(f"  {r} ({n // 1024} KB)")
print(f"Posibles secretos: {len(secrets)}")
for r, n, kind in secrets:
    print(f"  SECRETO? {r}:{n} ({kind}) — muévelo a backend/.env o a un gestor de contraseñas")
sys.exit(1 if secrets or (STRICT and broken) else 0)
