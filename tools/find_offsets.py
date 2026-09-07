#!/usr/bin/env python3
"""Find JNI RVAs (JavaVM*, invoke vtable, JNIEnv native table(s)) inside a
minidump of the running game, by pattern-scanning the mapped jvm.dll image."""
import struct
import sys
import mmap
import numpy as np

sys.path.insert(0, __file__.rsplit('\\', 1)[0] if '\\' in __file__ else '.')
from mdump import Minidump

DUMP = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"

md = Minidump(DUMP)
jbase, jsize, jname = md.find_module('jvm.dll')
print(f"jvm.dll base={jbase:#x} size={jsize:#x}")

img = md.read(jbase, jsize)
if img is None:
    # gather piecewise: fill unmapped holes with zeros
    img = bytearray(b'\0' * jsize)
    for s, sz, fo in md.mem_regions:
        if s < jbase + jsize and s + sz > jbase:
            lo = max(s, jbase) - jbase
            hi = min(s + sz, jbase + jsize) - jbase
            md.f.seek(fo + (jbase + lo - s))
            img[lo:hi] = md.f.read(hi - lo)
img = bytes(img)
print(f"image gathered: {len(img):#x} bytes")

# ---- PE sections ----
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
    print(f"  sec {name:<8} rva={vaddr:#010x} size={vsz:#x}")

text = next(s for s in sections if s[0] == '.text')
text_lo, text_hi = text[1], text[1] + text[2]
astr = md.find_module('Astraea.dll')
astr_base, astr_size = (astr[0], astr[1]) if astr else (0, 0)

q = np.frombuffer(img[:len(img) & ~7], dtype='<u8')
rva = (q - jbase).astype(np.int64)  # negative => outside module
is_text = (rva >= text_lo) & (rva < text_hi)
is_zero = q == 0

def rva_of(i):
    return int(rva[i]) if q[i] else 0

def target_desc(v):
    if v == 0:
        return "NULL"
    a = int(v)
    if jbase <= a < jbase + jsize:
        return f"jvm+rva {a - jbase:#x}"
    if astr_base <= a < astr_base + astr_size:
        return f"Astraea+rva {a - astr_base:#x}"
    for b, sz, nm in md.modules:
        if b <= a < b + sz:
            return f"{nm.split(chr(92))[-1]}+{a - b:#x}"
    return f"outside({a:#x})"

# ---- 1. invoke table: NULL,NULL,NULL then 5 text pointers ----
print("\n=== invoke table candidates (3 NULLs + 5 .text ptrs) ===")
z = is_zero.copy()
pat = z[:-7] & z[1:-6] & z[2:-5] & is_text[3:-4] & is_text[4:-3] & is_text[5:-2] & is_text[6:-1] & is_text[7:]
idx = np.flatnonzero(pat)
invoke_cands = []
for i in idx:
    # require previous qword NOT a text pointer (avoid landing mid-table)
    if i > 0 and is_text[i - 1]:
        continue
    invoke_cands.append(int(i))
    print(f"  rva {i*8:#x}: " + ", ".join(target_desc(int(v)) for v in q[i:i + 8]))

# ---- 2. main_vm: absolute refs to invoke table in image ----
print("\n=== refs to invoke table inside jvm.dll image ===")
for i in invoke_cands:
    tgt = jbase + i * 8
    hits = np.flatnonzero(q == tgt)
    for h in hits:
        print(f"  qword at jvm rva {h*8:#x} == invoke table rva {i*8:#x}")

# ---- 3. native interface tables: runs of >=220 plausible entries ----
print("\n=== native (JNIEnv) table candidates ===")
plausible = is_zero | is_text
# also allow entries pointing into Astraea.dll
ra = q.astype(np.int64) - astr_base
is_astr = (ra >= 0) & (ra < astr_size)
plausible = plausible | is_astr

runs = []
n = len(plausible)
i = 0
pmask = plausible
# find run boundaries via diff
d = np.diff(pmask.astype(np.int8))
starts = np.flatnonzero(d == 1) + 1
ends = np.flatnonzero(d == -1) + 1
if pmask[0]:
    starts = np.r_[0, starts]
if pmask[-1]:
    ends = np.r_[ends, n]
for s, e in zip(starts, ends):
    if e - s >= 220:
        runs.append((int(s), int(e)))

tables = []
for s, e in runs:
    seg = q[s:e]
    nz = int((seg == 0).sum())
    ntext = int(is_text[s:e].sum())
    nastr = int(is_astr[s:e].sum())
    nother = (e - s) - nz - ntext - nastr
    tables.append((s, e))
    print(f"  rva {s*8:#x}..{e*8:#x} len={e-s} nulls={nz} text={ntext} astr={nastr} other={nother}")
    if nother:
        # show the odd entries
        odd = [j for j in range(e - s)
               if seg[j] != 0 and not is_text[s + j] and not is_astr[s + j]][:12]
        for j in odd:
            print(f"      [{j}] {target_desc(int(seg[j]))}")

# ---- 4. who references each native table (absolute pointers, whole dump) ----
print("\n=== scanning whole dump for absolute refs to candidate tables ===")
targets = {}
for s, e in tables:
    targets[jbase + s * 8] = f"native@{s*8:#x}"
for i in invoke_cands:
    targets[jbase + i * 8] = f"invoke@{i*8:#x}"

fh = open(DUMP, 'rb')
mm = mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ)
for tval, tname in targets.items():
    hits_out = 0
    hits_in = 0
    examples = []
    for (s, sz, fo) in md.mem_regions:
        if sz < 8:
            continue
        buf = np.frombuffer(mm[fo:fo + (sz & ~7)], dtype='<u8')
        hit_idx = np.flatnonzero(buf == tval)
        if len(hit_idx) == 0:
            continue
        in_jvm = jbase <= s < jbase + jsize
        for hi in hit_idx[:5]:
            if len(examples) < 6:
                examples.append(f"{s + int(hi)*8:#x}{'(jvm)' if in_jvm else ''}")
        if in_jvm:
            hits_in += len(hit_idx)
        else:
            hits_out += len(hit_idx)
    print(f"  {tname} {tval:#x}: refs_in_jvm={hits_in} refs_outside={hits_out} ex={examples}")
