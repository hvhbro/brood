#!/usr/bin/env python3
"""Find the live JNIEnv table in new .data: 4 leading NULLs + jni-region text ptrs,
then verify with heap reference counts (old live table had 33 heap refs)."""
import struct
import sys
import mmap
import numpy as np
from collections import Counter

sys.path.insert(0, '.')
from mdump import Minidump

NEW = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"
OLD = r"D:\1proekts\rustme\rustme_19600_1782646686.dmp"

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

def refs_to(dump_path, md, jbase, jsize, targets):
    fh = open(dump_path, 'rb')
    mm = mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ)
    res = {t: 0 for t in targets}
    sites = {t: [] for t in targets}
    for (s, sz, fo) in md.mem_regions:
        if sz < 8:
            continue
        buf = np.frombuffer(mm[fo:fo + (sz & ~7)], dtype='<u8')
        for t in targets:
            hidx = np.flatnonzero(buf == t)
            if len(hidx):
                res[t] += len(hidx)
                sites[t].append(s + int(hidx[0]) * 8)
    return res, sites

for label, path, known_live in [('OLD', OLD, 0xDEC0F0), ('NEW', NEW, None)]:
    md, jb, js, img = load(path)
    secs = sections(img)
    execs = [(va, va + vsz) for nm, va, vsz, ch in secs if ch & 0x20000000]
    text = next((va, va + vsz) for nm, va, vsz, ch in secs if nm == '.text')
    data = next((va, va + vsz) for nm, va, vsz, ch in secs if nm == '.data')
    astr = md.find_module('Astraea.dll')
    abase, asize = (astr[0], astr[1]) if astr else (0, 0)
    q = np.frombuffer(img, dtype='<u8')

    print(f"\n##### {label}: scanning .data {data[0]:#x}..{data[1]:#x} for live table")
    cands = []
    for off in range(data[0] // 8, data[1] // 8 - 233):
        if q[off] or q[off+1] or q[off+2] or q[off+3]:
            continue
        seg = q[off+4:off+233]
        r = (seg - jb).astype(np.int64)
        t = int(((r >= text[0]) & (r < text[1])).sum())
        a = 0
        if abase:
            ra = seg - abase
            a = int(((ra >= 0) & (ra < asize)).sum())
        if t + a >= 215:
            cands.append((off * 8, t, a))
    for rva, t, a in cands:
        print(f"  cand rva {rva:#x}: text={t} astraea={a}")

    # heap refs for candidates (plus known live for old)
    tgts = [jb + r for r, _, _ in cands]
    if known_live:
        tgts.append(jb + known_live)
    res, sites = refs_to(path, md, jb, js, tgts)
    for rva, _, _ in cands:
        print(f"    rva {rva:#x}: heap_refs={res[jb+rva]} first={[hex(x) for x in sites[jb+rva][:3]]}")
    if known_live:
        print(f"    KNOWN live {known_live:#x}: heap_refs={res[jb+known_live]} "
              f"first={[hex(x) for x in sites[jb+known_live][:3]]}")

    if cands:
        # show layout of the best candidate (max text)
        best = max(cands, key=lambda c: c[1])
        rva = best[0]
        seg = np.frombuffer(img[rva:rva + 14 * 8], dtype='<u8')
        print(f"  layout of best cand {rva:#x}:")
        for i in range(14):
            v = int(seg[i])
            tag = ''
            if v:
                rr = v - jb
                for nm, va, vsz, _ in secs:
                    if va <= rr < va + vsz:
                        tag = f"{nm}+{rr - va:#x}"
                        break
                else:
                    if abase and abase <= v < abase + asize:
                        tag = f"Astraea+{v - abase:#x}"
                    else:
                        tag = f"ext {v:#x}"
            print(f"    [{i:3d}] {v:#018x} {tag}")
