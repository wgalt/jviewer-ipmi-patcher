#!/usr/bin/env python3
"""Patch an extracted JViewer class tree in place so it runs on a modern OpenJDK.

usage: patch.py CLASSES_DIR [--debug]

The patches look for *what* needs fixing rather than at fixed offsets, so they work across JViewer versions:
  tls      String constants like "SSLv3" / "SSLv3,TLSv1" (https.protocols values) lose their SSL entries
  catch    `catch (ArrayIndexOutOfBoundsException)` -> `catch (IndexOutOfBoundsException)` (ArrayList.get(-1))
  natives  every `native` method gets a body calling the static method of the same name in
           com.ami.iusb.VMediaNatives, with `this` (as Object) prepended (pure-Java vmedia, see patch-src)
  libload  in those classes, System.load/loadLibrary and *.getWrapperLibrary go to VMediaNatives no-ops
  debug    (--debug) com.ami.kvm.jviewer.Debug.MODE set to 1 (console logging) where it is initialised
"""
import os, re, struct, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from classfile import (ClassFile, CLASS, STRING, UTF8, METHODREF, ACC_STATIC, ACC_NATIVE,
                       parse_desc, slots, load_op, return_op)

HELPER = 'com/ami/iusb/VMediaNatives'
PROTO = re.compile(rb'(SSLv2Hello|SSLv3|SSLv2|TLSv1(\.[0-3])?)( *, *(SSLv2Hello|SSLv3|SSLv2|TLSv1(\.[0-3])?))*')


def warn(msg):
    print(f'patch: WARNING: {msg}', file=sys.stderr)


def patch_tls(cf):
    hits = 0
    strings = {struct.unpack('>H', v)[0] for e in cf.cp if e and e[0] == STRING for v in [e[1]]}
    for i in strings:
        s = cf.cp[i][1]
        if PROTO.fullmatch(s) and b'SSL' in s:
            keep = [p.strip() for p in s.split(b',') if not p.strip().startswith(b'SSL')]
            cf.cp[i] = (UTF8, b','.join(keep) or b'TLSv1'); hits += 1
    return hits


def patch_catch(cf):
    old = [i for i, e in enumerate(cf.cp) if e and e[0] == CLASS
           and cf.class_name(i) == 'java/lang/ArrayIndexOutOfBoundsException']
    if not old:
        return 0
    hits, new = 0, None
    for m in cf.methods:
        a = cf.code_attr(m)
        if not a:
            continue
        b = bytearray(a[1])
        p = 8 + struct.unpack('>I', b[4:8])[0]
        n = struct.unpack('>H', b[p:p+2])[0]
        for k in range(n):
            o = p + 2 + 8 * k + 6
            if struct.unpack('>H', b[o:o+2])[0] in old:
                new = new or cf.klass('java/lang/IndexOutOfBoundsException')
                b[o:o+2] = struct.pack('>H', new); hits += 1
        a[1] = bytes(b)
    return hits


def stub_code(cf, m):
    """Body for native method m: return VMediaNatives.<name>([this,] args...)."""
    args, ret = parse_desc(m.desc)
    static = m.access & ACC_STATIC
    hdesc = '(' + ('' if static else 'Ljava/lang/Object;') + ''.join(args) + ')' + ret
    code, slot = bytearray(), 0
    if not static:
        code += b'\x2a'; slot = 1
    for t in args:
        code += bytes([load_op(t), slot]); slot += slots(t)
    if slot > 255:
        raise ValueError('too many arguments')
    code += b'\xb8' + struct.pack('>H', cf.methodref(HELPER, m.name, hdesc)) + bytes([return_op(ret)])
    body = struct.pack('>HHI', max(slot, slots(ret), 1), slot, len(code)) + bytes(code) + b'\0\0\0\0'
    return hdesc, [cf.utf8('Code'), body]


def patch_natives(cf, helper):
    done, missing = [], []
    for m in cf.methods:
        if not m.access & ACC_NATIVE:
            continue
        hdesc, attr = stub_code(cf, m)
        if (m.name, hdesc) not in helper:
            missing.append(f'{m.name}{hdesc}'); continue
        m.access &= ~ACC_NATIVE; m.attrs.append(attr); done.append(m.name)
    return done, missing


def patch_libload(cf, helper):
    hits = 0
    for i, e in enumerate(cf.cp):
        if not e or e[0] != METHODREF:
            continue
        c, n, d = cf.ref_at(i)
        if (c == 'java/lang/System' and n in ('load', 'loadLibrary')) or n == 'getWrapperLibrary':
            if (n, d) not in helper:
                warn(f'{cf.name}: no VMediaNatives.{n}{d} to redirect {c}.{n} to'); continue
            cf.cp[i] = (METHODREF, struct.pack('>H', cf.klass(HELPER)) + e[1][2:]); hits += 1
    return hits


def patch_debug(cf):
    fld = [i for i, e in enumerate(cf.cp) if e and e[0] == 9
           and cf.ref_at(i) == ('com/ami/kvm/jviewer/Debug', 'MODE', 'I')]
    for m in cf.methods:
        if m.name in ('<clinit>', '<init>') and fld:
            a = cf.code_attr(m); b = bytearray(a[1])
            pat = b'\xb3' + struct.pack('>H', fld[0])
            o = b.find(pat, 8)
            if o > 8 and 0x02 <= b[o-1] <= 0x08:   # iconst_m1..iconst_5 ; putstatic MODE
                b[o-1] = 0x04; a[1] = bytes(b); return 1
    return 0


def main():
    root = sys.argv[1]; debug = '--debug' in sys.argv[2:]
    hp = os.path.join(root, HELPER + '.class')
    helper = {(m.name, m.desc) for m in ClassFile(open(hp, 'rb').read()).methods if m.access & ACC_STATIC}
    total = {'tls': 0, 'catch': 0, 'natives': 0, 'libload': 0, 'debug': 0}
    for d, _, files in os.walk(root):
        for f in files:
            if not f.endswith('.class'):
                continue
            path = os.path.join(d, f)
            cf = ClassFile(open(path, 'rb').read())
            if cf.name == HELPER:
                continue
            n = {'tls': patch_tls(cf), 'catch': patch_catch(cf)}
            done, missing = patch_natives(cf, helper)
            for x in missing:
                warn(f'{cf.name}.{x} is native and VMediaNatives has no replacement; it will throw UnsatisfiedLinkError')
            n['natives'] = len(done)
            n['libload'] = patch_libload(cf, helper) if done else 0
            if debug and cf.name == 'com/ami/kvm/jviewer/Debug':
                n['debug'] = patch_debug(cf)
            if any(n.values()):
                open(path, 'wb').write(cf.to_bytes())
                print(f'patch: {cf.name}: ' + ', '.join(f'{k} {v}' for k, v in n.items() if v))
            for k in total:
                total[k] += n.get(k, 0)
    if not total['tls']:
        warn('no SSLv3 protocol strings found (fine if this version never forces SSLv3)')
    if not total['natives']:
        warn('no native vmedia methods found; virtual media classes may have moved')
    if debug and not total['debug']:
        warn('--debug: could not find Debug.MODE initialiser')


if __name__ == '__main__':
    main()
