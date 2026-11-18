# Dependency checks, sourced by run.sh and build.sh. They only warn: an environment we haven't
# thought of may still work. JVIEWER_SKIP_CHECKS=1 turns them off.
#   check_deps run     java (11+, full JDK, GUI-capable), a SHA-256 tool, a display
#   check_deps build   javac, jar, unzip, python3

_dep_warn() { echo "warning: $*" >&2; }
_dep_have() { command -v "$1" >/dev/null 2>&1; }

_dep_jdk_hint() {
  case "$(uname -s)" in
    Darwin) echo "install a JDK, e.g. 'brew install openjdk' or from https://adoptium.net";;
    Linux)  if _dep_have apt-get; then echo "e.g. 'sudo apt install openjdk-25-jdk' (not -jre or -headless)"
            elif _dep_have dnf; then echo "e.g. 'sudo dnf install java-25-openjdk-devel' (not -headless)"
            else echo "install a full (non-headless) JDK from your distribution or https://adoptium.net"; fi;;
    *)      echo "install a JDK from https://adoptium.net";;
  esac
}

_dep_pkg_hint() {   # $1 = command
  case "$(uname -s)" in
    Darwin) case "$1" in python3) echo "e.g. 'xcode-select --install' or 'brew install python'";;
                         *) echo "e.g. 'brew install $1'";; esac;;
    *)      if _dep_have apt-get; then echo "e.g. 'sudo apt install $1'"
            elif _dep_have dnf; then echo "e.g. 'sudo dnf install $1'"
            else echo "install '$1' with your package manager"; fi;;
  esac
}

# Runs in a subshell without -e/-u/pipefail, so a failing probe can never stop the calling script.
check_deps() {
  [ "${JVIEWER_SKIP_CHECKS:-0}" = 1 ] && return 0
  ( set +eu +o pipefail; _check_deps "$@" )
  return 0
}

_check_deps() {
  local java="${JAVA:-java}" v major home=""
  case "$1" in
  run)
    if ! _dep_have "$java"; then
      _dep_warn "'$java' not found; JViewer needs Java 11 or newer ($(_dep_jdk_hint)), or set JAVA=/path/to/java"
    else
      v="$("$java" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -1)"
      major="${v%%[.+-]*}"; [ "$major" = 1 ] && { major="${v#1.}"; major="${major%%.*}"; }
      if [[ "$major" =~ ^[0-9]+$ ]] && [ "$major" -lt 11 ]; then
        _dep_warn "'$java' is Java $v; Java 11 or newer is needed ($(_dep_jdk_hint)), or set JAVA=/path/to/java"
      fi
      # Linux headless JDK packages leave out the X11 toolkit, so the window can't open
      home="$(readlink -f "$(command -v "$java")" 2>/dev/null)"; home="${home%/bin/*}"
      if [ "$(uname -s)" = Linux ] && [ -d "$home/lib" ] && [ ! -e "$home/lib/libawt_xawt.so" ]; then
        _dep_warn "$home looks like a headless Java (no lib/libawt_xawt.so), which can't open windows; $(_dep_jdk_hint)"
      fi
    fi
    # tools/Jnlp.java is run as a source file, which needs the JDK's compiler (a JRE doesn't have it)
    if [ -n "${home:-}" ] && [ -d "$home/bin" ]; then
      [ -e "$home/bin/javac" ] || _dep_warn "$home is a JRE, not a JDK: it can't run tools/Jnlp.java; $(_dep_jdk_hint)"
    fi
    _dep_have sha256sum || _dep_have shasum ||
      _dep_warn "neither 'sha256sum' nor 'shasum' found (used to cache builds); $(_dep_pkg_hint coreutils)"
    if [ "$(uname -s)" = Linux ] && [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
      _dep_warn "no DISPLAY or WAYLAND_DISPLAY set: JViewer needs an X11/XWayland session (or 'ssh -X')"
    fi
    ;;
  build)
    _dep_have javac || _dep_warn "no 'javac' on PATH (compiles the vmedia replacement); $(_dep_jdk_hint)"
    _dep_have jar || _dep_warn "no 'jar' on PATH (packs the patched jar); it comes with the JDK: $(_dep_jdk_hint)"
    _dep_have unzip || _dep_warn "no 'unzip' on PATH (unpacks the BMC's jars); $(_dep_pkg_hint unzip)"
    _dep_have python3 || _dep_warn "no 'python3' on PATH (tools/patch.py patches the bytecode); $(_dep_pkg_hint python3)"
    ;;
  esac
}
