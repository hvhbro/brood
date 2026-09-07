#!/usr/bin/env python3
"""Profile specific .data candidates in the NEW dump: where do their 233
entries point, and how many heap refs point at each candidate start."""
import struct
import sys
import mmap
import numpy as np
from collections import Counter

sys.path.insert(0, '.')
from mdump import Minidump

NEW = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"

md = Minidump(NEW)
jb, js, _ = md.find_module('jvm.dll')
img = bytearray(b'\0' * js)
for s, sz, fo in md.mem_regions:
    if s < jb + js and s + sz > jb:
        lo = max(s, jb) - jb
        hi = min(s + sz, jb + js) - jb
        md.f.seek(fo + (jb + lo - s))
        img[lo:hi] = md.f.read(hi - lo)
img = bytes(img)

e = struct.unpack_from('<I', img, 0x3C)[0]
nsec = struct.unpack_from('<H', img, e + 6)[0]
opt = struct.unpack_from('<H', img, e + 20)[0]
sec0 = e + 24 + opt
secs = []
for i in range(nsec):
    off = sec0 + 40 * i
    name = img[off:off + 8].rstrip(b'\0').decode(errors='replace')
    vsz, vaddr = struct.unpack_from('<II', img, off + 8)
    chars = struct.unpack_from('<I', img, off + 36)[0]
    secs.append((name, vaddr, vsz, chars))

astr = md.find_module('Astraea.dll')
abase, asize = (astr[0], astr[1]) if astr else (0, 0)

def tag_of(v):
    if v == 0:
        return 'NULL'
    r = v - jb
    if 0 <= r < js:
        for nm, va, vsz, _ in secs:
            if va <= r < va + vsz:
                return f"jvm:{nm}+{r - va:#x}"
        return f"jvm:?{r:#x}"
    if abase and abase <= v < abase + asize:
        return f"Astraea+{v - abase:#x}"
    for b, sz, nm in md.modules:
        if b <= v < b + sz:
            return f"{nm.split(chr(92))[-1]}+{v - b:#x}"
    return f"ext:{v:#x}"

def profile(rva, n=233):
    q = np.frombuffer(img[rva:rva + n * 8], dtype='<u8')
    cnt = Counter(tag_of(int(v)) for v in q)
    return q, cnt

def refs_to(targets):
    fh = open(NEW, 'rb')
    mm = mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ)
    res = Counter()
    for (s, sz, fo) in md.mem_regions:
        if sz < 8:
            continue
        buf = np.frombuffer(mm[fo:fo + (sz & ~7)], dtype='<u8')
        for t in targets:
            n = int((buf == t).sum())
            if n:
                res[t] += n
    return res

CANDS = [0xE90000, 0xE90110, 0xE570B0, 0xE9F410, 0xE915C8]
for c in CANDS:
    q, cnt = profile(c)
    print(f"\n=== cand {c:#x} ===")
    for k, v in cnt.most_common(8):
        print(f"    {k}: {v}")
    for i in range(6):
        print(f"    [{i}] {int(q[i]):#018x} {tag_of(int(q[i]))}")

res = refs_to([jb + c for c in CANDS])
print("\nheap refs:")
for c in CANDS:
    print(f"    {c:#x}: {res[jb + c]}")
