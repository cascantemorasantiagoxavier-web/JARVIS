#!/usr/bin/env bash
# Pruebas de humo de la voz GPT Realtime de J.A.R.V.I.S.
#
#   smoke.sh offline              Pruebas automáticas sin red (backend + núcleo Kotlin)
#   smoke.sh live                 Contra un backend en marcha: salud, autenticación, límites
#   smoke.sh live --create-secret Además pide un client secret efímero a OpenAI (cuenta 1 sesión
#                                 en los límites del servidor; no abre audio ni genera costo de voz)
#
# Variables para "live": JARVIS_URL (p. ej. http://127.0.0.1:8787) y JARVIS_CLIENT_TOKEN.
# El token y el secreto nunca se imprimen.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
MODE="${1:-offline}"
FAIL=0
pass() { printf 'PASA   %s\n' "$*"; }
fail() { printf 'FALLA  %s\n' "$*"; FAIL=1; }
info() { printf 'INFO   %s\n' "$*"; }

json_get() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const v=process.argv[1].split(".").reduce((o,k)=>o==null?o:o[k],JSON.parse(s));console.log(v===undefined?"":typeof v==="object"?JSON.stringify(v):String(v))}catch{console.log("")}})' "$1"; }

if [ "$MODE" = "offline" ]; then
  (cd "$ROOT/backend" && node --test test/realtime.test.js >/dev/null 2>&1) && pass "backend: sesiones, llamadas y colgado (test/realtime.test.js)" || fail "backend realtime.test.js"
  (cd "$ROOT/app/Android" && ./gradlew -Pjarvis.coreOnly=true :core:test -q --tests '*RealtimeTest*' --tests '*BackendIntegrationTest*' >/dev/null 2>&1) \
    && pass "núcleo: controlador Realtime, herramientas por voz e integración con node (RealtimeTest, BackendIntegrationTest)" \
    || fail "núcleo RealtimeTest/BackendIntegrationTest"
  info "Lo que estas pruebas NO cubren: audio real, WebRTC, AEC, permisos y servicio en primer plano (EMULADOR/DISPOSITIVO)."
  exit $FAIL
fi

[ "$MODE" = "live" ] || { echo "Modo desconocido: $MODE (offline | live)"; exit 2; }
: "${JARVIS_URL:?Define JARVIS_URL}"
: "${JARVIS_CLIENT_TOKEN:?Define JARVIS_CLIENT_TOKEN (no lo pegues en el chat)}"
URL="${JARVIS_URL%/}"

HEALTH=$(curl -s --max-time 10 "$URL/health") || HEALTH=""
[ "$(printf '%s' "$HEALTH" | json_get status)" = "ok" ] && pass "/health responde" || { fail "/health no responde en $URL"; exit 1; }
RT=$(printf '%s' "$HEALTH" | json_get features.realtime)
MODEL=$(printf '%s' "$HEALTH" | json_get features.realtimeModel)
if [ "$RT" = "true" ]; then pass "Realtime habilitado (modelo $MODEL)"; else info "Realtime NO configurado en el servidor (falta OPENAI_API_KEY en backend/.env)"; fi

CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$URL/api/realtime/session" -H 'content-type: application/json' -d '{}')
[ "$CODE" = "401" ] && pass "sin token → 401" || fail "sin token devolvió $CODE (esperado 401)"

USAGE=$(curl -s --max-time 10 "$URL/api/usage" -H "authorization: Bearer $JARVIS_CLIENT_TOKEN")
TODAY=$(printf '%s' "$USAGE" | json_get realtime.today)
[ -n "$TODAY" ] && pass "token aceptado; sesiones hoy: $TODAY de $(printf '%s' "$USAGE" | json_get realtime.maxPerDay)" || fail "/api/usage rechazó el token o no respondió"

if [ "${2:-}" = "--create-secret" ]; then
  if [ "$RT" != "true" ]; then info "Se omite --create-secret: Realtime no está configurado"; else
    R=$(curl -s --max-time 20 -X POST "$URL/api/realtime/session" -H 'content-type: application/json' -H "authorization: Bearer $JARVIS_CLIENT_TOKEN" -d '{"voice":"cedar"}')
    SECRET=$(printf '%s' "$R" | json_get clientSecret)
    if [ -n "$SECRET" ]; then pass "client secret efímero emitido (${SECRET:0:3}…, expira $(printf '%s' "$R" | json_get expiresAt))"; else fail "no se emitió el secreto: $(printf '%s' "$R" | json_get error.message)"; fi
  fi
fi
info "La conversación de voz real solo se prueba en el teléfono: ver el checklist de SKILL.md."
exit $FAIL
