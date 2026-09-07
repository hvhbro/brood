#!/usr/bin/env python3
"""Map NEW live-table slots to function names via the shared-helper trick:

Each live JNI function body ends with a call/jmp to a per-function "second
half" helper; the helper addresses are baked into the binary and identical
between builds (the pristine .rdata table holds them in OLD-live-slot order).

  new_live[X] body calls pristine[T]  =>  function has OLD slot T  =>  name.
"""
import re
import sys
import json
import numpy as np
import capstone
from capstone import Cs, CS_ARCH_X86, CS_MODE_64

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

SCAN = 0x500
md = Cs(CS_ARCH_X86, CS_MODE_64)
md.detail = False


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


def helper_refs(img, jb, body_rva):
    """All pristine-helper RVAs referenced by call/jmp rel32 within body."""
    refs = []
    code = img[body_rva:body_rva + SCAN]
    for ins in md.disasm(code, body_rva):
        if ins.mnemonic in ('call', 'jmp'):
            m = re.match(r'^(0x[0-9a-f]+)$', ins.op_str)
            if m:
                refs.append(int(m.group(1), 16))
    return refs


md_o, jb_o, js_o, img_o = load(OLD)
md_n, jb_n, js_n, img_n = load(NEW)
qo = np.frombuffer(img_o[:len(img_o) & ~7], dtype='<u8')
qn = np.frombuffer(img_n[:len(img_n) & ~7], dtype='<u8')

Po = [int(qo[0xB628F8 // 8 + i]) for i in range(234)]
Pn = [int(qn[0xBD2898 // 8 + i]) for i in range(234)]
Lo = [int(qo[0xDEC0F0 // 8 + i]) for i in range(234)]
Ln = [int(qn[0xE6CAF0 // 8 + i]) for i in range(234)]

# sanity: pristine helpers same in both builds
same = sum(1 for a, b in zip(Po, Pn) if a and b and (a - jb_o) == (b - jb_n))
print(f"pristine helpers identical old/new: {same}/234")

helper_rva = {Po[i] - jb_o: i for i in range(234) if Po[i]}

# --- validate on OLD build: live[S] should reference helper pristine[S] ---
ok, fail = 0, 0
for s, name in KNOWN_OLD.items():
    v = Lo[s]
    if not v or not (jb_o <= v < jb_o + js_o):
        fail += 1
        continue
    refs = helper_refs(img_o, jb_o, v - jb_o)
    hit = any((t - jb_o) == (Po[s] - jb_o) for t in refs)
    if hit:
        ok += 1
    else:
        fail += 1
print(f"OLD validation: live[S] references its own helper pristine[S]: {ok} ok / {fail} fail")

# --- map NEW slots ---
new_map = {}
detail = {}
for X in range(4, 234):
    v = Ln[X]
    if not v or not (jb_n <= v < jb_n + js_n):
        continue
    refs = [t - jb_n for t in helper_refs(img_n, jb_n, v - jb_n)]
    ts = sorted({helper_rva[r] for r in refs if r in helper_rva})
    if len(ts) == 1:
        T = ts[0]
        detail[X] = T
    elif len(ts) > 1:
        detail[X] = ('multi', ts)

# old slot -> new slot
inv = {}
for X, T in detail.items():
    if isinstance(T, int):
        inv.setdefault(T, []).append(X)

print(f"\nnew slots with unique helper: {sum(1 for v in detail.values() if isinstance(v, int))}, "
      f"multi-helper: {sum(1 for v in detail.values() if not isinstance(v, int))}")

mapped = {name: inv[T][0] for name, T in [(n, s) for s, n in KNOWN_OLD.items()] if T in inv}
print(f"\nMAPPED {len(mapped)}/{len(KNOWN_OLD)} known functions:")
for name in sorted(mapped, key=lambda n: KNOWN_OLD[n]):
    so, sn = KNOWN_OLD[name], mapped[name]
    mark = '' if so == sn else '   <-- moved'
    print(f"  {name:<30} {so:3d} -> {sn:3d}{mark}")

json.dump({k: int(v) for k, v in mapped.items()},
          open(r'C:\Users\Admin\Desktop\rustme\tools\slot_map_helper.json', 'w'), indent=1)
print("saved -> tools/slot_map_helper.json")
