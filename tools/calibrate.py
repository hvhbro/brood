#!/usr/bin/env python3
"""Calibrate discovery method against the OLD dump with known offsets."""
import struct
import sys
import mmap
import numpy as np

sys.path.insert(0, '.')
from mdump import Minidump

def analyze(dump_path, known=None, label=''):
    md = Minidump(dump_path)
    jbase, jsize, _ = md.find_module('jvm.dll')
    print(f"\n########## {label}: jvm.dll base={jbase:#x} size={jsize:#x}")

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
        print(f"  sec {name:<8} rva={vaddr:#010x} size={vsz:#x} exec={'Y' if chars & 0x20000000 else '-'}")

    def sec_of(rva):
        for name, va, vsz, _ in sections:
            if va <= rva < va + vsz:
                return name
        return '?'

    exec_ranges = [(va, va + vsz) for _, va, vsz, ch in sections if ch & 0x20000000]

    q = np.frombuffer(img[:len(img) & ~7], dtype='<u8')
    rva_arr = (q - jbase).astype(np.int64)
    is_exec = np.zeros(len(q), dtype=bool)
    for lo, hi in exec_ranges:
        is_exec |= (rva_arr >= lo) & (rva_arr < hi)
    is_zero = q == 0

    # ---- invoke table: 3 NULLs + 5 exec ptrs ----
    pat = is_zero[:-7] & is_zero[1:-6] & is_zero[2:-5] & is_exec[3:-4] & is_exec[4:-3] \
        & is_exec[5:-2] & is_exec[6:-1] & is_exec[7:]
    invoke_cands = []
    for i in np.flatnonzero(pat):
        i = int(i)
        if i > 0 and is_exec[i - 1]:
            continue
        invoke_cands.append(i)
    print(f"  invoke candidates: {[hex(i*8) for i in invoke_cands]}")

    # ---- main_vm: qwords in image == &invoke_table ----
    main_vm_cands = []
    for iv in invoke_cands:
        hits = np.flatnonzero(q == jbase + iv * 8)
        for h in hits:
            h = int(h)
            nxt = q[h + 1:h + 4]
            nulls = int((nxt == 0).sum())
            main_vm_cands.append((h, iv, nulls))
            print(f"  main_vm cand @ rva {h*8:#x} (sec {sec_of(h*8)}) -> invoke {iv*8:#x}, "
                  f"next3 nulls={nulls}/3")

    # ---- env tables: 4 NULLs immediately before a run of >=225 exec|zero ----
    runmask = is_exec | is_zero
    d = np.diff(runmask.astype(np.int8))
    starts = np.flatnonzero(d == 1) + 1
    ends = np.flatnonzero(d == -1) + 1
    if runmask[0]:
        starts = np.r_[0, starts]
    if runmask[-1]:
        ends = np.r_[ends, len(runmask)]
    env_cands = []
    for s, e in zip(starts, ends):
        L = e - s
        if L >= 225 and s >= 4 and (q[s - 4:s] == 0).all():
            env_cands.append(int(s))
            print(f"  env4null cand rva {s*8:#x} sec={sec_of(s*8)} len={L} "
                  f"nulls_inside={int((q[s:e]==0).sum())}")

    # ---- general exec|zero runs >= 225 (any start) for cross-check ----
    print("  --- all exec|zero runs >= 229 in image ---")
    for s, e in zip(starts, ends):
        L = e - s
        if L >= 229:
            print(f"    rva {s*8:#x}..{e*8:#x} len={L} sec={sec_of(s*8)} "
                  f"nulls={int((q[s:e]==0).sum())}")

    # ---- refs across whole dump ----
    fh = open(dump_path, 'rb')
    mm = mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ)
    tset = {}
    for iv in invoke_cands:
        tset[jbase + iv * 8] = f'invoke@{iv*8:#x}'
    for mvm, iv, _ in main_vm_cands:
        tset[jbase + mvm * 8] = f'main_vm@{mvm*8:#x}'
    for s in env_cands:
        tset[jbase + s * 8] = f'env4null@{s*8:#x}'
    if known:
        for k, r in known.items():
            tset.setdefault(jbase + r, f'KNOWN_{k}@{r:#x}')

    print("  --- refs across whole dump ---")
    for tval, tname in sorted(tset.items(), key=lambda kv: kv[1]):
        hin = hout = 0
        ex = []
        for (s, sz, fo) in md.mem_regions:
            if sz < 8:
                continue
            buf = np.frombuffer(mm[fo:fo + (sz & ~7)], dtype='<u8')
            hidx = np.flatnonzero(buf == tval)
            if len(hidx) == 0:
                continue
            if jbase <= s < jbase + jsize:
                hin += len(hidx)
            else:
                hout += len(hidx)
                for hi in hidx[:2]:
                    if len(ex) < 2:
                        ex.append(s + int(hi) * 8)
        print(f"    {tname}: in_jvm={hin} outside={hout} ex={[hex(e) for e in ex]}")

    # ---- RIP-relative refs from .text (any byte alignment for disp32) ----
    text = next(s for s in sections if s[0] == '.text')
    tlo, thi = text[1], text[1] + text[2]
    tb = np.frombuffer(img, np.uint8)[tlo:thi]
    sw = np.lib.stride_tricks.sliding_window_view(tb, 4)
    u = (sw[:, 0].astype(np.uint32) | (sw[:, 1].astype(np.uint32) << 8)
         | (sw[:, 2].astype(np.uint32) << 16) | (sw[:, 3].astype(np.uint32) << 24))
    disp = u.view(np.int32).astype(np.int64)
    pos = np.arange(len(disp), dtype=np.int64) + tlo + 4
    targets = pos + disp

    print("  --- RIP-relative refs from .text ---")
    check = {}
    if known:
        check.update({f'KNOWN_{k}@{v:#x}': v for k, v in known.items()})
    for iv in invoke_cands:
        check[f'invoke@{iv*8:#x}'] = iv * 8
    for s in env_cands:
        check[f'env4null@{s*8:#x}'] = s * 8
    for mvm, iv, _ in main_vm_cands:
        check[f'main_vm@{mvm*8:#x}'] = mvm * 8
    for nm, r in sorted(check.items()):
        hit = np.flatnonzero(targets == r)
        ex = [hex(int(x) + tlo) for x in hit[:6]]
        print(f"    {nm}: rip_refs={len(hit)} at={ex}")

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
    analyze(OLD, KNOWN, 'OLD dump (ground truth)')
else:
    analyze(NEW, None, 'NEW dump')
