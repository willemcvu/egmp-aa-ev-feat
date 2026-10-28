# Minimal protobuf wire-format dumper: decodes fixed32 as float, collapses repeated sibling runs.
import sys, struct
def varint(b,i):
    r=s=0
    while True:
        c=b[i]; i+=1; r|=(c&0x7f)<<s; s+=7
        if c<0x80: return r,i
def parse(b):
    i=0; out=[]
    while i<len(b):
        k,i=varint(b,i); f,w=k>>3,k&7
        if w==0: v,i=varint(b,i); out.append((f,'v',v))
        elif w==5: out.append((f,'f',struct.unpack('<f',b[i:i+4])[0])); i+=4
        elif w==1: out.append((f,'d',struct.unpack('<d',b[i:i+8])[0])); i+=8
        elif w==2:
            n,i=varint(b,i); s=b[i:i+n]; i+=n
            try: sub=parse(s); out.append((f,'m',sub))
            except Exception: out.append((f,'b',s))
        else: raise ValueError(w)
    return out
def flat(m):
    if m[1]=='m': return '{'+' '.join(flat(x) for x in m[2])+'}'
    if m[1]=='f': return f'{m[0]}:{m[2]:.6g}f'
    return f'{m[0]}:{m[2]}'
def show(msg,ind=0,path=''):
    j=0
    while j<len(msg):
        f,t,v=msg[j]; k=j
        while k+1<len(msg) and msg[k+1][0]==f and t=='m': k+=1
        p=f'{path}.{f}'
        if t=='m' and k>j:
            print(' '*ind+f'{p} x{k-j+1}:')
            for x in msg[j:k+1]: print(' '*(ind+2)+flat(x))
        elif t=='m':
            print(' '*ind+f'{p} {{'); show(v,ind+2,p); print(' '*ind+'}')
        else: print(' '*ind+f'{p} = '+(f'{v:.6g} (float)' if t=='f' else (v.hex() if t=='b' else str(v))))
        j=k+1
show(parse(open(sys.argv[1],'rb').read()))
