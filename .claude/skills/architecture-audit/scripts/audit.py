#!/usr/bin/env python3
"""Auditoría estática de las reglas de arquitectura de J.A.R.V.I.S.

Uso: python3 -I .claude/skills/architecture-audit/scripts/audit.py [raíz del repo]
Sale con código 1 si alguna regla obligatoria falla. No modifica nada.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[4]).resolve()
CORE = ROOT / "app/Android/core/src/main/kotlin"
APP = ROOT / "app/Android/app/src/main/kotlin"
results = []  # (ok, regla, detalle)


def check(ok, rule, detail=""):
    results.append((bool(ok), rule, detail))


def kotlin_files(base):
    return sorted(base.rglob("*.kt")) if base.exists() else []


def rel(p):
    return str(p.relative_to(ROOT))


# 1. :core es Kotlin/JVM puro
bad = [rel(f) for f in kotlin_files(CORE) if re.search(r"^import (android|androidx)\.", f.read_text(encoding="utf-8"), re.M)]
check(not bad, ":core no importa android.* ni androidx.*", ", ".join(bad[:5]))

# 2. Contrato de herramientas: cada herramienta declarada tiene implementación
tools = json.loads((ROOT / "shared/tools.json").read_text(encoding="utf-8"))["tools"]
core_toolset = (CORE / "com/example/jarvis/core/toolset/CoreToolset.kt").read_text(encoding="utf-8")
skill_toolset = (CORE / "com/example/jarvis/core/skills/SkillToolset.kt").read_text(encoding="utf-8")
implemented = set(re.findall(r'"([a-z_]+)" to ToolHandler', core_toolset)) | set(re.findall(r'register\("([a-z_]+)"', skill_toolset))
declared = {t["name"] for t in tools}
check(declared <= implemented, "toda herramienta de shared/tools.json está implementada", ", ".join(sorted(declared - implemented)))
check(implemented <= declared, "no hay manejadores sin declarar en tools.json", ", ".join(sorted(implemented - declared)))
missing_risk = [t["name"] for t in tools if t.get("risk") not in {"read", "local_write", "external", "sensitive"}]
check(not missing_risk, "toda herramienta declara su nivel de riesgo", ", ".join(missing_risk))
unconfirmed = [t["name"] for t in tools if t.get("risk") == "sensitive" and not t.get("confirm")]
check(not unconfirmed, "las herramientas sensibles piden confirmación (confirm: true)", ", ".join(unconfirmed))

# 3. Habilidades: manifiesto ↔ implementación ↔ registro
manifests = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted((ROOT / "shared/skills").glob("*.json"))}
impl_text = "\n".join(f.read_text(encoding="utf-8") for f in kotlin_files(CORE / "com/example/jarvis/core/skills/impl"))
impl_ids = set(re.findall(r'override val id = "([a-z_]+)"', impl_text))
impl_ids |= set(re.findall(r'Skill\("([a-z_]+)"', impl_text))  # habilidades parametrizadas (InventorySkill(scope))
check(set(manifests) <= impl_ids, "cada manifiesto tiene implementación", ", ".join(sorted(set(manifests) - impl_ids)))
for sid, m in manifests.items():
    problems = []
    if m.get("id") != sid:
        problems.append("id distinto del archivo")
    if len(m.get("exampleUtterances", [])) < 3:
        problems.append("menos de 3 ejemplos")
    if m.get("maturity") == "partial" and not m.get("missing"):
        problems.append("parcial sin lista 'missing'")
    if not m.get("testCases"):
        problems.append("sin testCases")
    unknown = [t for t in m.get("tools", []) if t not in declared]
    if unknown:
        problems.append("herramientas inexistentes: " + ", ".join(unknown))
    if problems:
        check(False, f"manifiesto {sid} completo", "; ".join(problems))
check(manifests, "hay manifiestos de habilidades", f"{len(manifests)} manifiestos")

# 4. Prácticas prohibidas en Kotlin
all_kt = kotlin_files(CORE) + kotlin_files(APP)
def grep(pattern):
    return [rel(f) for f in all_kt if re.search(pattern, f.read_text(encoding="utf-8"))]
check(not grep(r"\bGlobalScope\b"), "sin GlobalScope", ", ".join(grep(r"\bGlobalScope\b")))
check(not grep(r"fallbackToDestructiveMigration"), "sin fallbackToDestructiveMigration", ", ".join(grep(r"fallbackToDestructiveMigration")))
check(not grep(r"ACTION_CALL\b"), "llamar = ACTION_DIAL (nunca ACTION_CALL)", ", ".join(grep(r"ACTION_CALL\b")))
check(not grep(r"OPENAI_API_KEY|sk-[A-Za-z0-9_-]{20,}"), "ninguna clave de OpenAI en Kotlin", ", ".join(grep(r"OPENAI_API_KEY|sk-[A-Za-z0-9_-]{20,}")))
silent = grep(r"SmsManager|sendTextMessage")
check(not silent, "sin envío silencioso de SMS", ", ".join(silent))

# 5. Room: si cambió la versión hay migraciones
db = (APP / "com/example/jarvis/data/db/JarvisDatabase.kt")
if db.exists():
    t = db.read_text(encoding="utf-8")
    version = int(re.search(r"version = (\d+)", t).group(1))
    migrations = set(int(a) for a, _ in re.findall(r"Migration\((\d+), (\d+)\)", t))
    expected = set(range(1, version))
    check(expected <= migrations, f"Room v{version}: migraciones desde cada versión anterior", ", ".join(map(str, sorted(expected - migrations))))

# 6. Manifiesto Android: sin CAMERA declarado (la foto la toma la app del sistema), sin backup
manifest = (ROOT / "app/Android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
check("android.permission.CAMERA" not in manifest, "no se declara CAMERA (ACTION_IMAGE_CAPTURE)")
check('android:allowBackup="false"' in manifest, "allowBackup=false")

# 7. CLAUDE.md breve
lines = (ROOT / "CLAUDE.md").read_text(encoding="utf-8").count("\n")
check(lines < 200, "CLAUDE.md < 200 líneas", f"{lines} líneas")

failed = [r for r in results if not r[0]]
for ok, rule, detail in results:
    print(("PASA " if ok else "FALLA") + f"  {rule}" + (f"  [{detail}]" if detail and not ok else ""))
print(f"\n{len(results) - len(failed)}/{len(results)} reglas cumplidas")
sys.exit(1 if failed else 0)
