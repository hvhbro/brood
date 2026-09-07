#!/usr/bin/env python3
"""Найти rip-относительные ссылки .text -> строки:
   0xD2CD40 'jdk/internal/vm/options', 0xD2CD5E 'unsafeDefineClassCalls',
   0xD54585 'DefineClass', 0xd6a48a/0xd8b8d0/0xd99d9a 'defineClass'.
   Дизас контекста найденных ссылок.
"""
import struct
import sys
sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme\tools")
from jc import meta, sections, img
from capstone import Cs, CS_ARCH_X86, CS_MODE_64

BASE = meta('new')['base']
IM = img('new')
SEC = sections('new')
md = Cs(CS_ARCH_X86, CS_MODE_64)
text = next(s for s in SEC if s['name'] == '.text')
t0, tsz = text['vaddr'], text['vsize']

def sec_of(rva):
    for s in SEC:
        if s['vaddr'] <= rva < s['vaddr'] + s['vsize']:
            return s['name']
    return '?'

STRINGS = {
    0xD2CD40: 'jdk/internal/vm/options',
    0xD2CD5E: 'unsafeDefineClassCalls',
    0xD54585: 'DefineClass#2',
    0xD6A48A: 'defineClass#1',
    0xD8B8D0: 'defineClass#2',
    0xD99D9A: 'defineClass#3',
}

# контекст строк
for a, nm in STRINGS.items():
    d = bytes(IM[a - 64:a + 80])
    asc = ''.join(chr(b) if 32 <= b < 127 else '.' for b in d)
    print("%s @ %#x (%s):\n   %s\n" % (nm, a, sec_of(a), asc))

# поиск disp32
for target, nm in STRINGS.items():
    print("=== xrefs to %s @ %#x ===" % (nm, target))
    hits = []
    for off in range(t0, t0 + tsz - 4):
        v = struct.unpack_from('<i', IM, off)[0]
        if off + 4 + v == target:
            hits.append(off)
    # группируем: начало инструкции предположительно off-3..off-1; берем off-7 для дизаса
    shown = set()
    for off in hits:
        if any(abs(off - s) < 24 for s in shown):
            continue
        shown.add(off)
        start = max(off - 7, t0)
        code = bytes(IM[start:off + 8])
        ins_list = list(md.disasm(code, start))
        if ins_list:
            ins = ins_list[-2] if len(ins_list) > 1 else ins_list[-1]
            # найдем инструкцию, чей диапазон покрывает off
            for i, x in enumerate(ins_list):
                if x.address <= off < x.address + x.size:
                    ins = x
                    break
            print("  %#x: %-6s %s" % (off, ins.mnemonic, ins.op_str))
    print()
