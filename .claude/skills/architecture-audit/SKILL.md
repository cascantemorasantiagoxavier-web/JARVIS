---
name: architecture-audit
description: Audita que J.A.R.V.I.S. respete su arquitectura (core puro, contrato único de herramientas, manifiestos de habilidades, riesgos, Room, permisos). Úsala antes de un commit grande, al añadir herramientas o habilidades, o cuando el usuario pida revisar la arquitectura.
---

# Auditoría de arquitectura

## Cuándo
- Antes de cerrar un lote de cambios en `app/Android`, `shared/` o `backend/`.
- Al añadir o cambiar una herramienta (`shared/tools.json`) o una habilidad (`shared/skills/*.json`).
- Cuando algo "funciona en el chat pero no en la voz" (sospecha de dos caminos distintos).

## Pasos
1. Ejecuta la auditoría estática (no modifica nada):
   ```bash
   python3 -I .claude/skills/architecture-audit/scripts/audit.py
   ```
   Sale con código 1 si alguna regla falla y dice el archivo.
2. Ejecuta las pruebas que verifican lo que el script no puede ver:
   ```bash
   cd app/Android && ./gradlew -Pjarvis.coreOnly=true :core:test   # incluye SkillContractTest y verifyComplete
   cd backend && npm test
   ```
3. Revisa a mano lo que no es automatizable:
   - Un solo camino: chat, voz Realtime, botones y rutinas llaman a `ToolExecutor` (busca `executor.execute`; no debe haber atajos que ejecuten herramientas fuera de él).
   - El mapa solo se abre por `show_on_map` o un botón (`UiEvent.ShowMap`).
   - Las habilidades solo usan herramientas declaradas en su manifiesto (`SkillContext.tool` lo impone en tiempo de ejecución).
4. Informa cada hallazgo con archivo y línea. Clasifica la verificación según `.claude/rules/testing.md`.

## Qué comprueba el script
`:core` sin Android · cada herramienta declarada tiene manejador y viceversa · riesgo declarado ·
sensibles con `confirm` · cada manifiesto tiene implementación, ≥3 ejemplos, `testCases`, `missing` si es
parcial y herramientas existentes · sin `GlobalScope`, `fallbackToDestructiveMigration`, `ACTION_CALL`,
SMS silencioso ni claves en Kotlin · migraciones Room para cada versión · sin permiso CAMERA ·
`allowBackup=false` · `CLAUDE.md` < 200 líneas.

## Referencias (no copiar aquí)
- `../JARVIS-KB/02-ARCHITECTURE/arquitectura.md`
- `../JARVIS-KB/05-DECISIONS/` (ADR-001 módulos, ADR-005 cerebro único, ADR-013 habilidades)
- `docs/SKILLS.md`
