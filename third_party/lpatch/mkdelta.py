#!/usr/bin/env python3
"""Make an lpatch delta (.lpd): rebuilds <target> from the head unit's own <source>.

usage: mkdelta.py <source> <target> <out.lpd>

The delta stores the md5 of the source and of the target. On the head unit,
`lpatch delta` rebuilds the target only from that exact source and only
writes it if the result matches. Needs: pip install --require-hashes -r tools/requirements.txt
"""
import hashlib, struct, sys, zlib
import bsdiff4.core

def main():
    if len(sys.argv) > 1 and sys.argv[1] in ('-h', '--help'):
        print(__doc__)
        return
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    src, tgt, out = sys.argv[1:4]
    a, b = open(src, 'rb').read(), open(tgt, 'rb').read()
    ctrl, diff, extra = bsdiff4.core.diff(a, b)
    body = struct.pack('<I', len(ctrl))
    for x, y, z in ctrl:
        if not (0 <= x < 2**31 and 0 <= y < 2**31 and -2**31 <= z < 2**31):
            sys.exit('control value out of range')
        body += struct.pack('<iii', x, y, z)
    body += struct.pack('<I', len(diff)) + diff + struct.pack('<I', len(extra)) + extra
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    payload = c.compress(body) + c.flush()
    hdr = (b'LPD1' + struct.pack('<I', len(a)) + hashlib.md5(a).digest()
           + struct.pack('<I', len(b)) + hashlib.md5(b).digest()
           + struct.pack('<II', 1, len(payload)))
    open(out, 'wb').write(hdr + payload)
    assert apply(a, hdr + payload) == b
    print('%s  %d bytes (target %d)' % (out, len(hdr) + len(payload), len(b)))

def apply(a, d):
    """Reference implementation of `lpatch delta` (used as a self-check)."""
    assert d[:4] == b'LPD1'
    comp, plen = struct.unpack('<II', d[44:52])
    body = zlib.decompress(d[52:52 + plen], -15) if comp == 1 else d[52:52 + plen]
    n = struct.unpack('<I', body[:4])[0]; p = 4
    ctrl = [struct.unpack('<iii', body[p + 12*i:p + 12*i + 12]) for i in range(n)]; p += 12 * n
    dl = struct.unpack('<I', body[p:p+4])[0]; diff = body[p+4:p+4+dl]; p += 4 + dl
    el = struct.unpack('<I', body[p:p+4])[0]; extra = body[p+4:p+4+el]
    out = bytearray(); op = dp = ep = 0
    for x, y, z in ctrl:
        for k in range(x):
            v = diff[dp + k]; o = op + k
            if 0 <= o < len(a): v = (v + a[o]) & 255
            out.append(v)
        op += x; dp += x
        out += extra[ep:ep + y]; ep += y; op += z
    return bytes(out)

if __name__ == '__main__':
    main()
