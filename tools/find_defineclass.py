#!/usr/bin/env python3
"""Поиск настоящего DefineClass в jvm.dll (new build) через экспорт JVM_*.

1. Парсим PE-экспорты jvm.dll из кэш-образа.
2. Находим JVM_DefineClass*, JVM_FindClass* и др.
3. Сканируем .text на call/jmp rel32 к этим экспортам — находим wrapper'ы.
4. Сопоставляем wrapper'ы со слотами live-таблицы.
5. Заодно ищем killers: IAT TerminateProcess/ExitProcess/RaiseFailFastException
   и вызовы их в wrapper'ах.
"""
import struct
import sys
sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme\tools")
from jc import meta, sections, img, live_table, pristine_table

BASE = meta('new')['base']
IM = img('new')
SEC = sections('new')
lt = live_table('new', 240)

# ---------- PE parsing ----------
def u16(o): return struct.unpack_from('<H', IM, o)[0]
def u32(o): return struct.unpack_from('<I', IM, o)[0]

e_lfanew = u32(0x3C)
opt_hdr = e_lfanew + 24
magic = u16(opt_hdr)
assert magic == 0x20b, "PE32+ expected"
export_rva = u32(opt_hdr + 112)
import_rva = u32(opt_hdr + 120)
iat_rva = u32(opt_hdr + 200)  # IAT data dir (index 12)
iat_size = u32(opt_hdr + 204)

def rva2off(rva):
    for s in SEC:
        if s['vaddr'] <= rva < s['vaddr'] + max(s['vsize'], 1):
            # assume raw==virtual layout in dump image (mapped image)
            return rva
    return None

# exports
exports = {}
if export_rva:
    e = export_rva
    (flags, ts, majv, minv, name_rva, ordinal_base, n_funcs, n_names,
     addr_funcs, addr_names, addr_ord) = struct.unpack_from('<IIHHIIIIIII', IM, e)
    for i in range(n_names):
        nm_rva = u32(addr_names + 4 * i)
        end = IM.index(b'\0', nm_rva)
        nm = IM[nm_rva:end].decode('latin1')
        fn_rva = u32(addr_funcs + 4 * i)
        exports[nm] = fn_rva

print("exports: %d" % len(exports))
interesting = {k: v for k, v in exports.items()
               if any(t in k for t in ('DefineClass', 'FindClass', 'FindLoadedClass',
                                       'FindClassFromCaller', 'FindClassFromLoader'))}
for k in sorted(interesting):
    print("  export %-40s RVA %#x" % (k, interesting[k]))

# imports: build IAT slot -> name
imports = {}
if import_rva:
    d = import_rva
    while True:
        oft, ts, fc, name_rva, first_thunk = struct.unpack_from('<IIIII', IM, d)
        if oft == 0 and name_rva == 0 and first_thunk == 0:
            break
        dll_off = name_rva
        dll_name = IM[dll_off:IM.index(b'\0', dll_off)].decode('latin1')
        i = 0
        while True:
            thunk = u32(first_thunk + 8 * i)
            if thunk == 0:
                break
            if thunk & 0x80000000 == 0:
                hn = u32(oft + 8 * i)
                fo = hn + 2
                end = IM.index(b'\0', fo)
                fname = IM[fo:end].decode('latin1')
                imports[first_thunk + 8 * i] = dll_name + '!' + fname
            i += 1
        d += 20

kill_imports = {rva: n for rva, n in imports.items()
                if any(t in n for t in ('TerminateProcess', 'ExitProcess',
                                        'RaiseFailFastException', 'WriteProcessMemory',
                                        'VirtualProtect', 'CreateToolhelp32Snapshot',
                                        'NtTerminateProcess'))}
print("\nrelevant imports (IAT):")
for rva, n in sorted(kill_imports.items()):
    print("  IAT %#x  %s" % (rva, n))

# ---------- scan .text for calls ----------
text = next(s for s in SEC if s['name'] == '.text')
t0, tsz = text['vaddr'], text['vsize']

def scan_callers(target_rva):
    """Найти E8/E9 rel32 в .text, указывающие на target_rva."""
    out = []
    pat = struct.pack('<I', 0)  # scan manually
    for off in range(t0, t0 + tsz - 5):
        b = IM[off]
        if b in (0xE8, 0xE9):
            rel = struct.unpack_from('<i', IM, off + 1)[0]
            if off + 5 + rel == target_rva:
                out.append((off, 'call' if b == 0xE8 else 'jmp'))
    return out

# Какие экспорты реально вызываются из .text
print("\n=== callers of interesting exports ===")
for k in sorted(interesting):
    callers = scan_callers(interesting[k])
    print("%s @ %#x: %d direct call/jmp" % (k, interesting[k], len(callers)))
    for off, kind in callers[:10]:
        # какой live-слот содержит этот адрес внутри себя?
        slots = []
        for s in range(240):
            p = lt[s] - BASE
            if p and 0x100000000 <= lt[s] < 0x7fffffffffff and p <= off < p + 0x2000:
                slots.append(s)
        print("    %s at %#x  (in live func of slot%s)" %
              (kind, off, ('s ' + ','.join(map(str, slots))) if slots else ' NONE'))
