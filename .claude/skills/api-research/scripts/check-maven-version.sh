#!/usr/bin/env bash
# Comprueba versiones publicadas de dependencias en su repositorio OFICIAL (sin espejos).
#
#   check-maven-version.sh group:artifact[:versión] ...   Maven Central o Google Maven
#   check-maven-version.sh npm:paquete[@versión] ...       registro npm
#   check-maven-version.sh --catalog                       todas las del catálogo gradle/libs.versions.toml
#
# Para cada una: última versión publicada y si la versión pedida existe. Si el repositorio no es
# accesible lo dice (NO VERIFICABLE) en lugar de suponer.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
STATUS=0

repo_for() {
  case "$1" in
    androidx.*|com.android.*|com.google.android.*|com.google.firebase*) echo "https://dl.google.com/android/maven2" ;;
    *) echo "https://repo.maven.apache.org/maven2" ;;
  esac
}

check_maven() {
  local coord="$1" group artifact version base url meta latest
  IFS=: read -r group artifact version <<<"$coord"
  base=$(repo_for "$group")
  url="$base/${group//.//}/$artifact/maven-metadata.xml"
  local code
  meta=$(curl -s --max-time 15 -w '\n%{http_code}' "$url"); code="${meta##*$'\n'}"; meta="${meta%$'\n'*}"
  case "$code" in
    200) ;;
    404) printf '%-55s NO PUBLICADO en %s (revisa grupo/artefacto)\n' "$group:$artifact" "${base#https://}"; STATUS=1; return ;;
    *) printf '%-55s NO VERIFICABLE (sin acceso a %s, HTTP %s)\n' "$group:$artifact" "${base#https://}" "${code:-000}"; STATUS=1; return ;;
  esac
  latest=$(printf '%s' "$meta" | sed -n 's:.*<release>\(.*\)</release>.*:\1:p' | head -1)
  [ -n "$latest" ] || latest=$(printf '%s' "$meta" | sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p' | head -1)
  if [ -n "${version:-}" ]; then
    if printf '%s' "$meta" | grep -q "<version>$version</version>"; then
      printf '%-55s %-14s existe   (última: %s)\n' "$group:$artifact" "$version" "$latest"
    else
      printf '%-55s %-14s NO EXISTE (última: %s)\n' "$group:$artifact" "$version" "$latest"; STATUS=1
    fi
  else
    printf '%-55s última: %s\n' "$group:$artifact" "$latest"
  fi
}

check_npm() {
  local spec="${1#npm:}" name version latest
  name="${spec%@*}"; version=""; [ "$spec" != "$name" ] && version="${spec##*@}"
  [ "${spec:0:1}" = "@" ] && { name="@${spec:1}"; name="${name%@*}"; version=""; [[ "${spec:1}" == *@* ]] && version="${spec##*@}"; }
  latest=$(npm view "$name" version 2>/dev/null) || { printf '%-55s NO VERIFICABLE (registro npm inaccesible)\n' "npm:$name"; STATUS=1; return; }
  if [ -n "$version" ]; then
    if npm view "$name@$version" version >/dev/null 2>&1 && [ -n "$(npm view "$name@$version" version 2>/dev/null)" ]; then
      printf '%-55s %-14s existe   (última: %s)\n' "npm:$name" "$version" "$latest"
    else
      printf '%-55s %-14s NO EXISTE (última: %s)\n' "npm:$name" "$version" "$latest"; STATUS=1
    fi
  else
    printf '%-55s última: %s\n' "npm:$name" "$latest"
  fi
}

catalog() {
  python3 -I - "$ROOT/app/Android/gradle/libs.versions.toml" <<'PY'
import re, sys, tomllib
data = tomllib.load(open(sys.argv[1], "rb"))
versions = data.get("versions", {})
for lib in data.get("libraries", {}).values():
    if isinstance(lib, str):
        print(lib); continue
    module = lib.get("module") or f'{lib.get("group")}:{lib.get("name")}'
    ref = lib.get("version", {})
    v = versions.get(ref.get("ref")) if isinstance(ref, dict) else ref
    print(f"{module}:{v}" if v else module)
PY
}

[ $# -gt 0 ] || { sed -n '2,10p' "$0"; exit 2; }
if [ "$1" = "--catalog" ]; then
  while read -r c; do check_maven "$c"; done < <(catalog)
else
  for a in "$@"; do case "$a" in npm:*) check_npm "$a" ;; *) check_maven "$a" ;; esac; done
fi
exit $STATUS
