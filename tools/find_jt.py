#!/usr/bin/env python3
"""Identify the JavaThread JNIEnv signature from old dump ref sites, then
apply it in the new dump to find the live env table."""
import struct
import sys
import mmap
import numpy as np
from collections import Counter

sys.path.insert(0, '.')
from mdump import Minidump

OLD = r"D:\1proekts\rustme\rustme_19600_1782646686.dmp"
NEW = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"

def open_mm(path):
    fh = open(path, 'rb')
    return fh, mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ)

def region_of(md, va):
    for s, sz, fo in md.mem_regions:
        if s <= va < s + sz:
            return (s, sz, fo)
    return None

def read_at(md, mm, va, n):
    r = region_of(md, va)
    if not r:
        return None
    s, sz, fo = r
    if va + n > s + sz:
        return None
    return mm[fo + (va - s): fo + (va - s) + n]

def hexdump(b, va):
    for i in range(0, len(b), 32):
        qwords = " ".join(f"{int.from_bytes(b[i+k:i+k+8],'little'):#018x}" for k in (0, 8, 16, 24) if i + k + 8 <= len(b))
        print(f"    {va+i:#x}: {qwords}")

fh_o, mm_o = open_mm(OLD)
print("=== OLD: ref sites of live table 0xDEC0F0 ===")
md_o = Minidump(OLD)
jb_o = md_o.find_module('jvm.dll')[0]
tgt = jb_o + 0xDEC0F0
sites = []
for (s, sz, fo) in md_o.mem_regions:
    if sz < 8:
        continue
    buf = np.frombuffer(mm_o[fo:fo + (sz & ~7)], dtype='<u8')
    hidx = np.flatnonzero(buf == tgt)
    for hi in hidx[:6]:
        sites.append(s + int(hi) * 8)
print(f"  {len(sites)} sample sites")
for site in sites[:4]:
    print(f"  site {site:#x}:")
    b = read_at(md_o, mm_o, site - 0x60, 0xE0)
    if b is not None:
        hexdump(b, site - 0x60)
    print()

fh_n, mm_n = open_mm(NEW)
print("=== NEW: sites where qword points into jvm .data/.rdata, >=8 refs, with JavaThread-like context ===")
md_n = Minidump(NEW)
jb_n = md_n.find_module('jvm.dll')[0]

# collect all heap->jvm.dll .data/.rdata refs and group by target
data_lo, data_hi = 0xE50000, 0xEF5800
rd_lo, rd_hi = 0xBD0000, 0xE46076
from collections import defaultdict
by_target = defaultdict(list)
for (s, sz, fo) in md_n.mem_regions:
    if sz < 8:
        continue
    buf = np.frombuffer(mm_n[fo:fo + (sz & ~7)], dtype='<u8')
    m = ((buf >= jb_n + data_lo) & (buf < jb_n + data_hi)) | ((buf >= jb_n + rd_lo) & (buf < jb_n + rd_hi))
    idx = np.flatnonzero(m)
    # exclude sites inside jvm image
    if len(idx) == 0:
        continue
    site_va = s + idx * 8
    keep = ~((site_va >= jb_n) & (site_va < jb_n + 0x21f0000))
    idx2 = idx[keep]
    if len(idx2) == 0:
        continue
    tg = buf[idx2]
    for t, sv in zip(tg, site_va[keep]):
        by_target[int(t)].append(int(sv))

print(f"  distinct targets: {len(by_target)}")
cands = [(t, v) for t, v in by_target.items() if len(v) >= 8]
print(f"  targets with >=8 refs: {len(cands)}")
for t, v in sorted(cands, key=lambda kv: -len(kv[1]))[:15]:
    rva = t - jb_n
    sec = '.data' if data_lo <= rva < data_hi else '.rdata'
    print(f"    rva {rva:#x} ({sec}): {len(v)} refs")

def jt_signature(md, mm, site, tval):
    """Check whether site looks like JavaThread::jni_environment: nearby qwords
    contain a stack pointer and code-cache pointers."""
    b = read_at(md, mm, site - 0x40, 0xC0)
    if b is None:
        return False, None
    q = np.frombuffer(b, dtype='<u8')
    ptrs = q[(q > 0x10000) & (q < 0x7fffffffffff)]
    n_ptrs = len(ptrs)
    # count distinct high regions
    regions = set()
    for p in ptrs:
        r = region_of(md, int(p))
        if r:
            regions.add(r[0])
    return (n_ptrs >= 10 and len(regions) >= 3), (n_ptrs, len(regions))
