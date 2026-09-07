#!/usr/bin/env python3
"""Дизасемблер целей env-таблиц: pristine vs live для ключевых слотов.

Проверяет гипотезу: live-таблица указывает на interceptor-стабы протектора,
pristine (.rdata) — на настоящие JNI-функции.
"""
import json
import sys
sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme\tools")
from jc import meta, sections, img, live_table, pristine_table
from capstone import Cs, CS_ARCH_X86, CS_MODE_64

BASE = meta('new')['base']
IM = img('new')
SEC = sections('new')
md = Cs(CS_ARCH_X86, CS_MODE_64)
md.detail = False

def sec_of(rva):
    for s in SEC:
        if s['vaddr'] <= rva < s['vaddr'] + s['vsize']:
            return s['name']
    return '?'

def disas(rva, n=24, label=''):
    """Дизасемблировать n инструкций с RVA внутри jvm.dll (new cache)."""
    code = bytes(IM[rva:rva + 160])
    out = []
    for ins in md.disasm(code, rva):
        out.append("  %06x  %-22s %s" % (ins.address, ins.mnemonic, ins.op_str))
        if len(out) >= n:
            break
    print("%s (RVA %#x, sec %s):" % (label, rva, sec_of(rva)))
    print('\n'.join(out))
    print()

def func_end(rva, max_len=0x400):
    """Найти конец функции: ret + padding (align 16), потом int3/новая функция."""
    code = bytes(IM[rva:rva + max_len])
    last_ret = None
    for ins in md.disasm(code, rva):
        if ins.mnemonic == 'ret':
            last_ret = ins.address + ins.size
        elif ins.mnemonic == 'int3' and last_ret:
            return last_ret
        if ins.address - rva > max_len - 16:
            break
    return last_ret or rva + 0x40

SLOTS = {
    230: 'FindClass', 233: 'GetMethodID', 135: 'GetStaticMethodID',
    69: 'slot69 (killer/DefineClass?)', 7: 'slot7 killer',
    50: 'slot50 killer', 55: 'slot55 killer', 97: 'slot97 killer',
    195: 'CallObjectMethodA', 200: 'CallBooleanMethodA',
    26: 'NewStringUTF', 46: 'ExceptionClear',
}

lt = live_table('new', 240)
pt = pristine_table('new', 240)

for s in sorted(SLOTS):
    print("=" * 74)
    print("SLOT %d  %s" % (s, SLOTS[s]))
    print("=" * 74)
    print("--- PRISTINE target ---")
    disas(pt[s] - BASE, 16, "pristine slot %d" % s)
    print("--- LIVE target ---")
    disas(lt[s] - BASE, 24, "live slot %d" % s)
