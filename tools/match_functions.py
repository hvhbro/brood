#!/usr/bin/env python3
"""Match JNIEnv-table functions between OLD and NEW builds by disassembling
function bodies (capstone) and comparing normalized signatures. Rip-relative
targets are normalized to self-relative offsets / EXT, so relocation doesn't
break the comparison."""
import re
import sys
import json
import numpy as np
import capstone
from capstone import Cs, CS_ARCH_X86, CS_MODE_64
from capstone.x86 import X86_OP_IMM, X86_OP_MEM, X86_REG_RIP

sys.path.insert(0, '.')
from mdump import Minidump

OLD = r"D:\1proekts\rustme\rustme_19600_1782646686.dmp"
NEW = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"

KNOWN_OLD = {
 6:'GetModule',7:'GetStringUTFChars',12:'GetObjectField',13:'CallStaticObjectMethodA',
 19:'NewStringUTF',22:'CallObjectMethodA',23:'Throw',26:'CallStaticFloatMethodA',
 28:'GetLongField',29:'GetIntField',31:'GetObjectArrayElement',32:'GetStaticIntField',
 34:'GetStaticObjectField',35:'DeleteGlobalRef',38:'CallStaticDoubleMethodA',
 40:'IsInstanceOf',41:'CallStaticShortMethodA',42:'GetDoubleField',
 44:'GetStaticBooleanField',45:'SetDoubleArrayRegion',46:'SetObjectArrayElement',
 63:'CallNonvirtualDoubleMethodA',66:'GetShortArrayRegion',68:'GetSuperclass',
 70:'NewWeakGlobalRef',71:'GetStaticLongField',72:'CallDoubleMethodA',
 75:'GetByteArrayRegion',80:'CallNonvirtualFloatMethodA',82:'GetFloatField',
 88:'CallVoidMethodA',89:'SetIntArrayRegion',90:'GetMethodID',92:'DeleteLocalRef',
 96:'GetDoubleArrayRegion',98:'NewGlobalRef',103:'CallNonvirtualLongMethodA',
 105:'GetFloatArrayRegion',106:'CallObjectMethod',107:'CallNonvirtualShortMethodA',
 108:'IsAssignableFrom',109:'SetBooleanArrayRegion',112:'CallLongMethodA',
 115:'SetCharArrayRegion',116:'CallNonvirtualVoidMethodA',123:'GetBooleanArrayRegion',
 124:'DeleteWeakGlobalRef',125:'CallNonvirtualCharMethodA',126:'CallStaticVoidMethodA',
 127:'SetFloatArrayRegion',129:'GetStaticFloatField',130:'SetShortArrayRegion',
 131:'IsSameObject',132:'PopLocalFrame',136:'CallIntMethodA',
 143:'CallNonvirtualBooleanMethodA',145:'GetStaticShortField',146:'CallFloatMethodA',
 148:'GetStaticCharField',151:'GetBooleanField',152:'NewObject',156:'GetCharArrayRegion',
 157:'CallStaticBooleanMethodA',161:'CallBooleanMethodA',162:'GetArrayLength',
 164:'GetCharField',165:'PushLocalFrame',166:'NewObjectA',168:'IsVirtualThread',
 172:'GetStaticMethodID',175:'CallVoidMethod',176:'CallStaticIntMethodA',
 177:'GetLongArrayRegion',184:'CallByteMethodA',187:'CallNonvirtualObjectMethodA',
 190:'GetIntArrayRegion',191:'SetLongArrayRegion',193:'CallShortMethodA',
 195:'GetStaticByteField',197:'CallCharMethodA',198:'GetByteField',
 199:'CallStaticCharMethodA',201:'ExceptionOccurred',202:'ExceptionClear',
 203:'GetShortField',204:'GetJavaVM',205:'CallNonvirtualByteMethodA',209:'FindClass',
 210:'CallNonvirtualIntMethodA',212:'NewLocalRef',213:'SetByteArrayRegion',
 218:'CallStaticByteMethodA',219:'GetStaticDoubleField',221:'GetObjectClass',
 230:'CallStaticLongMethodA',233:'ReleaseStringUTFChars',
}

MAX_BYTES = 0x300
MAX_INSN = 220

md = Cs(CS_ARCH_X86, CS_MODE_64)
md.detail = True

BRANCH = set('jmp call'.split()) | {f'j{c}' for c in
    'mpzsaobe g'.replace(' ', '')} | {'ja', 'jae', 'jb', 'jbe', 'je', 'jg', 'jge', 'jl', 'jle',
    'jne', 'jno', 'jnp', 'jns', 'jo', 'jp', 'js', 'jcxz', 'jecxz'}


def resolve(img, jb, jsz, v):
    cur = v
    for _ in range(4):
        r = cur - jb
        if not (0 <= r < jsz - 6):
            return None
        b = img[r:r + 6]
        if b[0] == 0xE9:
            cur = cur + 5 + int.from_bytes(b[1:5], 'little', signed=True)
            continue
        if b[0] == 0xFF and b[1] == 0x25:
            pa = cur + 6 + int.from_bytes(b[2:6], 'little', signed=True)
            pr = pa - jb
            if 0 <= pr < jsz - 8:
                cur = int.from_bytes(img[pr:pr + 8], 'little')
                continue
            return None
        return cur
    return cur


def sig(img, jb, jsz, start_rva):
    """Strict sig: normalized (mnemonic, operands). Loose sig: mnemonics only
    plus structural anchors (stack size, EXT/REL markers)."""
    code = img[start_rva:start_rva + MAX_BYTES]
    strict, loose = [], []
    n = 0
    try:
        for ins in md.disasm(code, start_rva):
            n += 1
            if n > MAX_INSN:
                break
            m = ins.mnemonic
            ops = ins.op_str

            # direct branch / call with absolute imm -> self-rel or EXT
            if m in BRANCH or m == 'call':
                im = re.match(r'^(0x[0-9a-f]+)$', ops)
                if im:
                    tgt = int(im.group(1), 16)
                    rel = tgt - start_rva
                    token = f'{"REL" if 0 <= rel < MAX_BYTES else "EXT"}{rel if 0 <= rel < MAX_BYTES else ""}'
                    strict.append(f'{m} {token}')
                    loose.append(m)
                    if m == 'jmp':
                        break
                    continue

            def sub_rip(mo):
                disp = int(mo.group(2), 16)
                if mo.group(1) == '-':
                    disp = -disp
                tgt = ins.address + ins.size + disp
                rel = tgt - start_rva
                return f'[{"REL" if 0 <= rel < MAX_BYTES else "EXT"}{rel if 0 <= rel < MAX_BYTES else ""}]'

            s = re.sub(r'\[rip ([+-]) (0x[0-9a-f]+)\]', sub_rip, ops)
            # large immediates = addresses -> normalize; keep small imms
            s = re.sub(r'\b(0x[0-9a-f]{5,})\b', 'IMM', s)
            strict.append(f'{m} {s}')
            # loose: mnemonic + stack-size + struct-ish displacements
            keep = ''
            mm = re.match(r'sub rsp, (0x[0-9a-f]+)$', ops)
            if mm:
                keep = mm.group(1)
            else:
                ds = re.findall(r'\[(?:e|r|)[a-z0-9]{2,3} \+ (0x[0-9a-f]+)\]', ops)
                if ds:
                    keep = ','.join(ds)
            loose.append(f'{m}:{keep}')
            if m == 'ret':
                break
    except Exception:
        pass
    return tuple(strict), tuple(loose)


def load(dump):
    m = Minidump(dump)
    jb, jsz, _ = m.find_module('jvm.dll')
    img = bytearray(b'\0' * jsz)
    for s, sz, fo in m.mem_regions:
        if s < jb + jsz and s + sz > jb:
            lo = max(s, jb) - jb
            hi = min(s + sz, jb + jsz) - jb
            m.f.seek(fo + (jb + lo - s))
            img[lo:hi] = m.f.read(hi - lo)
    return m, jb, jsz, bytes(img)


md_o, jb_o, js_o, img_o = load(OLD)
md_n, jb_n, js_n, img_n = load(NEW)
qo = np.frombuffer(img_o[:len(img_o) & ~7], dtype='<u8')
qn = np.frombuffer(img_n[:len(img_n) & ~7], dtype='<u8')

def table(q, t):
    return [int(q[t // 8 + i]) for i in range(234)]

Lo = table(qo, 0xDEC0F0)
Ln = table(qn, 0xE6CAF0)

sig_o = {}
for s, name in KNOWN_OLD.items():
    f = resolve(img_o, jb_o, js_o, Lo[s]) if Lo[s] else None
    if f:
        sig_o[name] = (s, *sig(img_o, jb_o, js_o, f - jb_o))

sig_n = {}
for s in range(4, 234):
    f = resolve(img_n, jb_n, js_n, Ln[s]) if Ln[s] else None
    if f and 0 <= f - jb_n < js_n:
        st, lo = sig(img_n, jb_n, js_n, f - jb_n)
        sig_n[s] = (st, lo)

print(f"sigs: old={len(sig_o)} new={len(sig_n)}")

# strict matching
by_strict = {}
for s, (st, lo) in sig_n.items():
    by_strict.setdefault(st, []).append(s)

result = {}
amb, miss = [], []
for name, (s_old, st, lo) in sig_o.items():
    hits = by_strict.get(st, [])
    if len(hits) == 1:
        result[name] = (s_old, hits[0], 'strict')
    elif len(hits) > 1:
        amb.append((name, s_old, hits))
    else:
        miss.append((name, s_old, lo))

# loose matching for misses
by_loose = {}
for s, (st, lo) in sig_n.items():
    by_loose.setdefault(lo, []).append(s)
still = []
for name, s_old, lo in miss:
    hits = by_loose.get(lo, [])
    if len(hits) == 1:
        result[name] = (s_old, hits[0], 'loose')
    elif len(hits) > 1:
        amb.append((name, s_old, hits))
    else:
        still.append((name, s_old))

print(f"\nunique: {len(result)}  ambiguous: {len(amb)}  unmatched: {len(still)}")
print("\nMAP (old slot -> new slot):")
for name in sorted(result, key=lambda n: result[n][0]):
    so, sn, how = result[name]
    mark = '' if so == sn else '   <-- moved'
    print(f"  {name:<30} {so:3d} -> {sn:3d}  [{how}]{mark}")
print("\nambiguous:", [(n, so, h) for n, so, h in amb])
print("unmatched:", [(n, so) for n, so in still])

json.dump({k: [int(a), int(b)] for k, (a, b, _) in result.items()},
          open(r'C:\Users\Admin\Desktop\rustme\tools\slot_map_final.json', 'w'), indent=1)
print("\nsaved -> tools/slot_map_final.json")
