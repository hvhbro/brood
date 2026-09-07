#!/usr/bin/env python3
"""FINAL v2: assemble the complete slot map and generate jvm_offsets.h."""
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
# verified local fixes:
pairs[16] = 16; pairs[17] = 17; pairs[18] = 18; pairs[19] = 19   # DGR block
# varargs Call*A family: emission order Bool,Byte,Char,Short,Object,Int,Long,Float,Double
# (old ranks 48..56) maps to new ranks 47..55 (whole family shifted -1; DP agreed for
# 49..56, the 48->19 entry was a DP gap artifact).
varargs_old_ranks = {48: 'CallBooleanMethodA', 49: 'CallByteMethodA', 50: 'CallCharMethodA',
                     51: 'CallShortMethodA', 52: 'CallObjectMethodA', 53: 'CallIntMethodA',
                     54: 'CallLongMethodA', 55: 'CallFloatMethodA', 56: 'CallDoubleMethodA'}
for a in range(49, 57):
    pairs[a] = a - 1
pairs[48] = 47
# VoidA stays DP-mapped (59 -> 58)
old2new = {O[a]: N[b] for a, b in pairs.items()}
body2slot = {}
for s, body in rn.items():
    body2slot.setdefault(body, s)

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

resolved, notes = {}, {}
for s, name in SLOT2NAME.items():
    if name in HOOKED:
        resolved[name] = HOOKED[name]; notes[name] = 'hook-verified'; continue
    body_old = ro.get(s)
    body_new = old2new.get(body_old) if body_old else None
    if body_new is None:
        resolved[name] = None; notes[name] = 'NO-PAIR'; continue
    resolved[name] = body2slot.get(body_new); notes[name] = 'aligned'

missing = [n for n, v in resolved.items() if v is None]
dupes = defaultdict(list)
for n, s in resolved.items():
    if s is not None:
        dupes[s].append(n)
coll = {s: ns for s, ns in dupes.items() if len(ns) > 1}
print(f"resolved {96 - len(missing)}/96; missing: {missing}")
print("fold collisions (same slot):")
for s, ns in sorted(coll.items()):
    body = rn[s]
    all_slots = sorted(k for k, v in rn.items() if v == body)
    print(f"  slot {s}: {ns}  (body {body:#x} also at slots {all_slots})")

# For folds: if the body has MULTIPLE slots in the new table, assign one per name
# (ascending old-slot order). If only ONE slot, both names share it (genuine fold).
final = {}
for s, ns in dupes.items():
    if len(ns) == 1:
        final[ns[0]] = s
        continue
    body = rn[s]
    all_slots = sorted(k for k, v in rn.items() if v == body)
    names_sorted = sorted(ns, key=lambda n: SLOT2NAME.get(n, 999))
    if len(all_slots) >= len(names_sorted):
        for name, slot in zip(names_sorted, all_slots):
            final[name] = slot
    else:
        for name in names_sorted:
            final[name] = s
for n, s in resolved.items():
    if s is not None and n not in final:
        final[n] = s

final_missing = [n for n in SLOT2NAME.values() if n not in final]
print(f"\nFINAL: {len(final)}/96 mapped; missing: {final_missing}")

# bijection check
slots_used = defaultdict(list)
for n, s in final.items():
    slots_used[s].append(n)
coll2 = {s: ns for s, ns in slots_used.items() if len(ns) > 1}
print("remaining collisions:", coll2 or "none")

json.dump(final, open(r'C:\Users\Admin\Desktop\rustme\tools\slot_map_FINAL.json', 'w'), indent=1)

# generate header
lines = []
for name in sorted(final, key=lambda n: final[n]):
    lines.append(f"    {name} = {final[name] * 8},")
header = """// Auto-generated from minidump analysis (rustme_26116 dump vs old-build dump).
// %d functions. ВАЖНО: порядок слотов env-таблицы в этом форке НЕ стандартный
// (не как в jni.h) и меняется между билдами игры - регенерировать после каждого
// обновления (методика и скрипты: tools/ в корне проекта).
//
// Верификация: 8 примитивных геттеров восстановлены из хук-трамплинов (тела
// совпали 1:1), остальное - позиционное выравнивание emission-последовательности
// .text между билдами (difflib по мнемоникам, mean 0.86). FindClass подтверждён
// структурно. Call*A семья выровнена по порядку типов emission.
#pragma once
#include <cstdint>

enum class JniOffset : uint32_t {
%s
};
""" % (len(final), '\n'.join(lines))

with open(r'C:\Users\Admin\Desktop\rustme\jni\dll\src\jvm\jvm_offsets.h', 'w', encoding='utf-8') as f:
    f.write(header)
print("written jvm_offsets.h")

for name in sorted(final, key=lambda n: final[n]):
    print(f"  {name:<30} slot {final[name]:3d}  byte {final[name]*8}")
