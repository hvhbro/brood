#!/usr/bin/env python3
"""Shared helper: load cached images, tables; raw-dump reads for heap."""
import os
import sys
import json
import struct
import numpy as np

TOOLS = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(TOOLS, 'cache')
sys.path.insert(0, TOOLS)

OLD_DUMP = r"D:\1proekts\rustme\rustme_19600_1782646686.dmp"
NEW_DUMP = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"

_md_cache = {}


def meta(tag):
    with open(os.path.join(CACHE, f'{tag}_meta.json')) as f:
        return json.load(f)


def img(tag):
    p = os.path.join(CACHE, f'{tag}_img.bin')
    if not hasattr(img, '_c'):
        img._c = {}
    if tag not in img._c:
        with open(p, 'rb') as f:
            img._c[tag] = f.read()
    return img._c[tag]


def sections(tag):
    with open(os.path.join(CACHE, f'{tag}_sections.json')) as f:
        return json.load(f)


def qwords(tag, rva, n):
    """n qwords from image at rva."""
    im = img(tag)
    off = rva
    return [int.from_bytes(im[off + k * 8:off + k * 8 + 8], 'little') for k in range(n)]


def minidump(tag):
    if tag not in _md_cache:
        from mdump import Minidump
        _md_cache[tag] = Minidump(OLD_DUMP if tag == 'old' else NEW_DUMP)
    return _md_cache[tag]


def read_va(tag, va, n):
    """Read arbitrary memory (heap etc.) from the original dump."""
    md = minidump(tag)
    return md.read(va, n)


def live_table(tag, n=240):
    return qwords(tag, meta(tag)['live_rva'], n)


def pristine_table(tag, n=240):
    return qwords(tag, meta(tag)['pristine_rva'], n)


if __name__ == '__main__':
    for tag in ('old', 'new'):
        m = meta(tag)
        print(f"{tag}: base={m['base']:#x} live@{m['live_rva']:#x} pristine@{m['pristine_rva']:#x}")
        lt = live_table(tag, 10)
        print("  live[0..9]:", [hex(x) for x in lt])
