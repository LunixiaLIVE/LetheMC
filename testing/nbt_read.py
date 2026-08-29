import gzip, struct, sys, glob, os
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

class R:
    def __init__(s, d): s.d, s.i = d, 0
    def u1(s):
        v = s.d[s.i]; s.i += 1; return v
    def n(s, fmt, sz):
        v = struct.unpack_from(fmt, s.d, s.i)[0]; s.i += sz; return v
    def string(s):
        ln = s.n('>H', 2); v = s.d[s.i:s.i+ln].decode('utf8', 'replace'); s.i += ln; return v

def payload(r, t):
    if t == 1:  return r.n('>b',1)
    if t == 2:  return r.n('>h',2)
    if t == 3:  return r.n('>i',4)
    if t == 4:  return r.n('>q',8)
    if t == 5:  return r.n('>f',4)
    if t == 6:  return r.n('>d',8)
    if t == 7:
        ln = r.n('>i',4); v = r.d[r.i:r.i+ln]; r.i += ln; return v
    if t == 8:  return r.string()
    if t == 9:
        it = r.u1(); ln = r.n('>i',4)
        return [payload(r, it) for _ in range(max(0,ln))]
    if t == 10:
        out = {}
        while True:
            tt = r.u1()
            if tt == 0: return out
            nm = r.string(); out[nm] = payload(r, tt)
    if t == 11:
        ln = r.n('>i',4); return [r.n('>i',4) for _ in range(ln)]
    if t == 12:
        ln = r.n('>i',4); return [r.n('>q',8) for _ in range(ln)]
    raise ValueError('tag %d' % t)

def load(path):
    d = open(path,'rb').read()
    if d[:2] == b'\x1f\x8b': d = gzip.decompress(d)
    r = R(d); t = r.u1()
    if t != 10: raise ValueError('not a compound root')
    r.string()
    return payload(r, 10)

def summarize(lst):
    out = {}
    for it in lst or []:
        k = it.get('id','?')
        out[k] = out.get(k,0) + it.get('count', it.get('Count',1))
    return out

for path in sys.argv[1:]:
    if not os.path.exists(path): continue
    print('=== %s ===' % path)
    try:
        root = load(path)
    except Exception as e:
        print('  parse failed: %r' % e); continue
    inv = root.get('Inventory') or root.get('inventory') or []
    end = root.get('EnderItems') or root.get('enderItems') or []
    print('  Health   :', root.get('Health'))
    print('  XpLevel  :', root.get('XpLevel'), ' XpTotal:', root.get('XpTotal'))
    print('  Inventory: %d slots -> %s' % (len(inv), summarize(inv) or 'EMPTY'))
    print('  EnderChst: %d slots -> %s' % (len(end), summarize(end) or 'EMPTY'))
    print()
