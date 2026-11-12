#!/usr/bin/env python3
"""Rewrite CONSTANT_Utf8 entries in a .class file.  usage: cpatch.py in.class out.class OLD=NEW [...]"""
import sys, struct
def patch(data, repl):
    assert data[:4] == b'\xca\xfe\xba\xbe'
    n = struct.unpack('>H', data[8:10])[0]
    out = bytearray(data[:10]); p = 10; i = 1; hits = {k: 0 for k in repl}
    while i < n:
        t = data[p]
        if t == 1:
            l = struct.unpack('>H', data[p+1:p+3])[0]; s = data[p+3:p+3+l]
            if s in repl: s = repl[s]; hits[[k for k in repl if repl[k] == s][0]] += 1
            out += bytes([1]) + struct.pack('>H', len(s)) + s; p += 3 + l
        else:
            size = {3:5,4:5,5:9,6:9,7:3,8:3,9:5,10:5,11:5,12:5,15:4,16:3,17:5,18:5,19:3,20:3}[t]
            out += data[p:p+size]; p += size
            if t in (5, 6): i += 1
        i += 1
    for k, v in hits.items():
        if v != 1: sys.exit(f'expected exactly 1 match for {k!r}, got {v}')
    return bytes(out + data[p:])
if __name__ == '__main__':
    r = dict(tuple(x.encode().split(b'=', 1)) for x in sys.argv[3:])
    open(sys.argv[2], 'wb').write(patch(open(sys.argv[1], 'rb').read(), r))
