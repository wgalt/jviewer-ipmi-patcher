#!/bin/sh
# Launch the patched AMI JViewer KVM client on a modern JDK (24+). See README.md; on Windows use run.cmd.
#   ./run.sh <jviewer.jnlp | https://.../x.jnlp>   fetch this BMC's own jars, patch them (cached) and launch
#   ./run.sh [bmc-host] [username] [password]      standalone login, with the jars cached for that host
#   ./run.sh -- <raw JViewer args...>
D=$(cd "$(dirname "$0")" && pwd)
. "$D/tools/javacheck.sh"
exec "$JAVA" -Djviewer.home="$D" "$D/tools/JViewerPatcher.java" run "$@"
