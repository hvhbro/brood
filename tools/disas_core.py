#!/usr/bin/env python3
"""Дизас ядра define-пути 0x6E9FC0 (SystemDictionary::resolve_from_stream?)
и просмотр данных: vtable 0xD2CAD0, строка DefineClass 0xD2CD5E,
функциональный указатель [rip+0xa87ff4] -> 0xE7DEFA.
"""
import struct
import sys
sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme\tools")
from jc import meta, sections, img, live_table
from capstone import Cs, CS_ARCH_X86, CS_MODE_64

BASE = meta('new')['base']
IM = img('new')
SEC = sections('new')
md = Cs(CS_ARCH_X86, CS_MODE_64)

def sec_of(rva):
    for s in SEC:
        if s['vaddr'] <= rva < s['vaddr'] + s['vsize']:
            return s['name']
    return '?'

def disas(rva, length, label):
    code = bytes(IM[rva:rva + length])
    print("=== %s (RVA %#x, sec %s) ===" % (label, rva, sec_of(rva)))
    for ins in md.disasm(code, rva):
        note = ''
        if ins.mnemonic in ('call', 'jmp') and ins.op_str.startswith('0x'):
            try:
                t = int(ins.op_str, 16)
                note = "   ; -> %#x %s" % (t, sec_of(t))
            except ValueError:
                pass
        print("  %06x  %-7s %-34s%s" % (ins.address, ins.mnemonic, ins.op_str, note))
    print()

# 1. ядро define
disas(0x6E9FC0, 0x140, "define core (resolve_from_stream?)")

# 2. данные
def dump_bytes(rva, n, label):
    print("%s @ %#x (%s):" % (label, rva, sec_of(rva)))
    d = bytes(IM[rva:rva + n])
    for i in range(0, len(d), 16):
        chunk = d[i:i + 16]
        hexs = ' '.join('%02x' % b for b in chunk)
        asc = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk)
        print("  %06x  %s  %s" % (rva + i, hexs, asc))
    print()

dump_bytes(0xD2CAD0, 64, "stream vtable?")
dump_bytes(0xD2CD20, 128, "around 'DefineClass' str 0xD2CD5E")
dump_bytes(0xE7DEFA, 32, "fnptr slot 0xE7DEFA ([rip+0xa87ff4])")
dump_bytes(0xE915CC, 64, "guard string table 0xE915CC")
