#!/usr/bin/env python3
"""Проверка kill-механики: дизас 0x29C690 (путь из DEAD-ветки слота 69),
функциональный указатель из live[69] (определить какой), цели с разных
`call [rip+...]` внутри live[69], и quick-test перепутанных аргументов.
"""
import struct
import sys
sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme\tools")
from jc import meta, sections, img, live_table
from capstone import Cs, CS_ARCH_X86, CS_MODE_64

BASE = meta('new')['base']
IM = img('new')
SEC = sections('new')
lt = live_table('new', 240)
md = Cs(CS_ARCH_X86, CS_MODE_64)

def sec_of(rva):
    for s in SEC:
        if s['vaddr'] <= rva < s['vaddr'] + s['vsize']:
            return s['name']
    return '?'

def disas(rva, n, label):
    code = bytes(IM[rva:rva + n])
    print("=== %s @ %#x %s ===" % (label, rva, sec_of(rva)))
    for ins in md.disasm(code, rva):
        note = ''
        if ins.mnemonic in ('call', 'jmp') and ins.op_str.startswith('0x'):
            try:
                t = int(ins.op_str, 16)
                note = "  ; -> %#x %s" % (t, sec_of(t))
            except ValueError:
                pass
        # rip-rel resolve
        elif 'rip +' in ins.op_str or 'rip -' in ins.op_str:
            try:
                off = ins.op_str.split('rip ')[1].strip(' +[]')
                sign = 1
                if off.startswith('-'):
                    sign = -1
                    off = off[1:]
                v = int(off, 16) * sign
                tgt = ins.address + ins.size + v
                note = "  ; => %#x %s" % (tgt, sec_of(tgt))
            except Exception:
                pass
        print("  %06x  %-7s %-32s%s" % (ins.address, ins.mnemonic, ins.op_str, note))
    print()

# 1. kill-функция из DEAD-ветки
disas(0x29c690, 0x80, "kill? via 0x29c910")

# 2. slot entry live[69]
print("live[69] = %#x (RVA %#x)\n" % (lt[69], lt[69] - BASE))

# 3. какие адреса в live-таблице выглядят как продвинутые указатели внутрь live[69]?
r69 = lt[69] - BASE
for s in range(240):
    r = lt[s] - BASE
    if 0x1000 < r < 0x21f0000 and r69 - 0x40 <= r < r69 + 0x440 and r != r69:
        print("slot %d points into slot69 body: %#x (+%#x)" % (s, r, r - r69))

# 4. перечислим все call [rip+X] внутри live[69] body и их целевые значения из дампа
print("\nindirect call targets inside live[69] (from dump .data):")
code = bytes(IM[r69:r69 + 0x420])
for ins in md.disasm(code, r69):
    if ins.mnemonic == 'call' and 'rip +' in ins.op_str:
        try:
            off = int(ins.op_str.split('rip +')[1].strip('[]'), 16)
            tgt = ins.address + ins.size + off
            val = struct.unpack_from('<Q', IM, tgt)[0]
            where = ('jvm.dll RVA %#x %s' % (val - BASE, sec_of(val - BASE))) if BASE <= val < BASE + 0x21f0000 else ('external/heap %#x' % val)
            print("  %#x: call [rip+%#x] => ptr %#x (%s)" % (ins.address, off, val, where))
        except Exception:
            pass
