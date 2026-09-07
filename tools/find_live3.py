#!/usr/bin/env python3
"""Find live env table via JavaThread JNIEnv signature. Two heuristics:
A) qword[site-8..site-5 qwords] = 4 ascending ptrs (p,p+8,p+0x10,p+0x18)
B) qword[site+1..site+3] == 0,0,0 (3 trailing NULLs)
Validate on OLD (expect 0xDEC0F0), apply to NEW."""
import sys
import mmap
import numpy as np
from collections import Counter

sys.path.insert(0, '.')
from mdump import Minidump

OLD = r"D:\1proekts\rustme\rustme_19600_1782646686.dmp"
NEW = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"

def scan(path, data_range, label):
    md = Minidump(path)
    jb, js = md.find_module('jvm.dll')[0], md.find_module('jvm.dll')[1]
    fh = open(path, 'rb')
    mm = mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ)
    lo, hi = data_range
    histA, histB = Counter(), Counter()
    for (s, sz, fo) in md.mem_regions:
        if sz < 0x50:
            continue
        buf = np.frombuffer(mm[fo:fo + (sz & ~7)], dtype='<u8')
        m = (buf >= jb + lo) & (buf < jb + hi)
        idx = np.flatnonzero(m)
        if len(idx) == 0:
            continue
        ok = ~((s + idx * 8 >= jb) & (s + idx * 8 < jb + js))
        idx = idx[ok]
        idx = idx[(idx >= 8) & (idx + 3 < len(buf))]
        if len(idx) == 0:
            continue
        a = buf[idx - 8]
        sigA = (buf[idx - 7] == a + 8) & (buf[idx - 6] == a + 0x10) & (buf[idx - 5] == a + 0x18) & (a > 0x10000)
        sigB = (buf[idx + 1] == 0) & (buf[idx + 2] == 0) & (buf[idx + 3] == 0)
        for i in idx[sigA]:
            histA[int(buf[i])] += 1
        for i in idx[sigB]:
            histB[int(buf[i])] += 1
    print(f"=== {label} ===")
    print("  heuristic A (ascending-4 before):")
    for t, c in histA.most_common(4):
        print(f"    rva {t - jb:#x}: {c}")
    print("  heuristic B (3 trailing NULLs):")
    for t, c in histB.most_common(6):
        print(f"    rva {t - jb:#x}: {c}")

scan(OLD, (0xDD0000, 0xE74E50), 'OLD (expect 0xDEC0F0)')
scan(NEW, (0xE50000, 0xEF5800), 'NEW')
