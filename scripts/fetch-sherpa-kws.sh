#!/usr/bin/env bash
# Descarga Sherpa-ONNX (Apache-2.0) y un modelo de detección de palabras clave para "JARVIS".
# Deja:
#   app/Android/app/libs/sherpa-onnx-<versión>.aar
#   app/Android/app/src/main/assets/kws/{encoder,decoder,joiner}.onnx, tokens.txt, keywords.txt
# Luego compila con scripts/build-apk.sh: Gradle detecta el AAR y usa la variante "sherpa".
#
# Variables opcionales:
#   SHERPA_VERSION=1.12.x   versión concreta (por defecto: la última release de GitHub)
#   KWS_MODEL=...           nombre del archivo de modelo (por defecto el de GigaSpeech, inglés)
#   KWS_INT8=1              usa los modelos cuantizados int8 (más pequeños, algo menos precisos)
#
# NO VERIFICADO: este script se escribió en un entorno sin acceso a github.com. Si un nombre de
# archivo cambió, descarga a mano desde https://github.com/k2-fsa/sherpa-onnx/releases
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/app/Android/app"
REPO="k2-fsa/sherpa-onnx"
KWS_MODEL="${KWS_MODEL:-sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for bin in curl tar python3 unzip; do
  command -v "$bin" >/dev/null 2>&1 || { echo "Falta '$bin'." >&2; exit 1; }
done

# 1) AAR de Android
if [ -z "${SHERPA_VERSION:-}" ]; then
  echo "Buscando la última versión de sherpa-onnx…"
  SHERPA_VERSION="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"].lstrip("v"))')"
fi
echo "Versión: $SHERPA_VERSION"

AAR_URL="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/tags/v$SHERPA_VERSION" | python3 -c '
import json, sys
ver = sys.argv[1]
assets = [a for a in json.load(sys.stdin).get("assets", []) if a["name"].endswith(".aar")]
exact = [a for a in assets if a["name"] == f"sherpa-onnx-{ver}.aar"]
pick = (exact or [a for a in assets if "static" not in a["name"]] or assets or [None])[0]
print(pick["browser_download_url"] if pick else "")
' "$SHERPA_VERSION")"
if [ -z "$AAR_URL" ]; then
  echo "La release v$SHERPA_VERSION no tiene un .aar. Descárgalo a mano y ponlo en $APP/libs/" >&2
  exit 1
fi
echo "Descargando $AAR_URL"
curl -fL --retry 3 -o "$WORK/sherpa.aar" "$AAR_URL"

if ! unzip -p "$WORK/sherpa.aar" classes.jar > "$WORK/classes.jar" 2>/dev/null \
   || ! unzip -l "$WORK/classes.jar" | grep -q 'com/k2fsa/sherpa/onnx/KeywordSpotter'; then
  echo "AVISO: el AAR no contiene com.k2fsa.sherpa.onnx.KeywordSpotter; la variante sherpa podría no compilar." >&2
fi
rm -f "$APP"/libs/sherpa-onnx*.aar
cp "$WORK/sherpa.aar" "$APP/libs/sherpa-onnx-$SHERPA_VERSION.aar"

# 2) Modelo de palabras clave
MODEL_URL="https://github.com/$REPO/releases/download/kws-models/$KWS_MODEL.tar.bz2"
echo "Descargando $MODEL_URL"
curl -fL --retry 3 -o "$WORK/model.tar.bz2" "$MODEL_URL"
tar -xjf "$WORK/model.tar.bz2" -C "$WORK"
MDIR="$WORK/$KWS_MODEL"
[ -d "$MDIR" ] || MDIR="$(find "$WORK" -maxdepth 1 -type d -name 'sherpa-onnx-kws*' | head -1)"

pick() { # pick <parte>: elige el .onnx (int8 solo si KWS_INT8=1), prefiriendo chunk-16 (menor latencia)
  python3 -I - "$MDIR" "$1" "${KWS_INT8:-0}" <<'PY'
import os, sys
d, part, int8 = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
fs = sorted(f for f in os.listdir(d)
            if f.startswith(part + "-") and f.endswith(".onnx") and f.endswith(".int8.onnx") == int8)
print(([f for f in fs if "chunk-16" in f] or fs or [""])[0])
PY
}
OUT="$APP/src/main/assets/kws"
rm -rf "$OUT" && mkdir -p "$OUT"
for part in encoder decoder joiner; do
  f="$(pick "$part")"
  [ -n "$f" ] || { echo "No encontré $part*.onnx en $MDIR" >&2; exit 1; }
  cp "$MDIR/$f" "$OUT/$part.onnx"
  echo "  $part.onnx  ←  $f"
done
cp "$MDIR/tokens.txt" "$OUT/tokens.txt"

# 3) keywords.txt: "JARVIS" convertido a los tokens BPE del modelo
[ -f "$MDIR/bpe.model" ] || { echo "El modelo no incluye bpe.model; genera keywords.txt con sherpa-onnx-cli text2token." >&2; exit 1; }
python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install --quiet sentencepiece
"$WORK/venv/bin/python" -I - "$MDIR/bpe.model" "$OUT/tokens.txt" "$OUT/keywords.txt" <<'PY'
import sys
import sentencepiece as spm
bpe, tokens_path, out = sys.argv[1:4]
sp = spm.SentencePieceProcessor(model_file=bpe)
vocab = {line.split()[0] for line in open(tokens_path, encoding="utf-8") if line.strip()}
lines = []
for phrase in ["JARVIS", "HEY JARVIS", "OK JARVIS"]:
    pieces = sp.encode(phrase, out_type=str)
    missing = [p for p in pieces if p not in vocab]
    if missing:
        sys.exit(f"Tokens fuera del vocabulario para {phrase!r}: {missing}")
    lines.append(" ".join(pieces) + " @" + phrase.replace(" ", "_"))
open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("keywords.txt:\n  " + "\n  ".join(lines))
PY

echo
echo "Listo. Ahora: scripts/build-apk.sh"
echo "Licencias: sherpa-onnx es Apache-2.0; revisa la licencia del modelo en su página antes de distribuir el APK."
