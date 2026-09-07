#!/usr/bin/env python3
"""Rebuild the JNIEnv slot map (slot -> function name) for the NEW build.

Method: the pristine (.rdata) table and the live (.data) table hold the same
set of JNI functions; pristine entries go through jump thunks. Resolving the
thunks yields the permutation pristine_slot -> live_slot. The old build's
pristine slots are named via the known old live slots (verified at runtime in
the previous session); applying the NEW permutation to those pristine slots
gives the new live slot for every function.
"""
import struct
import sys
import numpy as np

sys.path.insert(0, '.')
from mdump import Minidump

OLD = r"D:\1proekts\rustme\rustme_19600_1782646686.dmp"
NEW = r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp"

# old live-table slot map, runtime-verified in the previous session
# (from jvm_offsets.h byte offsets / 8, discovered-offsets.md)
KNOWN_OLD_LIVE = {
    'GetModule': 6, 'GetStringUTFChars': 7, 'GetObjectField': 12,
    'CallStaticObjectMethodA': 13, 'NewStringUTF': 19, 'CallObjectMethodA': 22,
    'Throw': 23, 'CallStaticFloatMethodA': 26, 'GetLongField': 28,
    'GetIntField': 29, 'GetObjectArrayElement': 31, 'GetStaticIntField': 32,
    'GetStaticObjectField': 34, 'DeleteGlobalRef': 35, 'CallStaticDoubleMethodA': 38,
    'IsInstanceOf': 40, 'CallStaticShortMethodA': 41, 'GetDoubleField': 42,
    'GetStaticBooleanField': 44, 'SetDoubleArrayRegion': 45,
    'SetObjectArrayElement': 46, 'CallNonvirtualDoubleMethodA': 63,
    'GetShortArrayRegion': 66, 'GetSuperclass': 68, 'NewWeakGlobalRef': 70,
    'GetStaticLongField': 71, 'CallDoubleMethodA': 72, 'GetByteArrayRegion': 75,
    'CallNonvirtualFloatMethodA': 80, 'GetFloatField': 82, 'CallVoidMethodA': 88,
    'SetIntArrayRegion': 89, 'GetMethodID': 90, 'DeleteLocalRef': 92,
    'GetDoubleArrayRegion': 96, 'NewGlobalRef': 98, 'CallNonvirtualLongMethodA': 103,
    'GetFloatArrayRegion': 105, 'CallObjectMethod': 106,
    'CallNonvirtualShortMethodA': 107, 'IsAssignableFrom': 108,
    'SetBooleanArrayRegion': 109, 'CallLongMethodA': 112, 'SetCharArrayRegion': 115,
    'CallNonvirtualVoidMethodA': 116, 'GetBooleanArrayRegion': 123,
    'DeleteWeakGlobalRef': 124, 'CallNonvirtualCharMethodA': 125,
    'CallStaticVoidMethodA': 126, 'SetFloatArrayRegion': 127,
    'GetStaticFloatField': 129, 'SetShortArrayRegion': 130, 'IsSameObject': 131,
    'PopLocalFrame': 132, 'CallIntMethodA': 136, 'CallNonvirtualBooleanMethodA': 143,
    'GetStaticShortField': 145, 'CallFloatMethodA': 146, 'GetStaticCharField': 148,
    'GetBooleanField': 151, 'NewObject': 152, 'GetCharArrayRegion': 156,
    'CallStaticBooleanMethodA': 157, 'CallBooleanMethodA': 161,
    'GetArrayLength': 162, 'GetCharField': 164, 'PushLocalFrame': 165,
    'NewObjectA': 166, 'IsVirtualThread': 168, 'GetStaticMethodID': 172,
    'CallVoidMethod': 175, 'CallStaticIntMethodA': 176, 'GetLongArrayRegion': 177,
    'CallByteMethodA': 184, 'CallNonvirtualObjectMethodA': 187,
    'GetIntArrayRegion': 190, 'SetLongArrayRegion': 191, 'CallShortMethodA': 193,
    'GetStaticByteField': 195, 'CallCharMethodA': 197, 'GetByteField': 198,
    'CallStaticCharMethodA': 199, 'ExceptionOccurred': 201, 'ExceptionClear': 202,
    'GetShortField': 203, 'GetJavaVM': 204, 'CallNonvirtualByteMethodA': 205,
    'FindClass': 209, 'CallNonvirtualIntMethodA': 210, 'NewLocalRef': 212,
    'SetByteArrayRegion': 213, 'CallStaticByteMethodA': 218,
    'GetStaticDoubleField': 219, 'GetObjectClass': 221, 'CallStaticLongMethodA': 230,
    'ReleaseStringUTFChars': 233,
}

N_ENTRIES = 240  # read a bit past 233


def load(dump_path):
    md = Minidump(dump_path)
    jb, js, _ = md.find_module('jvm.dll')
    img = bytearray(b'\0' * js)
    for s, sz, fo in md.mem_regions:
        if s < jb + js and s + sz > jb:
            lo = max(s, jb) - jb
            hi = min(s + sz, jb + js) - jb
            md.f.seek(fo + (jb + lo - s))
            img[lo:hi] = md.f.read(hi - lo)
    return md, jb, js, bytes(img)


def resolve(md, jbase, jsize, img, va, depth=6):
    """Follow jmp thunks to the final function address."""
    seen = set()
    cur = va
    for _ in range(depth):
        if cur in seen:
            return cur
        seen.add(cur)
        r = cur - jbase
        if not (0 <= r < jsize - 6):
            return cur  # outside image: nothing to follow
        b = img[r:r + 6]
        if b[0] == 0xE9:  # jmp rel32
            rel = int.from_bytes(b[1:5], 'little', signed=True)
            cur = cur + 5 + rel
            continue
        if b[0] == 0xFF and b[1] == 0x25:  # jmp [rip+disp32]
            disp = int.from_bytes(b[2:6], 'little', signed=True)
            pa = cur + 6 + disp
            pr = pa - jbase
            if 0 <= pr < jsize - 8:
                cur = int.from_bytes(img[pr:pr + 8], 'little')
                continue
            mem = md.read(pa, 8)
            if mem:
                cur = int.from_bytes(mem, 'little')
                continue
            return cur
        return cur  # not a thunk
    return cur


def analyze(label, dump, pristine_rva, live_rva):
    md, jb, js, img = load(dump)
    q = np.frombuffer(img[:len(img) & ~7], dtype='<u8')
    pres = [int(q[pristine_rva // 8 + i]) for i in range(N_ENTRIES)]
    live = [int(q[live_rva // 8 + i]) for i in range(N_ENTRIES)]
    pres_res = [resolve(md, jb, js, img, v) if v else 0 for v in pres]
    live_res = [resolve(md, jb, js, img, v) if v else 0 for v in live]

    n_thunk = sum(1 for v, r in zip(pres, pres_res) if v and r != v)
    print(f"\n=== {label} ===")
    print(f"  pristine entries: {sum(1 for v in pres if v)} nonzero, "
          f"{n_thunk} resolved through jmp thunks")
    print(f"  live entries: {sum(1 for v in live if v)} nonzero, "
          f"{sum(1 for v, r in zip(live, live_res) if v and r != v)} resolved through jmp")

    # target -> slots
    from collections import defaultdict
    t2p, t2l = defaultdict(list), defaultdict(list)
    for i, t in enumerate(pres_res):
        if t:
            t2p[t].append(i)
    for i, t in enumerate(live_res):
        if t:
            t2l[t].append(i)
    pairs = {}
    ambiguous = 0
    for t in set(t2p) & set(t2l):
        if len(t2p[t]) == 1 and len(t2l[t]) == 1:
            pairs[t2p[t][0]] = t2l[t][0]
        else:
            ambiguous += 1
    print(f"  unique pristine<->live matches: {len(pairs)} (ambiguous targets: {ambiguous})")

    # diagnostics: sample thunk bytes of a few pristine entries
    shown = 0
    for i, v in enumerate(pres):
        if v and shown < 3:
            r = v - jb
            print(f"    pristine[{i}] = jvm:{r:#x} bytes: {img[r:r+8].hex()}")
            shown += 1
    return pres, live, pres_res, live_res, pairs


# ---- old build: name pristine slots via known live slots ----
pres_o, live_o, pres_res_o, live_res_o, pairs_o = analyze(
    'OLD build', OLD, 0xB628F8, 0xDEC0F0)

inv_pairs_o = {}   # live slot -> pristine slot
for t, s in pairs_o.items():
    inv_pairs_o.setdefault(s, []).append(t)

slot2pristine = {}
matched = 0
for name, s in KNOWN_OLD_LIVE.items():
    ts = inv_pairs_o.get(s, [])
    if len(ts) == 1:
        slot2pristine[name] = ts[0]
        matched += 1
print(f"\n  old: named {matched}/{len(KNOWN_OLD_LIVE)} known functions via thunk resolution")
unresolved = [n for n in KNOWN_OLD_LIVE if n not in slot2pristine]
if unresolved:
    print(f"  unresolved names: {unresolved[:10]}{'...' if len(unresolved) > 10 else ''}")

# ---- new build: permutation ----
pres_n, live_n, pres_res_n, live_res_n, pairs_n = analyze(
    'NEW build', NEW, 0xBD2898, 0xE6CAF0)

# permutation comparison (pristine slot -> live slot) between builds
same = sum(1 for t in pairs_o if t in pairs_n and pairs_o[t] == pairs_n[t])
diff = sum(1 for t in pairs_o if t in pairs_n and pairs_o[t] != pairs_n[t])
print(f"\n  permutation comparison: same={same} changed={diff} "
      f"(of {len(set(pairs_o) & set(pairs_n))} common slots)")

# ---- compose the new slot map ----
new_map = {}
for name, t in slot2pristine.items():
    if t in pairs_n:
        new_map[name] = pairs_n[t]

print(f"\n  composed new live-slot map for {len(new_map)}/{len(KNOWN_OLD_LIVE)} functions:")
for name in sorted(new_map, key=lambda n: new_map[n]):
    old_s = KNOWN_OLD_LIVE[name]
    new_s = new_map[name]
    mark = '' if old_s == new_s else '   <-- moved'
    print(f"    {name:<28} old={old_s:3d} new={new_s:3d}{mark}")

np.save(r'C:\Users\Admin\Desktop\rustme\tools\new_map.npy', np.array(sorted(new_map.items(), dtype=object), dtype=object))
import json
with open(r'C:\Users\Admin\Desktop\rustme\tools\new_slot_map.json', 'w') as f:
    json.dump({k: int(v) for k, v in new_map.items()}, f, indent=1)
print("\n  saved -> tools/new_slot_map.json")
