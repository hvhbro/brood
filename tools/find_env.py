#!/usr/bin/env python3
"""Find JNIEnv tables by histogramming heap qwords that point into jvm.dll.
A JNIEnv struct starts with a pointer to the JNINativeInterface table, so the
live table gets many heap refs; the pristine static table gets few."""
import struct
import sys
import mmap
import numpy as np
from collections import Counter

sys.path.insert(0, '.')
from mdump import Minidump

def analyze(dump_path, known=None, label=''):
    md = Minidump(dump_path)
    jbase, jsize, _ = md.find_module('jvm.dll')
    print(f"\n##### {label}: jvm.dll base={jbase:#x} size={jsize:#x}")

    img = bytearray(b'\0' * jsize)
    for s, sz, fo in md.mem_regions:
        if s < jbase + jsize and s + sz > jbase:
            lo = max(s, jbase) - jbase
            hi = min(s + sz, jbase + jsize) - jbase
            md.f.seek(fo + (jbase + lo - s))
            img[lo:hi] = md.f.read(hi - lo)
    img = bytes(img)

    e_lfanew = struct.unpack_from('<I', img, 0x3C)[0]
    nsec = struct.unpack_from('<H', img, e_lfanew + 6)[0]
    opt_size = struct.unpack_from('<H', img, e_lfanew + 20)[0]
    sec0 = e_lfanew + 24 + opt_size
    sections = []
    for i in range(nsec):
        off = sec0 + 40 * i
        name = img[off:off + 8].rstrip(b'\0').decode(errors='replace')
        vsz, vaddr = struct.unpack_from('<II', img, off + 8)
        chars = struct.unpack_from('<I', img, off + 36)[0]
        sections.append((name, vaddr, vsz, chars))

    def sec_of(rva):
        for name, va, vsz, _ in sections:
            if va <= rva < va + vsz:
                return name
        return '?'

    exec_ranges = [(va, va + vsz) for _, va, vsz, ch in sections if ch & 0x20000000]
    rdata = next(((va, va + vsz) for n, va, vsz, _ in sections if n == '.rdata'), None)
    data = next(((va, va + vsz) for n, va, vsz, _ in sections if n == '.data'), None)

    fh = open(dump_path, 'rb')
    mm = mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ)

    # histogram of qwords pointing into jvm.dll image, EXCLUDING refs from within jvm image itself
    hist = Counter()
    refsites = {}
    for (s, sz, fo) in md.mem_regions:
        if sz < 8:
            continue
        buf = np.frombuffer(mm[fo:fo + (sz & ~7)], dtype='<u8')
        # targets within jvm.dll
        m = (buf >= jbase) & (buf < jbase + jsize)
        idx = np.flatnonzero(m)
        # exclude refs that live inside jvm image
        keep = ~((s + idx * 8 >= jbase) & (s + idx * 8 < jbase + jsize))
        idx = idx[keep]
        if len(idx) == 0:
            continue
        tgts, cnts = np.unique(buf[idx], return_counts=True)
        for t, c in zip(tgts, cnts):
            hist[int(t)] += int(c)
            refsites.setdefault(int(t), []).append(s + int(np.flatnonzero(buf[idx] == t)[0]) * 8)

    print("  top heap-referenced jvm.dll addresses (>=3 refs):")
    top = [(t, c) for t, c in hist.most_common(4000) if c >= 3]
    for t, c in top[:40]:
        r = t - jbase
        print(f"    rva {r:#x} sec={sec_of(r)} refs={c} first={hex(refsites[t][0])}")

    # validate table shape: at least 200 nonzero entries in 0x750 bytes
    def table_score(rva):
        seg = np.frombuffer(img[rva:rva + 233 * 8], dtype='<u8')
        inmod = (seg >= jbase) & (seg < jbase + jsize)
        nonzero = int((seg != 0).sum())
        text = 0
        for lo, hi in exec_ranges:
            text += int(((seg - jbase) >= lo & 0xFFFFFFFF if False else
                         (((seg - jbase) >= lo) & (((seg - jbase) < hi)))).sum())
        return nonzero, text

    print("\n  candidate JNIEnv tables (rva in .rdata/.data, refs>=2, >=200 nonzero of 233):")
    for t, c in top:
        r = t - jbase
        in_rd = rdata and rdata[0] <= r < rdata[1]
        in_dt = data and data[0] <= r < data[1]
        if not (in_rd or in_dt):
            continue
        nz, txt = table_score(r)
        if nz >= 200:
            print(f"    rva {r:#x} sec={sec_of(r)} refs={c} nonzero={nz}/233 text_ptrs={txt}")

    if known:
        print("\n  known offsets ref counts:")
        for k, r in known.items():
            t = jbase + r
            c = hist.get(t, 0)
            print(f"    {k} @ {r:#x}: heap_refs={c} sites={[hex(x) for x in refsites.get(t, [])[:4]]}")

OLD = r"D:\1proekts\rustme\rustme_19600_1782646686.dmp"
NEW = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"
KNOWN = {
    'kJavaVmRva': 0xDEBF68,
    'kInvokeTableRva': 0xBEBB10,
    'kNativeTableRva': 0xB628F8,
    'kObservedLiveEnvTableRva': 0xDEC0F0,
}

which = sys.argv[1] if len(sys.argv) > 1 else 'old'
if which == 'old':
    analyze(OLD, KNOWN, 'OLD dump')
else:
    analyze(NEW, None, 'NEW dump')
