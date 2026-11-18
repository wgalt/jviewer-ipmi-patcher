#!/bin/bash
# Launch the patched AMI JViewer KVM client on a modern OpenJDK.
#   ./run.sh <jviewer.jnlp | https://.../x.jnlp>   fetch this BMC's own jars, patch them (cached) and launch
#   ./run.sh [bmc-host] [username] [password]      standalone login with the jars cached for that host by an
#                                                  earlier JNLP launch (no host: the last one); HTTPS port 443,
#                                                  or PORT=... ./run.sh
#   ./run.sh -- <raw JViewer args...>               raw arguments (jar picked by -hostname, else the last one)
# JVIEWER_JAR=path overrides the jar for standalone/raw launches (e.g. one made with ./build.sh).
# TLS_PROTOCOLS overrides the client TLS versions (default TLSv1, e.g. TLS_PROTOCOLS=TLSv1.2,TLSv1).
# JVIEWER_DEBUG=1 builds with console logging.
set -euo pipefail
D="$(cd "$(dirname "$0")" && pwd)"
CACHE="$D/cache"
JAVA="${JAVA:-java}"
TLS="${TLS_PROTOCOLS:-TLSv1}"
NET=(-Djava.security.properties="$D/legacy.java.security" -Dhttps.protocols="$TLS"
     -Djdk.tls.client.protocols="$TLS" -Djava.security.disableSystemPropertiesFile=true)
OPTS=("${NET[@]}" -Dsun.java2d.metal=true --add-opens java.desktop/java.awt=ALL-UNNAMED)

sha() { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi | cut -c1-16; }
hostkey() { printf '%s' "$1" | tr 'A-Z' 'a-z' | tr -c 'a-z0-9._-' '_'; }

if [[ "${1:-}" == *.jnlp || "${1:-}" =~ ^https?:// ]]; then
  JARDIR=""; HOST=""; MAIN=""; PROPS=(); ARGS=()
  while IFS= read -r -d '' r; do
    case "$r" in
      D:*) JARDIR="${r#D:}";; H:*) HOST="${r#H:}";; M:*) MAIN="${r#M:}";;
      P:*) PROPS+=("-D${r#P:}");; A:*) ARGS+=("${r#A:}");;
    esac
  done < <("$JAVA" "${NET[@]}" "$D/tools/Jnlp.java" "$1" "$CACHE/jars")
  [ -n "$JARDIR" ] || { echo "run: could not load $1" >&2; exit 1; }
  # rebuild only when the BMC's jars or the patches change
  KEY="$( (cat "$JARDIR"/*.jar "$D/build.sh" "$D/tools/"*.py; find "$D/patch-src" -name '*.java' -exec cat {} +
           echo "debug=${JVIEWER_DEBUG:-0}") | sha)"
  OUT="$CACHE/build/$KEY"
  [ -f "$OUT/JViewer.jar" ] || "$D/build.sh" -o "$OUT" "$JARDIR" >&2
  # remember it for standalone launches of this host
  mkdir -p "$CACHE/hosts"; echo "$KEY" > "$CACHE/hosts/$(hostkey "$HOST")"; echo "$KEY" > "$CACHE/hosts/.last"
  [ -n "$MAIN" ] || MAIN="$(unzip -p "$OUT/JViewer.jar" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Main-Class: *//p')"
  exec "$JAVA" "${OPTS[@]}" ${PROPS[@]+"${PROPS[@]}"} -cp "$OUT/JViewer.jar" "$MAIN" ${ARGS[@]+"${ARGS[@]}"}
fi

# Standalone / raw: find the patched jar for the host
jar_for() {
  local f="$CACHE/hosts/.last" k
  [ -n "$1" ] && f="$CACHE/hosts/$(hostkey "$1")"
  if [ -n "${JVIEWER_JAR:-}" ]; then echo "$JVIEWER_JAR"; return; fi
  if [ -f "$f" ] && k="$(cat "$f")" && [ -f "$CACHE/build/$k/JViewer.jar" ]; then echo "$CACHE/build/$k/JViewer.jar"; return; fi
  {
    echo "run: no patched JViewer cached for ${1:-any host} yet."
    echo "     Open the remote console once from the BMC web UI, save the jviewer.jnlp it offers and run"
    echo "     ./run.sh path/to/jviewer.jnlp ; later standalone launches of that host reuse its jars."
    [ -n "$1" ] && [ -d "$CACHE/hosts" ] && [ -n "$(ls "$CACHE/hosts")" ] && echo "     Cached hosts: $(ls "$CACHE/hosts" | tr '\n' ' ')"
  } >&2
  exit 1
}

if [ "${1:-}" = "--" ]; then
  shift; H=""; prev=""
  for a in "$@"; do [ "$prev" = "-hostname" ] && H="$a"; prev="$a"; done
  JAR="$(jar_for "$H")"
  exec "$JAVA" "${OPTS[@]}" -jar "$JAR" "$@"
fi
JAR="$(jar_for "${1:-}")"
ARGS=(-apptype StandAlone -webport "${PORT:-443}")   # BMC HTTPS port; override with PORT=...
[ -n "${1:-}" ] && ARGS+=(-hostname "$1")
[ -n "${2:-}" ] && ARGS+=(-u "$2")
[ -n "${3:-}" ] && ARGS+=(-p "$3")
exec "$JAVA" "${OPTS[@]}" -jar "$JAR" "${ARGS[@]}"
