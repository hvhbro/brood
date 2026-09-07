#!/usr/bin/env python3
"""Определить, что делает live слот 69 в порядке вызовов после эпилога
(0x3f5f1d..0x3f5fc9 уже видели). Цель: найти kill/guard.
Также: полная карта внешних call-целей из live[69] и их принадлежность
(тот же interceptor-регион или 'настоящий' .text).

Гипотезы проверки:
1. 0x14ed00 (назначение? ThreadStateTransition?)
2. ветка magic 0xDEAD (0x3f5fca): 0x14eb90 -> ? ; 0x5457a0 -> ?; 0x29c910 -> ?
3. 0x417b70 и [rip+0xa87ff4] = fnptr @0xE7DEFA — что там в дампе (значение)?
4. Наоборот: возьмём ПАРУ (перестановку) в live-таблице как признак
   'одинаковой функции' и сравним live[69] с live-целями других слотов
   (не может ли 69 дублировать чужую функцию?)
"""
import struct
import sys
sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme\tools")
from jc import meta, sections, img, live_table, read_va
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

def calls_in(rva, length=0x420):
    code = bytes(IM[rva:rva + length])
    out = []
    for ins in md.disasm(code, rva):
        if ins.mnemonic == 'call' and ins.op_str.startswith('0x'):
            out.append(int(ins.op_str, 16))
    return out

# 1. где лежат цели вызовов live[69]
r69 = lt[69] - BASE
cs = calls_in(r69)
print("live[69] RVA %#x, calls:" % r69)
for c in sorted(set(cs)):
    # в interceptor-регионе?
    inter = sum(1 for s in range(240)
                if 0x1000 < lt[s] - BASE < 0x21f0000
                and abs(lt[s] - BASE - c) < 0x4000)
    print("  -> %#x %s (interceptor-region: %s)" % (c, sec_of(c), bool(inter)))

# 2. значения fnptr @0xE7DEFA из ДАМПА (heap read, т.к. .data в image уже применена)
val = struct.unpack_from('<Q', IM, 0xE7DEFA)[0]
print("\nfnptr @0xE7DEFA = %#x" % val)
if val > BASE:
    r = val - BASE
    print("  -> jvm.dll RVA %#x %s" % (r, sec_of(r)))
    code = bytes(IM[r:r + 64])
    for ins in list(md.disasm(code, r))[:10]:
        print("    %06x %-7s %s" % (ins.address, ins.mnemonic, ins.op_str))

# 3. kill-ветка magic 0xDEAD: 0x14eb90 / 0x5457a0 / 0x29c910
for t in (0x14eb90, 0x5457a0, 0x29c910):
    print("\ncalled in DEAD-branch: %#x %s:" % (t, sec_of(t)))
    code = bytes(IM[t:t + 80])
    for ins in list(md.disasm(code, t))[:14]:
        print("    %06x %-7s %s" % (ins.address, ins.mnemonic, ins.op_str))

# 4. 0x14ed00 и 0x417b70
for t in (0x14ed00, 0x417b70, 0x3204e0):
    print("\nhelper %#x %s:" % (t, sec_of(t)))
    code = bytes(IM[t:t + 60])
    for ins in list(md.disasm(code, t))[:12]:
        print("    %06x %-7s %s" % (ins.address, ins.mnemonic, ins.op_str))
