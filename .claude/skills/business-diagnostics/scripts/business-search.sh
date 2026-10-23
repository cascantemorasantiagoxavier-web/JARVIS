#!/usr/bin/env bash
# Diagnóstico de la búsqueda de negocios de J.A.R.V.I.S.
#   business-search.sh "<categoría>" "<ubicación>"   consulta el backend (JARVIS_URL, JARVIS_CLIENT_TOKEN)
#   business-search.sh --file respuesta.json          analiza una respuesta guardada
#   business-search.sh --offline                      pruebas automáticas (sin red)
# Respeta las políticas de OSM: una consulta por ejecución, sin bucles.
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../../../.." && pwd)"
case "${1:-}" in
  --offline)
    (cd "$ROOT/backend" && node --test test/business.test.js) >/dev/null 2>&1 && echo "PASA   backend/test/business.test.js" || { echo "FALLA  backend/test/business.test.js"; exit 1; }
    exit 0 ;;
  --file)
    node "$DIR/analyze.mjs" < "${2:?Falta el archivo}"; exit $? ;;
  "") sed -n '2,6p' "$0"; exit 2 ;;
esac
: "${JARVIS_URL:?Define JARVIS_URL}"; : "${JARVIS_CLIENT_TOKEN:?Define JARVIS_CLIENT_TOKEN}"
BODY=$(node -e 'console.log(JSON.stringify({category:process.argv[1],location:process.argv[2],limit:20}))' "$1" "${2:?Falta la ubicación}")
OUT="${TMPDIR:-/tmp}/jarvis-business-$$.json"
CODE=$(curl -s -o "$OUT" -w '%{http_code}' --max-time 60 -X POST "${JARVIS_URL%/}/api/business/search" \
  -H 'content-type: application/json' -H "authorization: Bearer $JARVIS_CLIENT_TOKEN" -d "$BODY")
echo "HTTP $CODE"
node "$DIR/analyze.mjs" < "$OUT"; RC=$?
echo "(respuesta completa en $OUT)"
exit $RC
