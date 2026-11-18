#!/bin/sh
# Patch a set of JViewer jars (run.sh does this for you from a JNLP). On Windows use build.cmd.
#   ./build.sh [-o OUT_DIR] JAR|DIR ...        OUT_DIR defaults to dist
D=$(cd "$(dirname "$0")" && pwd)
. "$D/tools/javacheck.sh"
exec "$JAVA" -Djviewer.home="$D" "$D/tools/JViewerPatcher.java" build "$@"
