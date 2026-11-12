#!/bin/bash
# Build patched JViewer jars from the firmware originals in ./orig into ./dist
set -euo pipefail
cd "$(dirname "$0")"
B=build; rm -rf "$B" dist   # dist/Jar is JViewer's native-lib cache, cleared too
mkdir -p "$B/main" "$B/soc" dist
(cd "$B/main" && unzip -q ../../orig/JViewer.jar)
(cd "$B/soc"  && unzip -q ../../orig/JViewer-SOC.jar)
# Strip the (now invalid) code signatures
rm -f "$B"/*/META-INF/*.SF "$B"/*/META-INF/*.RSA "$B"/*/META-INF/*.DSA
for d in "$B"/*/META-INF; do printf 'Manifest-Version: 1.0\n' > "$d/MANIFEST.MF"; done
printf 'Main-Class: com.ami.kvm.jviewer.JViewer\nClass-Path: JViewer-SOC.jar\n' >> "$B/main/META-INF/MANIFEST.MF"

cpatch() { local f="$B/main/$1"; shift; python3 -I tools/cpatch.py "$f" "$f.new" "$@"; mv "$f.new" "$f"; }

# 1. Modern ArrayList.get(-1) throws IndexOutOfBoundsException, not ArrayIndexOutOfBoundsException
cpatch com/ami/kvm/jviewer/gui/StandAloneConnectionDialog.class \
  'java/lang/ArrayIndexOutOfBoundsException=java/lang/IndexOutOfBoundsException'

# 2. On fallback login the dialog forces https.protocols=SSLv3, which modern JDKs no longer have
cpatch 'com/ami/kvm/jviewer/gui/StandAloneConnectionDialog$Validator.class' 'SSLv3=TLSv1'

# Debug build: JVIEWER_DEBUG=1 ./build.sh  -> Debug.MODE=1 (log to console) instead of 2 (silent)
if [ "${JVIEWER_DEBUG:-0}" = 1 ]; then
  python3 -I - "$B/main/com/ami/kvm/jviewer/Debug.class" <<'PY'
import sys; f=sys.argv[1]; d=open(f,'rb').read()
old=bytes.fromhex('05b30005'); assert d.count(old)==1, d.count(old)
open(f,'wb').write(d.replace(old, bytes.fromhex('04b30005')))
PY
fi

# 3. Patched classes recompiled from source (patch-src): pure-Java vmedia (ImageDisk + *Redir), no native libs
if [ -n "$(find patch-src -name '*.java' 2>/dev/null)" ]; then
  javac --release 8 -nowarn -encoding UTF-8 -cp "$B/main:$B/soc" -d "$B/main" $(find patch-src -name '*.java')
fi

(cd "$B/main" && jar --create --file ../../dist/JViewer.jar --manifest META-INF/MANIFEST.MF $(ls | grep -v META-INF))
(cd "$B/soc"  && jar --create --file ../../dist/JViewer-SOC.jar --manifest META-INF/MANIFEST.MF $(ls | grep -v META-INF))
echo "Built dist/JViewer.jar dist/JViewer-SOC.jar"
