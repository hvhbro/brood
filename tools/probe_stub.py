#!/usr/bin/env python3
"""Disambiguate live env table: read trampoline bytes, count threads,
inspect JNIEnv ref sites (JavaThread context)."""
import struct
import sys
import numpy as np

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

def dump_mem(va, n=48, label=''):
    b = md.read(va, n)
    print(f"  {label} @ {va:#x}:")
    if b is None:
        print("    <unmapped>")
        return
    for i in range(0, n, 16):
        print("    " + " ".join(f"{x:02x}" for x in b[i:i+16]))

# threads count
nthreads = 0
if 3 in md.streams:  # ThreadListStream
    srva, sz = md.streams[3][0]
    md.f.seek(srva)
    (nt,) = struct.unpack('<I', md.f.read(4))
    nthreads = nt
print(f"threads in dump: {nthreads}")

print("\n--- trampoline bytes (targets of table 0xE90000) ---")
imgq = np.frombuffer(img, dtype='<u8')
base_stub = int(imgq[0xE90000 // 8])
print(f"  table[0] -> {base_stub:#x}")
for k in [0, 1, 2]:
    dump_mem(base_stub + k * 0x20, 32, f"stub[{k}]")

print("\n--- trampoline bytes (targets of table 0xE90110) ---")
base2 = int(imgq[0xE90110 // 8])
print(f"  table[4-slot0] -> {base2:#x}")
dump_mem(base2, 32, "stub2[0]")

print("\n--- which region is the stub in? ---")
for s, sz, fo in md.mem_regions:
    if s <= base_stub < s + sz:
        print(f"  stub region: {s:#x}..{s+sz:#x} size={sz:#x}")
        break

print("\n--- ref sites: memory around a JNIEnv pointing to 0xE90000 / 0xE90110 ---")
def find_refs(target, limit=3):
    out = []
    for (s, sz, fo) in md.mem_regions:
        if sz < 8:
            continue
        buf = np.frombuffer(md.f_segment_view if False else b'', dtype='<u8')  # placeholder
        break
    return out

fh = open(NEW, 'rb')
import mmap
mm = mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ)
def find_refs(target, limit=4):
    out = []
    for (s, sz, fo) in md.mem_regions:
        if sz < 8:
            continue
        buf = np.frombuffer(mm[fo:fo + (sz & ~7)], dtype='<u8')
        hidx = np.flatnonzero(buf == target)
        for hi in hidx[:limit]:
            out.append(s + int(hi) * 8)
        if len(out) >= limit:
            break
    return out

for tgt, nm in [(jb + 0xE90000, '0xE90000'), (jb + 0xE90110, '0xE90110'),
                (jb + 0xBD2898, '0xBD2898 (.rdata pristine)')]:
    sites = find_refs(tgt)
    print(f"  refs to {nm}: {len(sites)} site(s) {[hex(x) for x in sites[:4]]}")
    for site in sites[:2]:
        dump_mem(site - 0x40, 0x60 + 0x40, f"context around ref {site:#x}")
        print()
        break
