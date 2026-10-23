#!/usr/bin/env bash
# Diagnóstico del entorno de compilación Android de J.A.R.V.I.S.
# Uso: .claude/skills/android-build/scripts/diagnose.sh [--run-core]
# Solo lee y prueba conectividad; no instala nada ni rodea bloqueos de red.
set -u
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
ANDROID_DIR="$ROOT/app/Android"
ok() { printf 'OK     %s\n' "$*"; }
warn() { printf 'AVISO  %s\n' "$*"; }
bad() { printf 'FALTA  %s\n' "$*"; BLOCKERS=$((BLOCKERS + 1)); }
BLOCKERS=0

# Java
if command -v java >/dev/null 2>&1; then
  JV=$(java -version 2>&1 | grep -v JAVA_TOOL_OPTIONS | head -1)
  ok "Java: $JV"
  java -version 2>&1 | grep -v JAVA_TOOL_OPTIONS | grep -Eq '"(17|21|2[2-9])' || warn "AGP 8.x requiere JDK 17 o superior"
else
  bad "Java no instalado (se necesita JDK 17+)"
fi

# Gradle wrapper
if [ -x "$ANDROID_DIR/gradlew" ]; then
  ok "Gradle wrapper: $(grep -o 'gradle-[0-9.]*' "$ANDROID_DIR/gradle/wrapper/gradle-wrapper.properties" | head -1)"
else
  bad "Falta app/Android/gradlew"
fi

# Android SDK
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$ANDROID_DIR/local.properties" ]; then
  SDK=$(grep '^sdk.dir=' "$ANDROID_DIR/local.properties" | cut -d= -f2-)
fi
if [ -n "$SDK" ] && [ -d "$SDK" ]; then
  ok "Android SDK: $SDK"
  ls "$SDK/platforms" 2>/dev/null | grep -q "android-" && ok "Plataformas: $(ls "$SDK/platforms" | tr '\n' ' ')" || bad "SDK sin plataformas (instala platforms;android-35 con sdkmanager)"
  [ -d "$SDK/build-tools" ] && ok "Build-tools: $(ls "$SDK/build-tools" | tr '\n' ' ')" || bad "SDK sin build-tools"
else
  bad "Android SDK no encontrado (ANDROID_HOME, ANDROID_SDK_ROOT o local.properties sdk.dir)"
fi

# Repositorios Maven (solo comprobación de alcance; no se usan espejos)
probe() {
  local name="$1" url="$2" code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -I "$url" 2>/dev/null)
  if [ "$code" = "200" ]; then ok "$name accesible"; else bad "$name NO accesible (HTTP ${code:-sin respuesta}): $url"; fi
}
probe "Google Maven (AGP, AndroidX, Compose)" "https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml"
probe "Maven Central" "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/maven-metadata.xml"
probe "Gradle Plugin Portal" "https://plugins.gradle.org/m2/org/jetbrains/kotlin/jvm/org.jetbrains.kotlin.jvm.gradle.plugin/maven-metadata.xml"

# Sherpa-ONNX (palabra clave)
if ls "$ANDROID_DIR/app/libs/"sherpa-onnx*.aar >/dev/null 2>&1; then ok "AAR de Sherpa-ONNX presente (variante sherpa)"; else warn "Sin AAR de Sherpa-ONNX: la palabra clave se compila como 'no disponible' (scripts/fetch-sherpa-kws.sh)"; fi

echo
if [ "$BLOCKERS" -eq 0 ]; then
  echo "Listo para: cd app/Android && ./gradlew :app:testDebugUnitTest :app:assembleDebug"
else
  echo "$BLOCKERS bloqueo(s). Sin Google Maven o SDK solo se puede ejecutar:"
  echo "  cd app/Android && ./gradlew -Pjarvis.coreOnly=true :core:test"
fi

if [ "${1:-}" = "--run-core" ]; then
  echo; echo "== Pruebas de :core =="
  (cd "$ANDROID_DIR" && ./gradlew -Pjarvis.coreOnly=true :core:test -q) && ok ":core:test en verde" || { bad ":core:test falló"; }
fi
exit $(( BLOCKERS > 0 ? 1 : 0 ))
