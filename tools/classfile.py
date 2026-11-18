"""Minimal Java class-file reader/writer: enough to edit the constant pool, method bodies and exception tables."""
import struct

# constant-pool tag -> payload size in bytes (Utf8 is variable)
_CP_SIZE = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
UTF8, CLASS, STRING, FIELDREF, METHODREF, IMETHODREF, NAMEANDTYPE = 1, 7, 8, 9, 10, 11, 12
ACC_STATIC, ACC_NATIVE, ACC_ABSTRACT = 0x0008, 0x0100, 0x0400


class Member:
    def __init__(self, access, name, desc, attrs):
        self.access, self.name, self.desc, self.attrs = access, name, desc, attrs  # attrs: [[name_idx, bytes]]


class ClassFile:
    def __init__(self, data):
        if data[:4] != b'\xca\xfe\xba\xbe':
            raise ValueError('not a class file')
        self.minor, self.major = struct.unpack('>HH', data[4:8])
        n = struct.unpack('>H', data[8:10])[0]
        self.cp = [None]          # cp[i] = (tag, value); value is bytes for Utf8, tuple of ints/bytes otherwise
        p, i = 10, 1
        while i < n:
            t = data[p]
            if t == UTF8:
                l = struct.unpack('>H', data[p+1:p+3])[0]
                self.cp.append((t, bytes(data[p+3:p+3+l]))); p += 3 + l
            else:
                self.cp.append((t, bytes(data[p+1:p+1+_CP_SIZE[t]]))); p += 1 + _CP_SIZE[t]
                if t in (5, 6):
                    self.cp.append(None); i += 1
            i += 1
        self.access, self.this, self.super, ni = struct.unpack('>HHHH', data[p:p+8]); p += 8
        self.interfaces = list(struct.unpack(f'>{ni}H', data[p:p+2*ni])); p += 2 * ni
        self.fields, p = self._members(data, p)
        self.methods, p = self._members(data, p)
        self.attrs, p = self._attrs(data, p)
        if p != len(data):
            raise ValueError('trailing data in class file')

    @staticmethod
    def _attrs(data, p):
        n = struct.unpack('>H', data[p:p+2])[0]; p += 2; out = []
        for _ in range(n):
            ni, l = struct.unpack('>HI', data[p:p+6])
            out.append([ni, bytes(data[p+6:p+6+l])]); p += 6 + l
        return out, p

    def _members(self, data, p):
        n = struct.unpack('>H', data[p:p+2])[0]; p += 2; out = []
        for _ in range(n):
            a, ni, di = struct.unpack('>HHH', data[p:p+6])
            attrs, p = self._attrs(data, p + 6)
            out.append(Member(a, self.utf8_at(ni), self.utf8_at(di), attrs))
        return out, p

    # ---- constant pool access ----
    def utf8_at(self, i):
        t, v = self.cp[i]; assert t == UTF8; return v.decode('utf-8', 'surrogatepass')

    def _u2(self, i, k=0):
        return struct.unpack('>H', self.cp[i][1][2*k:2*k+2])[0]

    def class_name(self, i):
        return self.utf8_at(self._u2(i))

    @property
    def name(self):
        return self.class_name(self.this)

    def ref_at(self, i):
        """Fieldref/Methodref/InterfaceMethodref -> (class, name, desc)."""
        nat = self._u2(i, 1)
        return self.class_name(self._u2(i)), self.utf8_at(self._u2(nat)), self.utf8_at(self._u2(nat, 1))

    def _find_or_add(self, tag, payload):
        for i, e in enumerate(self.cp):
            if e == (tag, payload):
                return i
        if len(self.cp) >= 0xffff:
            raise ValueError('constant pool full')
        self.cp.append((tag, payload)); return len(self.cp) - 1

    def utf8(self, s):
        return self._find_or_add(UTF8, s.encode('utf-8', 'surrogatepass'))

    def klass(self, name):
        return self._find_or_add(CLASS, struct.pack('>H', self.utf8(name)))

    def methodref(self, cls, name, desc):
        nat = self._find_or_add(NAMEANDTYPE, struct.pack('>HH', self.utf8(name), self.utf8(desc)))
        return self._find_or_add(METHODREF, struct.pack('>HH', self.klass(cls), nat))

    # ---- serialisation ----
    def _ser_attrs(self, attrs):
        return struct.pack('>H', len(attrs)) + b''.join(struct.pack('>HI', ni, len(b)) + b for ni, b in attrs)

    def _ser_members(self, ms):
        return struct.pack('>H', len(ms)) + b''.join(
            struct.pack('>HHH', m.access, self.utf8(m.name), self.utf8(m.desc)) + self._ser_attrs(m.attrs) for m in ms)

    def to_bytes(self):
        # members/attrs may add Utf8 entries, so serialise them before the constant pool
        body = (struct.pack('>HHHH', self.access, self.this, self.super, len(self.interfaces))
                + struct.pack(f'>{len(self.interfaces)}H', *self.interfaces)
                + self._ser_members(self.fields) + self._ser_members(self.methods) + self._ser_attrs(self.attrs))
        cp = bytearray()
        for e in self.cp[1:]:
            if e is None:
                continue
            t, v = e
            cp += bytes([t]) + (struct.pack('>H', len(v)) if t == UTF8 else b'') + v
        return b'\xca\xfe\xba\xbe' + struct.pack('>HHH', self.minor, self.major, len(self.cp)) + bytes(cp) + body

    # ---- Code attribute helpers ----
    def code_attr(self, m):
        for a in m.attrs:
            if self.utf8_at(a[0]) == 'Code':
                return a
        return None


def parse_desc(desc):
    """'(I[BLjava/lang/String;)V' -> (['I', '[B', 'Ljava/lang/String;'], 'V')"""
    assert desc[0] == '('
    args, i = [], 1
    while desc[i] != ')':
        j = i
        while desc[j] == '[':
            j += 1
        j = desc.index(';', j) + 1 if desc[j] == 'L' else j + 1
        args.append(desc[i:j]); i = j
    return args, desc[i+1:]


def slots(t):
    return 2 if t in ('J', 'D') else 0 if t == 'V' else 1


def load_op(t):
    return {'I': 0x15, 'Z': 0x15, 'B': 0x15, 'C': 0x15, 'S': 0x15, 'J': 0x16, 'F': 0x17, 'D': 0x18}.get(t, 0x19)


def return_op(t):
    return {'V': 0xb1, 'J': 0xad, 'F': 0xae, 'D': 0xaf}.get(t, 0xb0 if t[0] in 'L[' else 0xac)
