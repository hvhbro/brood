#!/usr/bin/env python3
"""Итоговый анализ DefineClass-блокировки:
1. 0x5457a0 => 0x545690 => 0x14FDC90 (.orn = закриптованная секция!) — кто
   проверяется; disas 0x14FDC90.
2. Полный дизас live[69] 0x3F5F1D..0x3F5FC9 — какие проверки дают возврат 0.
3. Направления: поиск в .orn строк-маркеров, сравнение live[69] между
   билдами (старый дамп), вывод.
"""
import struct
import sys
sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme\tools")
from jc import img, meta, sections, live_table
from capstone import Cs, CS_ARCH_X86, CS_MODE_64
from mdump import Minidump

IM = img('new')
SEC = sections('new')
BASE = meta('new')['base']
md = Cs(CS_ARCH_X86, CS_MODE_64)

def sec_of(rva):
    for s in SEC:
        if s['vaddr'] <= rva < s['vaddr'] + s['vsize']:
            return s['name']
    return '?'

def disas(rva, n, label, resolve=True):
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
        elif resolve and 'rip +' in ins.op_str:
            try:
                off = int(ins.op_str.split('rip +')[1].strip('[]'), 16)
                tgt = ins.address + ins.size + off
                note = "  ; => %#x %s" % (tgt, sec_of(tgt))
            except Exception:
                pass
        print("  %06x  %-7s %-32s%s" % (ins.address, ins.mnemonic, ins.op_str, note))
    print()

# 1. Хук-валидатор (в шифрованной секции)
disas(0x14fdc90, 0x60, "hook validator 0x14FDC90 (.orn)")

# 2. хвост live[69]
disas(0x3f5f1d, 0xB0, "live[69] tail")

# 3. старый дамп: слот-69 live-цель и её секция
mdo = Minidump(r"D:\1proekts\rustme\rustme_19600_1782646686.dmp")
b, sz, name = mdo.find_module('jvm.dll')
OLDLIVE = 0xDEC0F0
old69 = int.from_bytes(mdo.read(b + OLDLIVE + 8 * 69, 8)[0:8], 'little')
print("OLD: live[69] = %#x (RVA %#x, %s)" % (old69, old69 - b, sec_of(old69 - b) if b <= old69 < b + sz else 'outside jvm.dll'))
old_lt = [int.from_bytes(mdo.read(b + OLDLIVE + 8 * i, 8)[0:8] or b'\0' * 8, 'little') for i in range(240)]
heap_old = [i for i, v in enumerate(old_lt) if v and not (b <= v < b + sz)]
print("OLD: heap/misplaced live slots:", heap_old)
# читаем первый вызов старой live[69]
r = old69 - b
code = mdo.read(old69, 160)
print("OLD: live[69] prologue:")
if code:
    for ins in list(md.disasm(bytes(code), r))[:16]:
        print("  %06x %-7s %s" % (ins.address, ins.mnemonic, ins.op_str))
