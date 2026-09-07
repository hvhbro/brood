#!/usr/bin/env python3
"""1) Поиск ссылок .text -> magic-хендл 0xDEAD/0xDEAE (кто его ставит?):
   иммедиаты 0xDEAD, 0xDEAE; и функции, пишущие [X+0xB8].
2) NPE-путь в live[69]: 0x3f5fca -> 0x14eb90 (set state) -> 0x5457a0
   (vmcaller 'must be in vm'?) -> 0x29c910(rcx="Unknown exception").
   Дизас 0x5457a0 глубже и посмотреть call [rip+...] в нём.
3) Сравнение: заменить ли 69 на реальный DefineClass? Смотрим старый дамп
   live-таблицы: кол-во heap-записей, сравнение multiset'ов между билдами.
"""
import struct
import sys
sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme\tools")
from jc import meta, sections, img, live_table, pristine_table
from capstone import Cs, CS_ARCH_X86, CS_MODE_64

BASE = meta('new')['base']
IM = img('new')
SEC = sections('new')
lt = live_table('new', 240)
md = Cs(CS_ARCH_X86, CS_MODE_64)
text = next(s for s in SEC if s['name'] == '.text')
t0, tsz = text['vaddr'], text['vsize']

def sec_of(rva):
    for s in SEC:
        if s['vaddr'] <= rva < s['vaddr'] + s['vsize']:
            return s['name']
    return '?'

# 1. где .text содержит imm 0xDEAD / 0xDEAE (байты AD DE 00 00 / AE DE 00 00)
def find_imm(val):
    pat = struct.pack('<I', val)
    out = []
    start = 0
    while True:
        i = IM.find(pat, max(start, t0))
        if i < 0 or i >= t0 + tsz:
            break
        out.append(i)
        start = i + 1
    return out

for v in (0xDEAD, 0xDEAE, 0xDEAA):
    hits = find_imm(v)
    print("imm %#06x in .text: %d hits" % (v, len(hits)), ['%#x' % h for h in hits[:12]])
print()

# 2. 0x5457a0: что за валидация
code = bytes(IM[0x5457a0:0x5457a0 + 0xA0])
print("=== 0x5457a0 (vm validation?) ===")
for ins in md.disasm(code, 0x5457a0):
    note = ''
    if ins.mnemonic in ('call', 'jmp') and ins.op_str.startswith('0x'):
        t = int(ins.op_str, 16)
        note = "  ; -> %#x %s" % (t, sec_of(t))
    elif 'rip +' in ins.op_str:
        try:
            off = int(ins.op_str.split('rip +')[1].strip('[]'), 16)
            tgt = ins.address + ins.size + off
            note = "  ; => %#x %s" % (tgt, sec_of(tgt))
        except Exception:
            pass
    print("  %06x  %-7s %-32s%s" % (ins.address, ins.mnemonic, ins.op_str, note))

# 3. старый дамп: heap-записи в live
from mdump import Minidump
lt_old = None
OLD = r"D:\1proekts\rustme\rustme_19600_1782646686.dmp"
mdo = Minidump(OLD)
b, sz, name = mdo.find_module('jvm.dll')
OLDLIVE = 0xDEC0F0
old_lt = [int.from_bytes(mdo.read(b + OLDLIVE + 8 * i, 8)[0:8] or b'\0' * 8, 'little') for i in range(240)]
heap_old = [i for i, v in enumerate(old_lt) if v and not (b <= v < b + sz)]
print("\nOLD build: heap/misplaced live entries at slots:", heap_old)
print("OLD build NULL slots:", [i for i, v in enumerate(old_lt) if v == 0])
