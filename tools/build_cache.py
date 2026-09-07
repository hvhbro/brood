#!/usr/bin/env python3
"""One-time cache: extract jvm.dll images + tables from both dumps to tools/cache/."""
import os
import sys
import struct
import json
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mdump import Minidump

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'cache')
os.makedirs(CACHE, exist_ok=True)

DUMPS = {
    'old': r"D:\1proekts\rustme\rustme_19600_1782646686.dmp",
    'new': r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp",
}
LIVE = {'old': 0xDEC0F0, 'new': 0xE6CAF0}
PRISTINE = {'old': 0xB628F8, 'new': 0xBD2898}

for tag, path in DUMPS.items():
    out_img = os.path.join(CACHE, f'{tag}_img.bin')
    if os.path.exists(out_img):
        print(f"{tag}: cached, skip")
        continue
    md = Minidump(path)
    jb, jsz, name = md.find_module('jvm.dll')
    print(f"{tag}: jvm.dll base={jb:#x} size={jsz:#x}")
    img = bytearray(b'\0' * jsz)
    for s, sz, fo in md.mem_regions:
        if s < jb + jsz and s + sz > jb:
            lo = max(s, jb) - jb
            hi = min(s + sz, jb + jsz) - jb
            md.f.seek(fo + (jb + lo - s))
            img[lo:hi] = md.f.read(hi - lo)
    with open(out_img, 'wb') as f:
        f.write(img)
    # dump full memory around live table from raw dump (table is in .data, in image already)
    meta = {'base': jb, 'size': jsz, 'name': name, 'live_rva': LIVE[tag], 'pristine_rva': PRISTINE[tag]}
    with open(os.path.join(CACHE, f'{tag}_meta.json'), 'w') as f:
        json.dump(meta, f, indent=1)
    # PE sections
    e = struct.unpack_from('<I', img, 0x3C)[0]
    nsec = struct.unpack_from('<H', img, e + 6)[0]
    opt = struct.unpack_from('<H', img, e + 20)[0]
    sec0 = e + 24 + opt
    secs = []
    for i in range(nsec):
        off = sec0 + 40 * i
        nm = img[off:off + 8].rstrip(b'\0').decode(errors='replace')
        vsz, vaddr = struct.unpack_from('<II', img, off + 8)
        chars = struct.unpack_from('<I', img, off + 36)[0]
        secs.append({'name': nm, 'vaddr': vaddr, 'vsize': vsz, 'chars': chars})
    with open(os.path.join(CACHE, f'{tag}_sections.json'), 'w') as f:
        json.dump(secs, f, indent=1)
    print(f"{tag}: image {jsz:#x} bytes cached, {len(secs)} sections")

print("done")
