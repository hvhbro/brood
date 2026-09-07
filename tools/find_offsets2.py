#!/usr/bin/env python3
"""Second pass: detailed inspection of JNI table candidates."""
import struct
import sys
import mmap
import numpy as np

sys.path.insert(0, '.')
from mdump import Minidump

DUMP = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"

md = Minidump(DUMP)
jbase, jsize, jname = md.find_module('jvm.dll')

img = md.read(jbase, jsize)
if img is None:
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
    sections.append((name, vaddr, vsz))
    print(f"sec {name:<8} rva={vaddr:#010x} size={vsz:#x}")

def sec_of(rva):
    for name, va, vsz in sections:
        if va <= rva < va + vsz:
            return name
    return '?'

text = next(s for s in sections if s[0] == '.text')
text_lo, text_hi = text[1], text[1] + text[2]
astr = md.find_module('Astraea.dll')
astr_base, astr_size = (astr[0], astr[1]) if astr else (0, 0)

q = np.frombuffer(img[:len(img) & ~7], dtype='<u8')
rva = (q - jbase).astype(np.int64)
is_text = (rva >= text_lo) & (rva < text_hi)
is_zero = q == 0
ra = q.astype(np.int64) - astr_base
is_astr = (ra >= 0) & (ra < astr_size)
is_img = (rva >= 0) & (rva < jsize)
is_heapish = (~is_img) & (q != 0)

def target_desc(v):
    a = int(v)
    if a == 0:
        return "NULL"
    if jbase <= a < jbase + jsize:
        return f"jvm:{a - jbase:#x}({sec_of(a - jbase)})"
    if astr_base <= a < astr_base + astr_size:
        return f"astr:{a - astr_base:#x}"
    for b, sz, nm in md.modules:
        if b <= a < b + sz:
            return f"{nm.split(chr(92))[-1]}+{a - b:#x}"
    return f"ext:{a:#x}"

# ---------- invoke tables ----------
print("\n=== invoke candidates (3 NULL + 5 text ptrs) ===")
z = is_zero.copy()
pat = z[:-7] & z[1:-6] & z[2:-5] & is_text[3:-4] & is_text[4:-3] & is_text[5:-2] & is_text[6:-1] & is_text[7:]
for i in np.flatnonzero(pat):
    i = int(i)
    if i > 0 and is_text[i - 1]:
        continue
    print(f"  rva {i*8:#x} sec={sec_of(i*8)}: " + " | ".join(target_desc(int(v)) for v in q[i:i+8]))

# ---------- strict native tables: 4 leading NULLs, then 229 entries, mostly text ----------
print("\n=== strict JNIEnv table candidates (233 entries) ===")
N = 233
cand = []
for i in range(0, len(q) - N):
    if not (is_zero[i] and is_zero[i+1] and is_zero[i+2] and is_zero[i+3]):
        continue
    seg = is_text[i+4:i+N]
    if seg.sum() >= 215:
        cand.append(i)
print(f"  found {len(cand)} strict candidates")
for i in cand:
    seg = q[i:i+N]
    nt = int(is_text[i:i+N].sum())
    nz = int(is_zero[i:i+N].sum())
    na = int(is_astr[i:i+N].sum())
    nh = int(is_heapish[i:i+N].sum())
    print(f"  rva {i*8:#x} sec={sec_of(i*8)} text={nt} null={nz} astr={na} ext={nh}")

# pairwise similarity of strict candidates
if len(cand) > 1:
    print("\n  pairwise entry diffs:")
    for a in range(len(cand)):
        for b in range(a+1, len(cand)):
            diff = int((q[cand[a]:cand[a]+N] != q[cand[b]:cand[b]+N]).sum())
            print(f"    {cand[a]*8:#x} vs {cand[b]*8:#x}: {diff} differing slots")

# ---------- main_vm shape: qword equal to an invoke table, look at following 5 qwords ----------
print("\n=== possible main_vm structs (ref site + next 5 qwords) ===")
invoke_rvas = []
pat2 = z[:-7] & z[1:-6] & z[2:-5] & is_text[3:-4] & is_text[4:-3] & is_text[5:-2] & is_text[6:-1] & is_text[7:]
for i in np.flatnonzero(pat2):
    i = int(i)
    if i > 0 and is_text[i - 1]:
        continue
    invoke_rvas.append(i)
for iv in invoke_rvas:
    tgt = jbase + iv * 8
    hits = np.flatnonzero(q == tgt)
    for h in hits:
        h = int(h)
        nxt = q[h+1:h+6]
        ntext = sum(1 for v in nxt if text_lo <= v - jbase < text_hi)
        print(f"  ref at jvm rva {h*8:#x} (sec={sec_of(h*8)}) -> invoke {iv*8:#x}; "
              f"next5 textptrs={ntext}")
        if ntext >= 3:
            print("      next5: " + " | ".join(target_desc(int(v)) for v in nxt))

# ---------- who references the strict native tables: whole dump scan ----------
print("\n=== refs to strict native tables across dump ===")
fh = open(DUMP, 'rb')
mm = mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ)
for i in cand:
    tval = jbase + i * 8
    hits_in = hits_out = 0
    ex = []
    for (s, sz, fo) in md.mem_regions:
        if sz < 8:
            continue
        buf = np.frombuffer(mm[fo:fo + (sz & ~7)], dtype='<u8')
        hidx = np.flatnonzero(buf == tval)
        if len(hidx) == 0:
            continue
        in_jvm = jbase <= s < jbase + jsize
        if in_jvm:
            hits_in += len(hidx)
        else:
            hits_out += len(hidx)
            for hi in hidx[:3]:
                if len(ex) < 3:
                    ex.append(s + int(hi) * 8)
    print(f"  native@{i*8:#x}: refs_in_jvm={hits_in} refs_outside={hits_out} ex={[hex(e) for e in ex]}")
