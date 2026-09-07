#!/usr/bin/env python3
"""Match pristine (.rdata) and live (.data) JNIEnv tables in both dumps.
Prints qword layouts + entry target histograms so known/new can be compared."""
import struct
import sys
import numpy as np

sys.path.insert(0, '.')
from mdump import Minidump

def load(dump_path):
    md = Minidump(dump_path)
    jbase, jsize, _ = md.find_module('jvm.dll')
    img = bytearray(b'\0' * jsize)
    for s, sz, fo in md.mem_regions:
        if s < jbase + jsize and s + sz > jbase:
            lo = max(s, jbase) - jbase
            hi = min(s + sz, jbase + jsize) - jbase
            md.f.seek(fo + (jbase + lo - s))
            img[lo:hi] = md.f.read(hi - lo)
    return md, jbase, jsize, bytes(img)

def sections(img):
    e = struct.unpack_from('<I', img, 0x3C)[0]
    nsec = struct.unpack_from('<H', img, e + 6)[0]
    opt = struct.unpack_from('<H', img, e + 20)[0]
    sec0 = e + 24 + opt
    out = []
    for i in range(nsec):
        off = sec0 + 40 * i
        name = img[off:off + 8].rstrip(b'\0').decode(errors='replace')
        vsz, vaddr = struct.unpack_from('<II', img, off + 8)
        chars = struct.unpack_from('<I', img, off + 36)[0]
        out.append((name, vaddr, vsz, chars))
    return out

def profile(md, jbase, img, secs, rva, n=233):
    q = np.frombuffer(img[rva:rva + n * 8], dtype='<u8')
    astr = md.find_module('Astraea.dll')
    abase, asize = (astr[0], astr[1]) if astr else (0, 0)
    execs = [(va, va + vsz) for nm, va, vsz, ch in secs if ch & 0x20000000]
    text_lo = next(va for nm, va, vsz, ch in secs if nm == '.text')
    text_hi = text_lo + next(vsz for nm, va, vsz, ch in secs if nm == '.text')
    stats = {'nonzero': 0, 'text': 0, 'exec_other': 0, 'astraea': 0, 'out': 0, 'null': 0}
    outs = []
    for v in q:
        if v == 0:
            stats['null'] += 1
            continue
        stats['nonzero'] += 1
        r = v - jbase
        if text_lo <= r < text_hi:
            stats['text'] += 1
        elif any(lo <= r < hi for lo, hi in execs):
            stats['exec_other'] += 1
        elif abase and abase <= v < abase + asize:
            stats['astraea'] += 1
            outs.append(v - abase)
        else:
            stats['out'] += 1
            outs.append(v)
    return stats, outs, q

def show(md, jbase, img, secs, rva, n=14, label=''):
    st, outs, q = profile(md, jbase, img, secs, rva, 233)
    print(f"  {label} @ {rva:#x}: {st}")
    for i in range(n):
        v = int(q[i])
        r = v - jbase
        tag = ''
        if v:
            for nm, va, vsz, _ in secs:
                if va <= r < va + vsz:
                    tag = f"{nm}+{r - va:#x}"
                    break
            else:
                astr = md.find_module('Astraea.dll')
                if astr and astr[0] <= v < astr[0] + astr[1]:
                    tag = f"Astraea+{v - astr[0]:#x}"
                else:
                    tag = f"ext {v:#x}"
        print(f"    [{i:3d}] {v:#018x} {tag}")

OLD = r"D:\1proekts\rustme\rustme_19600_1782646686.dmp"
NEW = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"

print("======== OLD (ground truth) ========")
md, jb, js, img = load(OLD)
secs = sections(img)
show(md, jb, img, secs, 0xB628F8, 14, 'KNOWN native (.rdata)')
show(md, jb, img, secs, 0xDEC0F0, 14, 'KNOWN live (.data)')

print("======== NEW ========")
md2, jb2, js2, img2 = load(NEW)
secs2 = sections(img2)
show(md2, jb2, img2, secs2, 0xBD2898, 14, 'cand native (.rdata, run+0x18)')
# candidate live: scan .data for windows: >=220 nonzero, >=200 text+astraea
q2 = np.frombuffer(img2, dtype='<u8')
data_lo, data_sz = next((va, vsz) for nm, va, vsz, _ in secs2 if nm == '.data')
data_hi = data_lo + data_sz
astr2 = md2.find_module('Astraea.dll')
abase2, asize2 = (astr2[0], astr2[1]) if astr2 else (0, 0)
execs2 = [(va, va + vsz) for nm, va, vsz, ch in secs2 if ch & 0x20000000]
text2 = next((va, va + vsz) for nm, va, vsz, ch in secs2 if nm == '.text')
lo_off = data_lo // 8
hi_off = data_hi // 8
print("\n  scanning new .data for live-table windows...")
best = []
for off in range(lo_off, hi_off - 233):
    seg = q2[off:off + 233]
    nz = int((seg != 0).sum())
    if nz < 200:
        continue
    r = (seg - jb2).astype(np.int64)
    t = ((r >= text2[0]) & (r < text2[1])).sum()
    a = 0
    if abase2:
        ra = seg - abase2
        a = int(((ra >= 0) & (ra < asize2)).sum())
    if t + a >= 210:
        best.append((off * 8, int(t), a, nz))
for rva, t, a, nz in best:
    print(f"    rva {rva:#x}: text={t} astraea={a} nonzero={nz}/233")
if not best:
    print("    none")
