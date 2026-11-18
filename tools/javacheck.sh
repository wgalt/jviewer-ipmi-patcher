# Sourced by run.sh / build.sh: picks $JAVA and warns (without stopping) if it isn't a JDK 24+,
# since tools/JViewerPatcher.java is run from source and uses java.lang.classfile.
JAVA="${JAVA:-java}"
if [ "${JVIEWER_SKIP_CHECKS:-0}" != 1 ]; then
  case "$(uname -s)" in
    Darwin) _hint="install a JDK, e.g. 'brew install openjdk' or from https://adoptium.net";;
    *) if command -v apt-get >/dev/null 2>&1; then _hint="e.g. 'sudo apt install openjdk-25-jdk' (not -jre or -headless)"
       elif command -v dnf >/dev/null 2>&1; then _hint="e.g. 'sudo dnf install java-25-openjdk-devel' (not -headless)"
       else _hint="install a JDK from your distribution or https://adoptium.net"; fi;;
  esac
  if ! command -v "$JAVA" >/dev/null 2>&1; then
    echo "warning: '$JAVA' not found; a JDK 24 or newer is needed; $_hint; or set JAVA=/path/to/java" >&2
  else
    # "openjdk 25.0.1 2025-10-21" -> 25 (Java 8 has no --version and gives nothing)
    _v=$("$JAVA" --version 2>/dev/null | head -n 1 | awk '{print $2}')
    _major=$(printf '%s' "$_v" | sed 's/[^0-9].*//')
    if [ -z "$_major" ] || [ "$_major" -lt 24 ]; then
      echo "warning: '$JAVA' is Java ${_v:-8 or older}; a JDK 24 or newer is needed; $_hint; or set JAVA=/path/to/java" >&2
    fi
  fi
fi
