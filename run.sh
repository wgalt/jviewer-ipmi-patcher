#!/bin/bash
# Launch the patched AMI JViewer KVM client on a modern OpenJDK.
#   ./run.sh <bmc-host> [username] [password]      (standalone login; HTTPS port 443, or PORT=... ./run.sh)
#   ./run.sh -- <raw JViewer args...>               (e.g. args copied from a jviewer.jnlp)
set -euo pipefail
D="$(cd "$(dirname "$0")" && pwd)"
[ -f "$D/dist/JViewer.jar" ] || "$D/build.sh"
JAVA="${JAVA:-java}"
OPTS=(-Djava.security.properties="$D/legacy.java.security" -Dhttps.protocols=TLSv1
      -Djdk.tls.client.protocols=TLSv1 -Dsun.java2d.metal=true
      -Djava.security.disableSystemPropertiesFile=true
      --add-opens java.desktop/java.awt=ALL-UNNAMED)
if [ "${1:-}" = "--" ]; then shift; exec "$JAVA" "${OPTS[@]}" -jar "$D/dist/JViewer.jar" "$@"; fi
ARGS=(-apptype StandAlone -webport "${PORT:-443}")   # BMC HTTPS port; override with PORT=...
[ -n "${1:-}" ] && ARGS+=(-hostname "$1")
[ -n "${2:-}" ] && ARGS+=(-u "$2")
[ -n "${3:-}" ] && ARGS+=(-p "$3")
exec "$JAVA" "${OPTS[@]}" -jar "$D/dist/JViewer.jar" "${ARGS[@]}"
