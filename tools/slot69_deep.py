#!/usr/bin/env python3
"""1) Сканируем весь .text на прямые call/jmp к каждой live-цели слота 0..239:
   у 'листовых' слотов входов почти нет (killers), у JNI-слотов — много.
2) Дизас live-слота 69 полностью + его первые уникальные вызовы.
3) Проверяем содержимое live-таблицы: редкие цели (heap/rdata/data/low).
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

# ---------- 1. indegree ----------
targets = {}
for s in range(240):
    p = lt[s] - BASE
    if 0x1000 < p < 0x21f0000:
        targets.setdefault(p, []).append(s)

indeg = {p: 0 for p in targets}
for off in range(t0, t0 + tsz - 5):
    b = IM[off]
    if b in (0xE8, 0xE9):
        rel = struct.unpack_from('<i', IM, off + 1)[0]
        dst = off + 5 + rel
        if dst in indeg:
            indeg[dst] += 1

print("=== live slots: entry indegree into their target func ===")
rows = []
for s in range(240):
    p = lt[s] - BASE
    if p not in indeg:
        tag = {'HEAP/UNMAPPED', 'NULL', 'LOW'}.intersection() and '?' or 'non-text'
        rows.append((s, -1, 'non-text: %#x' % (lt[s] - BASE) if lt[s] > BASE else 'ptr=%#x' % lt[s]))
    else:
        rows.append((s, indeg[p], ''))
for s, n, extra in rows:
    if extra:
        print("  slot %3d: %s" % (s, extra))
print("\n  slots sorted by indegree (asc, text-targets only):")
vals = [(n, s) for s, n, e in rows if n >= 0]
vals.sort()
print("   ", ['%d:%d' % (s, n) for n, s in vals[:40]])

# ---------- 2. full disas of live slot 69 ----------
def disas_range(rva, length, label):
    code = bytes(IM[rva:rva + length])
    print("=== %s (RVA %#x) ===" % (label, rva))
    out = []
    for ins in md.disasm(code, rva):
        tgt = ''
        if ins.mnemonic in ('call', 'jmp') and ins.op_str.startswith('0x'):
            try:
                t = int(ins.op_str, 16)
                tgt = "   ; -> %#x %s" % (t, sec_of(t))
            except ValueError:
                pass
        out.append("  %06x  %-7s %-30s%s" % (ins.address, ins.mnemonic, ins.op_str, tgt))
        if ins.mnemonic in ('ret', 'jmp') and ins.op_str.startswith('0x') and len(out) > 30:
            pass
    print('\n'.join(out))

disas_range(lt[69] - BASE, 0x420, "LIVE SLOT 69 full")
