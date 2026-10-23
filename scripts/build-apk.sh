#!/usr/bin/env bash
# Compila el APK debug de JARVIS (y antes ejecuta las pruebas de la app).
# Requisitos: JDK 17+, Android SDK (plataforma 35) y acceso a Google Maven + Maven Central.
# Uso: scripts/build-apk.sh [--skip-tests]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/app/Android"
SKIP_TESTS=0
[ "${1:-}" = "--skip-tests" ] && SKIP_TESTS=1

command -v java >/dev/null 2>&1 || { echo "Falta un JDK 17 o superior." >&2; exit 1; }

# Android SDK: local.properties o variables de entorno
if ! grep -qs '^sdk.dir=' "$ANDROID_DIR/local.properties"; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$SDK" ]; then
    echo "No encuentro el Android SDK. Instala Android Studio o define ANDROID_HOME," >&2
    echo "o crea $ANDROID_DIR/local.properties con: sdk.dir=/ruta/al/Android/Sdk" >&2
    exit 1
  fi
  echo "sdk.dir=$SDK" > "$ANDROID_DIR/local.properties"
  echo "Creado local.properties con sdk.dir=$SDK"
fi

if ls "$ANDROID_DIR/app/libs/"sherpa-onnx*.aar >/dev/null 2>&1; then
  echo "Palabra clave local: Sherpa-ONNX INCLUIDO"
else
  echo "Palabra clave local: no incluida (opcional: scripts/fetch-sherpa-kws.sh)"
fi

cd "$ANDROID_DIR"
TASKS=(:app:assembleDebug)
[ $SKIP_TESTS -eq 0 ] && TASKS=(:core:test :app:testDebugUnitTest :app:assembleDebug)
./gradlew "${TASKS[@]}" --console=plain

APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  echo
  echo "APK generado: $APK"
  echo "Tamaño: $(du -h "$APK" | cut -f1)"
  command -v sha256sum >/dev/null 2>&1 && echo "SHA-256: $(sha256sum "$APK" | cut -d' ' -f1)"
  echo "Instalar: adb install -r \"$APK\""
else
  echo "Gradle terminó pero no se encontró el APK en $APK" >&2
  exit 1
fi
