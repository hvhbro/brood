#!/usr/bin/env python3
"""Проверка цепочки блокировки DefineClass:
1. Дизас 0x545690 (vmcaller-пролог) — куда ведёт при провале проверки.
2. Полный live[69]: ветка 0x3F5FCA (magic) и 0x3F5F1D..0x3F5FC9 — что
   возвращает при отказе (rax=rsi).
3. Восстановление логики: rsi=0 всю дорогу, кроме 0x3F5F10 после 0x4154F0.
   Какие проверки приводят к возврату rsi=0.
4. Взгляд на 0x6E9FC0: вызов 0x6E8520 (loader resolve?) и 0x43BA90
   (resolve_from_stream?) — проверить "пустые" ветки.
"""
import sys
sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme\tools")
from jc import img, sections
from capstone import Cs, CS_ARCH_X86, CS_MODE_64

IM = img('new')
SEC = sections('new')
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
        elif 'rip +' in ins.op_str:
            try:
                off = int(ins.op_str.split('rip +')[1].strip('[]'), 16)
                tgt = ins.address + ins.size + off
                note = "  ; => %#x %s" % (tgt, sec_of(tgt))
            except Exception:
                pass
        print("  %06x  %-7s %-32s%s" % (ins.address, ins.mnemonic, ins.op_str, note))
    print()

disas(0x545690, 0x60, "vmcaller 0x545690")
disas(0x545630, 0x60, "before 0x545690")
