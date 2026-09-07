#!/usr/bin/env python3
"""Find live env table via JavaThread signature:
qword[site] = table ptr, qword[site-0x20..site-0x8] = 4 ascending ptrs."""
import sys
import mmap
import numpy as np
from collections import Counter

sys.path.insert(0, '.')
from mdump import Minidump

OLD = r"D:\1proekts\rustme\rustme_19600_1782646686.dmp"
NEW = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"

def scan(path, jvm_data_range, label):
    md = Minidump(path)
    jb = md.find_module('jvm.dll')[0]
    fh = open(path, 'rb')
    mm = mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ)
    lo, hi = jvm_data_range
    hist = Counter()
    for (s, sz, fo) in md.mem_regions:
        if sz < 0x30:
            continue
        buf = np.frombuffer(mm[fo:fo + (sz & ~7)], dtype='<u8')
        # candidate sites: qword at i points into jvm .data
        m = (buf >= jb + lo) & (buf < jb + hi)
        idx = np.flatnonzero(m)
        if len(idx) == 0:
            continue
        # exclude sites inside jvm image
        ok = ~((s + idx * 8 >= jb) & (s + idx * 8 < jb + 0x21f0000))
        idx = idx[ok]
        if len(idx) == 0:
            continue
        # need idx >= 4 (site-0x20)
        idx = idx[idx >= 4]
        if len(idx) == 0:
            continue
        a = buf[idx - 4].astype(np.uint64)
        b = buf[idx - 3].astype(np.uint64)
        c = buf[idx - 2].astype(np.uint64)
        d = buf[idx - 1].astype(np.uint64)
        sig = (b == a + 8) & (c == a + 0x10) & (d == a + 0x18) & (a > 0x10000)
        for i in idx[sig]:
            hist[int(buf[i])] += 1
    print(f"=== {label} ===")
    for t, cnt in hist.most_common(6):
        print(f"  target {t:#x} (jvm rva {t - jb:#x}): {cnt} JavaThread-like sites")
    return hist

scan(OLD, (0xDD0000, 0xE74E50), 'OLD (expect 0xDEC0F0 on top)')
scan(NEW, (0xE50000, 0xEF5800), 'NEW')
