---
name: session-continuity
description: Mantiene la continuidad entre sesiones de trabajo en J.A.R.V.I.S. y la documentación al día (CONTINUIDAD, estado, bitácora, resultados de pruebas, ADR, CLAUDE.md). Úsala al empezar una sesión (para saber dónde quedó todo) y siempre antes de terminarla o cuando el usuario se va.
---

# Continuidad entre sesiones y documentación

## Al empezar
1. Leer `../JARVIS-KB/06-TASKS/CONTINUIDAD.md` (dónde quedó, próxima tarea, qué espera del usuario).
2. Leer solo lo que la tarea necesite desde `../JARVIS-KB/00-INDEX.md`. No cargar toda la KB.
3. `git log --oneline | head` y `git status` para ver el último trabajo real.

## Al terminar (o si el usuario se va)
1. Pruebas que el entorno permita (`scripts/test-all.sh`) y registrar el resultado con su clasificación.
2. Actualizar la KB:
   - `06-TASKS/CONTINUIDAD.md`: dónde quedó, próxima tarea concreta (comandos), riesgos, qué falta del usuario.
   - `06-TASKS/estado.md`: tabla de estado.
   - `09-DAILY-LOG/AAAA-MM-DD.md`: qué se hizo, qué falló y por qué, decisiones.
   - `08-TESTS/AAAA-MM-DD-resultados.md`: comando, número de pruebas y etiqueta (AUTOMATIZADO / EMULADOR /
     DISPOSITIVO FÍSICO / NO TESTEADO). Datos simulados = lógica verificada, no el servicio real.
   - Decisión nueva → `05-DECISIONS/ADR-NNN-*.md` + fila en `registro-de-decisiones.md`.
   - Preferencia nueva del usuario → `04-PREFERENCES/preferencias-del-usuario.md`.
3. Si cambió algo estructural, actualizar `CLAUDE.md` (máx. 200 líneas; enlaces en vez de detalle).
4. Comprobar:
   ```bash
   python3 -I .claude/skills/session-continuity/scripts/check-kb.py          # código 1 = falta algo
   python3 -I .claude/skills/obsidian-integration/scripts/vault_lint.py ../JARVIS-KB --strict
   .claude/skills/security-review/scripts/scan-secrets.sh
   ```
5. Commit pequeño y descriptivo en español.

## Informe final al usuario
En español, breve: qué quedó funcionando (con su clasificación), qué no y por qué, qué necesita de él.
Nunca afirmar que algo se desplegó, instaló o probó, ni que existe un APK, sin haberlo verificado.
