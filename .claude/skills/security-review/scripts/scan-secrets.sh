#!/usr/bin/env bash
# Revisión de seguridad de J.A.R.V.I.S.: secretos en el repositorio y en la KB, .gitignore,
# configuración Android y backend. Solo lee. Nunca imprime el valor de un secreto.
# Uso: .claude/skills/security-review/scripts/scan-secrets.sh [--staged]
#   --staged  revisa solo lo que está en el área de preparación (antes de un commit)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
KB="$(cd "$ROOT/.." && pwd)/JARVIS-KB"
FINDINGS=0
pass() { printf 'PASA   %s\n' "$*"; }
fail() { printf 'FALLA  %s\n' "$*"; FINDINGS=$((FINDINGS + 1)); }

PATTERN='(sk-(proj-)?[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{30,}|gh[pousr]_[A-Za-z0-9]{30,}|xox[abprs]-[A-Za-z0-9-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|AKIA[0-9A-Z]{16})'
# Valores de prueba conocidos y documentados (claves falsas de las suites)
ALLOW='sk-test-SECRET-should-never-leak|sk-integration-PERMANENT-KEY|sk-proj-ABCDEFGHIJKLMNOPQRSTUVWX123456|sk-test-1234567890abcdefghijklmnop|sk-invalid-key-for-test'

cd "$ROOT"
if [ "${1:-}" = "--staged" ]; then
  FILES=$(git diff --cached --name-only --diff-filter=ACM)
else
  FILES=$( { git ls-files; git ls-files --others --exclude-standard; } 2>/dev/null | sort -u)
fi

# 1. Patrones de secretos en archivos versionables
HITS=""
while IFS= read -r f; do
  [ -f "$f" ] || continue
  case "$f" in *.png|*.jpg|*.webp|*.ttf|*.otf|*.jar|*.aar|*.onnx) continue ;; esac
  m=$(grep -nIE "$PATTERN" "$f" 2>/dev/null | grep -vE "$ALLOW" | cut -d: -f1 | tr '\n' ',' )
  [ -n "$m" ] && HITS="$HITS\n  $f (líneas ${m%,})"
done <<<"$FILES"
[ -z "$HITS" ] && pass "sin claves con formato conocido en el repositorio" || fail "posibles claves en:$(printf "$HITS")"

# 2. Variables secretas con valor en archivos que no son .env
VARS=$(printf '%s\n' "$FILES" | grep -vE '(^|/)\.env' | while IFS= read -r f; do
  [ -f "$f" ] && grep -nIE '^(OPENAI_API_KEY|TAVILY_API_KEY|BRAVE_API_KEY|JARVIS_CLIENT_TOKEN)=[^[:space:]]+' "$f" /dev/null 2>/dev/null | cut -d: -f1,2
done)
[ -z "$VARS" ] && pass "ninguna variable secreta con valor fuera de .env" || fail "variables secretas con valor: $VARS"

# 3. .env y similares fuera del control de versiones
for p in .env backend/.env app/Android/local.properties release.keystore; do
  if git ls-files --error-unmatch "$p" >/dev/null 2>&1; then fail "$p está versionado"; fi
done
for rule in '.env' '*.keystore' '*.jks' 'local.properties'; do
  grep -qxF "$rule" .gitignore && pass ".gitignore excluye $rule" || fail ".gitignore no excluye $rule"
done
grep -nE '^[A-Z_]*(KEY|TOKEN|SECRET)=[^[:space:]]+' backend/.env.example >/dev/null 2>&1 && fail "backend/.env.example contiene valores secretos" || pass ".env.example sin valores secretos"

# 4. Android: sin claves en Gradle/recursos, sin backup, HTTPS en release
# Solo lo que entra en el APK (src/main) y la configuración de Gradle; las pruebas usan claves falsas.
ANDROID_HITS=$(grep -rnIE "(OPENAI_API_KEY|api\.openai\.com|$PATTERN)" app/Android/app/src/main app/Android/core/src/main app/Android/*.kts app/Android/*/build.gradle.kts app/Android/gradle 2>/dev/null | grep -vE "$ALLOW" | cut -d: -f1 | sort -u)
[ -z "$ANDROID_HITS" ] && pass "la app no contiene claves ni llama a OpenAI directamente" || fail "referencias a OpenAI/claves en la app: $ANDROID_HITS"
M=app/Android/app/src/main/AndroidManifest.xml
grep -q 'android:allowBackup="false"' $M && pass "allowBackup=false" || fail "allowBackup no está en false"
grep -q 'cleartextTrafficPermitted="true"' app/Android/app/src/main/res/xml/network_security_config.xml 2>/dev/null && fail "network_security_config permite HTTP en claro (debe ser solo debug)" || pass "release sin HTTP en claro"
grep -q 'android.permission.SEND_SMS\|android.permission.CALL_PHONE' $M && fail "permisos de envío/llamada directa declarados" || pass "sin permisos para enviar SMS o llamar directamente"

# 5. Backend: logger redacta y rutas protegidas
grep -q "authorization" backend/src/logger.js && pass "el logger redacta cabeceras de autorización" || fail "revisar redacción del logger"
grep -q "requireClientToken" backend/src/app.js && pass "/api/* exige el token del cliente" || fail "/api sin requireClientToken"

# 6. KB de Obsidian sin secretos
if [ -d "$KB" ]; then
  KBH=$(grep -rnIE "$PATTERN" "$KB" 2>/dev/null | grep -vE "$ALLOW" | cut -d: -f1,2)
  [ -z "$KBH" ] && pass "JARVIS-KB sin secretos" || fail "posibles secretos en la KB: $KBH"
fi

echo
[ "$FINDINGS" -eq 0 ] && echo "Sin hallazgos." || echo "$FINDINGS hallazgo(s). No hacer commit hasta resolverlos."
exit $(( FINDINGS > 0 ? 1 : 0 ))
