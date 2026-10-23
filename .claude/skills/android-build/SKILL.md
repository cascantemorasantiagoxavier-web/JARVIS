---
name: android-build
description: Compila y diagnostica la app Android de J.A.R.V.I.S. (Gradle, AGP, SDK, Google Maven, Room/KSP, Sherpa-ONNX). Úsala al compilar, generar el APK, cuando falle Gradle o para saber qué se puede verificar en la máquina actual.
---

# Compilación y diagnóstico Android

## 1. Diagnóstico del entorno (siempre primero)
```bash
.claude/skills/android-build/scripts/diagnose.sh            # Java, wrapper, SDK, Google Maven, Maven Central, Sherpa
.claude/skills/android-build/scripts/diagnose.sh --run-core # además ejecuta las pruebas de :core
```
Código 1 = hay bloqueos; el script dice cuáles y qué sí se puede ejecutar.

## 2. Qué ejecutar según el resultado
| Situación | Comando | Qué verifica |
|---|---|---|
| Sin Google Maven o sin SDK | `cd app/Android && ./gradlew -Pjarvis.coreOnly=true :core:test` | cerebro, herramientas, habilidades, Realtime (sin Android) |
| Todo accesible | `./gradlew :app:testDebugUnitTest` | Room, navegación, UI con Robolectric |
| Todo accesible | `./gradlew :app:assembleDebug` o `scripts/build-apk.sh` | APK de depuración |

## 3. Errores frecuentes
- **`Could not resolve com.android.tools.build:gradle`** → Google Maven bloqueado. No usar espejos no aprobados
  (`.claude/rules/security.md`). Informar el bloqueo y verificar solo `:core`.
- **KSP/Room `Migration didn't properly handle`** → el SQL de `JarvisDatabase.MIGRATION_*` debe ser idéntico al
  que genera Room (ver `app/schemas/<versión>.json` tras compilar). Nunca `fallbackToDestructiveMigration`.
- **Versiones de AndroidX/Compose** sin verificar: comprobarlas con la skill `api-research` y anotarlas en la KB.
- **Sherpa-ONNX**: el source set `sherpa` solo se usa si existe `app/libs/sherpa-onnx*.aar`
  (`scripts/fetch-sherpa-kws.sh`). Sin él, la palabra clave aparece como "no disponible".

## 4. Reglas
- No declarar que existe un APK sin haber visto el archivo (`ls -la app/Android/app/build/outputs/apk/debug/`).
- Registrar el resultado en `../JARVIS-KB/08-TESTS/` con la etiqueta correcta (AUTOMATIZADO / EMULADOR /
  DISPOSITIVO FÍSICO / NO TESTEADO).
- La verificación de tipos offline del módulo `:app` (arnés con stubs) no equivale a compilar con AGP: decirlo.

Detalle: `.claude/rules/android.md`, `../JARVIS-KB/07-RESEARCH/entorno-de-desarrollo.md`, `../JARVIS-KB/05-DECISIONS/ADR-004-red-bloqueada.md`.
