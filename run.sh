#!/bin/bash
# Launch the patched AMI JViewer KVM client on a modern OpenJDK.
#   ./run.sh <bmc-host> [username] [password]      standalone login; HTTPS port 443, or PORT=... ./run.sh
#   ./run.sh <jviewer.jnlp | https://.../x.jnlp>   fetch this BMC's own jars, patch them (cached) and launch
#   ./run.sh -- <raw JViewer args...>               run dist/ with raw arguments
# TLS_PROTOCOLS overrides the client TLS versions (default TLSv1, e.g. TLS_PROTOCOLS=TLSv1.2,TLSv1).
set -euo pipefail
D="$(cd "$(dirname "$0")" && pwd)"
JAVA="${JAVA:-java}"
TLS="${TLS_PROTOCOLS:-TLSv1}"
NET=(-Djava.security.properties="$D/legacy.java.security" -Dhttps.protocols="$TLS"
     -Djdk.tls.client.protocols="$TLS" -Djava.security.disableSystemPropertiesFile=true)
OPTS=("${NET[@]}" -Dsun.java2d.metal=true --add-opens java.desktop/java.awt=ALL-UNNAMED)

sha() { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi | cut -c1-16; }

if [[ "${1:-}" == *.jnlp || "${1:-}" =~ ^https?:// ]]; then
  CACHE="$D/cache"; JARDIR=""; MAIN=""; PROPS=(); ARGS=()
  while IFS= read -r -d '' r; do
    case "$r" in
      D:*) JARDIR="${r#D:}";; M:*) MAIN="${r#M:}";;
      P:*) PROPS+=("-D${r#P:}");; A:*) ARGS+=("${r#A:}");;
    esac
  done < <("$JAVA" "${NET[@]}" "$D/tools/Jnlp.java" "$1" "$CACHE/jars")
  [ -n "$JARDIR" ] || { echo "run: could not load $1" >&2; exit 1; }
  # rebuild only when the BMC's jars or the patches change
  KEY="$( (cat "$JARDIR"/*.jar "$D/build.sh" "$D/tools/"*.py; find "$D/patch-src" -name '*.java' -exec cat {} +) | sha)"
  OUT="$CACHE/build/$KEY"
  [ -f "$OUT/JViewer.jar" ] || "$D/build.sh" -o "$OUT" "$JARDIR" >&2
  [ -n "$MAIN" ] || MAIN="$(unzip -p "$OUT/JViewer.jar" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Main-Class: *//p')"
  exec "$JAVA" "${OPTS[@]}" ${PROPS[@]+"${PROPS[@]}"} -cp "$OUT/JViewer.jar" "$MAIN" ${ARGS[@]+"${ARGS[@]}"}
fi

[ -f "$D/dist/JViewer.jar" ] || "$D/build.sh"
if [ "${1:-}" = "--" ]; then shift; exec "$JAVA" "${OPTS[@]}" -jar "$D/dist/JViewer.jar" "$@"; fi
ARGS=(-apptype StandAlone -webport "${PORT:-443}")   # BMC HTTPS port; override with PORT=...
[ -n "${1:-}" ] && ARGS+=(-hostname "$1")
[ -n "${2:-}" ] && ARGS+=(-u "$2")
[ -n "${3:-}" ] && ARGS+=(-p "$3")
exec "$JAVA" "${OPTS[@]}" -jar "$D/dist/JViewer.jar" "${ARGS[@]}"
