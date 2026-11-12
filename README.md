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
| Native vmedia libs (x86 only, missing from jar in standalone mode) → `FloppyRedir` init crash killed the network listener (blank video) | replaced entirely by pure Java, see below |
| Reflection into `AWTEvent.bdata` blocked by module system | `--add-opens java.desktop/java.awt=ALL-UNNAMED` |
| Jar signatures invalid after patching | stripped |

## Virtual media (pure Java)
The native `libjava{cdrom,floppy,harddisk}wrapper` libraries were reverse-engineered (the Linux builds have symbols)
and replaced by `patch-src/com/ami/iusb/ImageDisk.java`, a small SCSI block-device emulator over an image file.
`CDROMRedir`/`FloppyRedir`/`HarddiskRedir` are the decompiled classes with their `native` methods delegating to it.

IUSB SCSI packet: 32-byte IUSB header, ReadLen@32, TagNo@36, DataDir@40, 12-byte CDB@41,
status/sense@53..56, DataLen@57 (LE), data@61. Response = request's first 62 bytes + status + data.

- CD-ROM (2048-byte sectors, read-only): TEST UNIT READY, READ CAPACITY, READ(10/12), READ TOC (formats 0/1, LBA/MSF), START STOP, PREVENT ALLOW
- Floppy / hard disk (512-byte sectors, read/write, file-locked): the above minus TOC, plus WRITE(10), FORMAT UNIT, READ FORMAT CAPACITIES, MODE SENSE(6/10)
- Physical host drives are not supported (image files only).

Tested on a Linux host via the BMC: ISO mounted and read (virtual CD-ROM); a 64 MB MBR/FAT32 image and a
1.44 MB FAT12 floppy image mounted, read and written, with the writes landing in the image files.

## Linux
No native code, so any architecture works. Notes (untested on Linux so far):
- Needs a non-headless JRE/JDK (e.g. `openjdk-25-jdk`, not `-jre-headless`) and an X11/XWayland session.
- Fedora/RHEL system crypto policies are opted out of (`security.useSystemPropsFile=false`,
  `-Djava.security.disableSystemPropertiesFile=true`) so TLS 1.0 to the BMC stays allowed.
- HiDPI: add `-Dsun.java2d.uiScale=2` to `OPTS` in `run.sh` if the window is tiny.

## Limitations
- Passing a password as `./run.sh host user pass` makes it visible in `ps`; prefer typing it in the dialog.
- Lock-key state is not synchronised. The original native code read the local Num/Caps/Scroll Lock state and
  toggled local keys (via `java.awt.Robot`) to match the remote machine's LEDs; that is now a no-op
  (`FloppyRedir.GetLEDStatus` echoes the remote state). Pressing a lock key still sends it to the remote machine,
  and the status bar's Num/Caps/Scroll indicators show the remote state, but if the two start out different they
  stay different until you press the key. (The macOS native version never worked anyway: it linked against a
  long-gone X11 path.)
