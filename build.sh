#!/bin/bash
# Build a patched JViewer from the original (signed, firmware) jars. run.sh does this for you from a JNLP.
#   ./build.sh [-o OUT_DIR] JAR|DIR ...        OUT_DIR defaults to dist
# Every jar containing classes is merged (first one wins on duplicates) into OUT_DIR/JViewer.jar;
# jars without classes (the per-OS native libraries) are ignored. JVIEWER_DEBUG=1 enables console logging.
set -euo pipefail
D="$(cd "$(dirname "$0")" && pwd)"
OUT=dist
if [ "${1:-}" = "-o" ]; then OUT="$2"; shift 2; fi
[ $# -gt 0 ] || { echo "usage: $0 [-o OUT_DIR] JAR|DIR ...   (JViewer.jar, JViewer-SOC.jar, ... from the BMC)" >&2; exit 2; }
JARS=()
for a in "$@"; do
  if [ -d "$a" ]; then
    # main jar first so its manifest and classes take precedence
    for j in "$a"/JViewer.jar "$a"/*.jar; do [ -f "$j" ] && JARS+=("$(cd "$(dirname "$j")" && pwd)/$(basename "$j")"); done
  else JARS+=("$(cd "$(dirname "$a")" && pwd)/$(basename "$a")"); fi
done
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
B="$(mktemp -d "${TMPDIR:-/tmp}/jviewer-build.XXXXXX")"; trap 'rm -rf "$B"' EXIT
rm -rf "$OUT/Jar" "$OUT/JViewer.jar" "$OUT/JViewer-SOC.jar"   # Jar/ is JViewer's native-lib cache
mkdir -p "$B/classes"
MAIN=""; seen=" "
for j in "${JARS[@]}"; do
  case "$seen" in *" $j "*) continue;; esac; seen+="$j "
  case "$(unzip -Z1 "$j")" in *.class*) ;; *) continue;; esac   # skip native-library jars
  echo "build: + $(basename "$j")"
  (cd "$B/classes" && unzip -qn "$j")
  [ -n "$MAIN" ] || MAIN="$(unzip -p "$j" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Main-Class: *//p')"
done
[ -n "$(find "$B/classes" -name '*.class' | head -1)" ] || { echo "build: no classes found in: ${JARS[*]}" >&2; exit 1; }
MAIN="${MAIN:-com.ami.kvm.jviewer.JViewer}"
# Strip the (now invalid) code signatures
rm -rf "$B/classes/META-INF"; mkdir "$B/classes/META-INF"
printf 'Manifest-Version: 1.0\nMain-Class: %s\n' "$MAIN" > "$B/manifest"

# Pure-Java vmedia (ImageDisk + VMediaNatives) compiled in, then bytecode patches (see tools/patch.py)
javac --release 8 -nowarn -encoding UTF-8 -d "$B/classes" $(find "$D/patch-src" -name '*.java')
python3 -I "$D/tools/patch.py" "$B/classes" $([ "${JVIEWER_DEBUG:-0}" = 1 ] && echo --debug)

(cd "$B/classes" && jar --create --file "$OUT/JViewer.jar" --manifest "$B/manifest" $(ls | grep -v META-INF))
echo "Built $OUT/JViewer.jar"
