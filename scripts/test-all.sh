#!/usr/bin/env bash
# Ejecuta todas las pruebas que este equipo puede correr y dice claramente cuáles omitió.
# Uso: scripts/test-all.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
declare -a SUMMARY=()
FAILED=0

record() { SUMMARY+=("$1"); }

echo "==> Backend (node --test, sin red)"
if command -v node >/dev/null 2>&1; then
  (
    cd "$ROOT/backend" || exit 1
    [ -d node_modules ] || npm ci --no-audit --no-fund
    npm test
  ) && record "Backend ............ AUTOMATIZADO: OK" || { record "Backend ............ AUTOMATIZADO: FALLÓ"; FAILED=1; }
else
  record "Backend ............ NO TESTEADO: falta Node.js 22.9+"
fi

echo "==> Núcleo Kotlin (:core, sin Android SDK)"
if command -v java >/dev/null 2>&1; then
  (cd "$ROOT/app/Android" && ./gradlew -Pjarvis.coreOnly=true :core:test --console=plain) \
    && record "Núcleo :core ....... AUTOMATIZADO: OK" || { record "Núcleo :core ....... AUTOMATIZADO: FALLÓ"; FAILED=1; }
else
  record "Núcleo :core ....... NO TESTEADO: falta un JDK 17+"
fi

echo "==> App Android (:app, Robolectric)"
has_sdk() {
  [ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] || grep -qs '^sdk.dir=' "$ROOT/app/Android/local.properties"
}
google_maven_ok() {
  curl -s -o /dev/null -m 15 -w '%{http_code}' \
    https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml | grep -q '^200$'
}
if ! has_sdk; then
  record "App :app ........... NO TESTEADO: no hay Android SDK (ANDROID_HOME o local.properties)"
elif ! google_maven_ok; then
  record "App :app ........... NO TESTEADO: Google Maven (dl.google.com) no es accesible"
else
  (cd "$ROOT/app/Android" && ./gradlew :app:testDebugUnitTest --console=plain) \
    && record "App :app ........... AUTOMATIZADO (Robolectric): OK" || { record "App :app ........... AUTOMATIZADO (Robolectric): FALLÓ"; FAILED=1; }
fi

record "Pruebas en vivo .... NO TESTEADO aquí: ejecuta 'cd backend && npm run test:live' con tu .env"
record "Emulador/teléfono .. NO TESTEADO: requieren ejecución manual (ver tests/README.md)"

echo
echo "================ RESUMEN ================"
printf '%s\n' "${SUMMARY[@]}"
exit $FAILED
