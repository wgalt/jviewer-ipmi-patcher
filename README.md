# JViewer (AMI MegaRAC KVM) for modern OpenJDK

KVM client from the ASRock EPC612D4I BMC firmware 07.12.00, patched to run on OpenJDK 25 (tested on Apple Silicon macOS).

```bash
./run.sh nas-mgmt          # standalone login dialog
./build.sh                 # rebuild dist/ from orig/ (JVIEWER_DEBUG=1 for console logging)
```

`orig/` holds the unmodified jars from the firmware's cramfs `www` partition (`Java/release/`).

## Fixes
| Problem on modern Java | Fix |
|---|---|
| Startup crash in `getSupportedLocales` (`ArrayList.get(-1)` now throws `IndexOutOfBoundsException`) | bytecode: widen catch type |
| Fallback login sets `https.protocols=SSLv3` (removed from JDK) → "Connection Failure" | bytecode: `SSLv3` → `TLSv1` |
| BMC speaks only TLS 1.0 / `TLS_RSA_WITH_AES_256_CBC_SHA`, 1024-bit SHA-1 cert | `legacy.java.security` + TLSv1-only flags in `run.sh` |
| Native vmedia libs missing from jar in standalone mode → `FloppyRedir` init crash killed the network listener (blank video) | bundle natives under `com/ami/kvm/jviewer/lib/<os>/` |
| x86_64-only natives on Apple Silicon → `UnsatisfiedLinkError` from LED / keyboard-layout calls | `patch-src/com/ami/iusb/FloppyRedir.java`: wrappers fall back gracefully |
| Reflection into `AWTEvent.bdata` blocked by module system | `--add-opens java.desktop/java.awt=ALL-UNNAMED` |
| Jar signatures invalid after patching | stripped |

## Limitations
- Virtual media (ISO/floppy/HDD redirection) needs the native libs, which exist only for x86/x86_64. On Apple Silicon run with an x86_64 JDK under Rosetta (`JAVA=/path/to/x64/java ./run.sh ...`) if you need it.
- Passing a password as `./run.sh host user pass` makes it visible in `ps`; prefer typing it in the dialog.
