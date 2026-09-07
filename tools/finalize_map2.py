#!/usr/bin/env python3
"""FINAL: build resolved slot map, expand ICF folds, generate jvm_offsets.h."""
import sys, json
from collections import defaultdict
sys.path.insert(0, '.')
import jc

d = json.load(open(r'C:\Users\Admin\Desktop\rustme\tools\rank_lists.json'))
ro = {int(k): v for k, v in d['ro'].items() if v}
rn = {int(k): v for k, v in d['rn'].items()}
O, N = d['O'], d['N']
fp = json.load(open(r'C:\Users\Admin\Desktop\rustme\tools\final_pairs.json'))
pairs = dict((a, b) for a, b in fp['final_pairs'])
# verified fixes:
pairs[16] = 16; pairs[17] = 17; pairs[18] = 18; pairs[19] = 19
for a in range(49, 60):           # Call*A family: shift -1
    if a in pairs:
        pairs[a] = pairs[a] - 1
pairs[48] = 48                    # CallBooleanMethodA
old2new = {O[a]: N[b] for a, b in pairs.items()}

SLOT2NAME = {
 7:'GetStringUTFChars',12:'GetObjectField',13:'CallStaticObjectMethodA',
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
HOOKED = {
 'GetLongField': 150, 'GetIntField': 127, 'GetDoubleField': 208,
 'GetFloatField': 196, 'GetBooleanField': 22, 'GetCharField': 36,
 'GetByteField': 182, 'GetShortField': 134,
}

body_all_slots = defaultdict(list)
for s, body in rn.items():
    body_all_slots[body].append(s)
for body in body_all_slots:
    body_all_slots[body].sort()

# Build map per FUNCTION (not per body), so ICF folds get distinct slots:
resolved = {}
for s, name in SLOT2NAME.items():
    if name in HOOKED:
        resolved[name] = HOOKED[name]
        continue
    body_old = ro.get(s)
    body_new = old2new.get(body_old) if body_old else None
    if body_new is None:
        resolved[name] = None
        continue
    candidates = body_all_slots[body_new]
    resolved[name] = candidates  # list; may hold 1 or more slots

# Expand folds: group functions that ended with the same body, assign slots by
# ascending old slot order (matches old-build slot order convention).
groups = defaultdict(list)
for name, cand in resolved.items():
    if cand is None:
        continue
    key = tuple(cand) if isinstance(cand, list) else (cand,)
    groups[key].append(name)

final = {}
for cand, names in groups.items():
    # ascending OLD slot order (matches old-build convention for folded pairs)
    oldslot = {v: k for k, v in SLOT2NAME.items()}
    names_sorted = sorted(names, key=lambda n: oldslot.get(n, 999))
    for name, slot in zip(names_sorted, sorted(cand)):
        final[name] = slot

missing = [n for n in SLOT2NAME.values() if n not in final]
dupes = defaultdict(list)
for n, s in final.items():
    dupes[s].append(n)
coll = {s: ns for s, ns in dupes.items() if len(ns) > 1}
print(f"final: {len(final)}/96 mapped; missing: {missing}; slot collisions: {coll or 'none'}")

json.dump(final, open(r'C:\Users\Admin\Desktop\rustme\tools\slot_map_resolved.json', 'w'), indent=1)

# ---- generate jvm_offsets.h ----
lines = []
for name in sorted(final, key=lambda n: final[n]):
    lines.append(f"    {name} = {final[name] * 8},")
header = """// Auto-generated from minidump analysis (rustme_26116 dump vs old-build dump).
// %d functions. ВАЖНО: порядок слотов env-таблицы в этом форке НЕ стандартный
// (не jni.h) и меняется между билдами игры - регенерировать после каждого
// обновления (методика: tools/ в корне проекта).
//
// Верификация: 8 хукнутых геттеров восстановлены из трамплинов (тела совпали 1:1),
// остальное - позиционное выравнивание emission-последовательности .text между
// билдами (difflib по мнемоникам, mean 0.86). FindClass подтверждён структурно.
#pragma once
#include <cstdint>

enum class JniOffset : uint32_t {
%s
};
""" % (len(final), '\n'.join(lines))

with open(r'C:\Users\Admin\Desktop\rustme\jni\dll\src\jvm\jvm_offsets.h', 'w', encoding='utf-8') as f:
    f.write(header)
print("written jvm_offsets.h")

# print the map for review
for name in sorted(final, key=lambda n: final[n]):
    print(f"  {name:<30} slot {final[name]:3d}  byte {final[name]*8}")
