# Recover a .proto-like schema from jadx-decompiled protobuf-lite classes (newMessageInfo strings).
# Usage: python3 lite_schema.py <jadx/sources/defpackage> <RootClass> [...]
import sys, re, os

TYPES = ['double', 'float', 'int64', 'uint64', 'int32', 'fixed64', 'fixed32', 'bool', 'string', 'message',
         'bytes', 'uint32', 'enum', 'sfixed32', 'sfixed64', 'sint32', 'sint64', 'group']

def jstr(lit):
    out, i = [], 0
    while i < len(lit):
        c = lit[i]
        if c == '\\':
            n = lit[i + 1]
            if n == 'u':
                out.append(chr(int(lit[i + 2:i + 6], 16))); i += 6; continue
            if n in '01234567':
                m = re.match(r'[0-7]{1,3}', lit[i + 1:]).group(0)
                out.append(chr(int(m, 8))); i += 1 + len(m); continue
            out.append({'n': '\n', 't': '\t', 'r': '\r', 'b': '\b', 'f': '\f', 's': ' '}.get(n, n)); i += 2; continue
        out.append(c); i += 1
    return ''.join(out)

class Src:
    def __init__(self, d): self.d, self.cache = d, {}
    def get(self, cls):
        if cls not in self.cache:
            p = os.path.join(self.d, cls + '.java')
            self.cache[cls] = open(p, encoding='utf-8').read() if os.path.exists(p) else None
        return self.cache[cls]
    def info(self, cls):
        s = self.get(cls)
        if not s: return None
        m = re.search(r'newMessageInfo\(a, "((?:[^"\\]|\\.)*)", new Object\[\]\{(.*?)\}\);', s, re.S)
        if not m: return None
        objs = [o.strip() for o in re.findall(r'"[^"]*"|[\w$.]+\.class|[\w$.]+', m.group(2))]
        return jstr(m.group(1)), objs
    def ftype(self, cls, field):
        m = re.search(r'public (?:final )?([\w$.<>]+) ' + re.escape(field) + r'\b', self.get(cls) or '')
        return m.group(1) if m else '?'
    def enum_values(self, cls):
        s = self.get(cls) or ''
        return re.findall(r'\b([A-Z][A-Z0-9_]*)\((\d+)\)', s) or None

def decode(info):
    s, objs = info
    pos = [0]
    def nxt():
        v = ord(s[pos[0]]); pos[0] += 1
        if v >= 0xD800:
            r, sh = v & 0x1FFF, 13
            while True:
                v = ord(s[pos[0]]); pos[0] += 1
                if v >= 0xD800: r |= (v & 0x1FFF) << sh; sh += 13
                else: return r | (v << sh)
        return v
    flags, n = nxt(), nxt()
    oi = 0
    if n:
        oneofs, hasbits, _, _, _, _, _, _ = [nxt() for _ in range(8)]
        oi = oneofs * 2 + (1 if hasbits else 0)
    fields = []
    for _ in range(n):
        num, tx = nxt(), nxt()
        t = tx & 0xFF
        f = {'num': num, 'raw': t, 'req': bool(tx & 0x400), 'oneof': None, 'cls': None, 'name': None}
        if t >= 51:
            idx = nxt(); f['oneof'] = idx; base = t - 51
            if base in (9, 17) or (base == 12 and tx & 0x800): f['cls'] = objs[oi].replace('.class', ''); oi += 1
            f['name'] = objs[idx * 2].strip('"') + '[oneof %d]' % idx
            t = base
        else:
            f['name'] = objs[oi].strip('"'); oi += 1
            if t in (27, 49) or ((t in (12, 30, 44)) and tx & 0x800) or t == 50:
                f['cls'] = objs[oi].replace('.class', ''); oi += 1
                if t == 50 and tx & 0x800: oi += 1
            if tx & 0x1000: nxt()
        if t <= 17: f['label'], f['type'] = 'optional', TYPES[t]
        elif t <= 34: f['label'], f['type'] = 'repeated', TYPES[t - 18]
        elif t <= 48: f['label'], f['type'] = 'repeated(packed)', TYPES[t - 35 + 0]
        elif t == 49: f['label'], f['type'] = 'repeated', 'group'
        else: f['label'], f['type'] = 'map', 'map'
        f['t'] = t
        fields.append(f)
    return flags, fields

def dump(src, root, seen=None, ind=0, out=None):
    seen = set() if seen is None else seen
    if root in seen:
        out.append(' ' * ind + f'  (see {root})'); return
    seen.add(root)
    info = src.info(root)
    if not info:
        out.append(' ' * ind + f'  ({root}: no message info)'); return
    _, fields = decode(info)
    for f in fields:
        typ, sub = f['type'], None
        if typ == 'message':
            sub = f['cls'] or src.ftype(root, f['name'])
            typ = sub
        elif typ == 'enum':
            e = f['cls']
            typ = 'enum' + (f' {e}' if e else '')
            if e:
                ev = src.enum_values(e)
                if ev: typ += ' {' + ', '.join(f'{k}={v}' for k, v in ev) + '}'
        out.append(' ' * ind + f'{f["num"]:>3} {f["label"]:<16} {typ:<12} ({root}.{f["name"]})')
        if sub: dump(src, sub, seen, ind + 6, out)
    return out

if __name__ == '__main__':
    src = Src(sys.argv[1])
    for r in sys.argv[2:]:
        print(f'message {r}'); print('\n'.join(dump(src, r, out=[]))); print()
