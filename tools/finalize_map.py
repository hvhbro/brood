#!/usr/bin/env python3
"""Final: build resolved slot map + generate jvm_offsets.h."""
import sys, json
from collections import defaultdict
sys.path.insert(0, '.')
import jc

d = json.load(open(r'C:\Users\Admin\Desktop\rustme\tools\rank_lists.json'))
rn = {int(k): v for k, v in d['rn'].items()}
ro = {int(k): v for k, v in d['ro'].items() if v}
O, N = d['O'], d['N']

fp = json.load(open(r'C:\Users\Admin\Desktop\rustme\tools\final_pairs.json'))
pairs = dict((a, b) for a, b in fp['final_pairs'])
# local fixes verified by difflib matrix:
pairs[16] = 16  # DeleteGlobalRef
pairs[17] = 17
pairs[18] = 18
pairs[19] = 19
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
        resolved[name] = HOOKED[name]
        notes[name] = 'hook-verified'
        continue
    body_old = ro.get(s)
    if body_old is None:
        resolved[name] = None
        notes[name] = 'old-unresolvable'
        continue
    body_new = old2new.get(body_old)
    if body_new is None:
        resolved[name] = None
        notes[name] = 'NO-PAIR'
        continue
    resolved[name] = body2slot.get(body_new)
    notes[name] = 'aligned' if resolved[name] is not None else 'new-body-no-slot'

dupes = defaultdict(list)
for n, s in resolved.items():
    if s is not None:
        dupes[s].append(n)
coll = {s: ns for s, ns in dupes.items() if len(ns) > 1}
print(f"resolved {sum(1 for v in resolved.values() if v is not None)}/96; fold groups: {len(coll)}")
all_ok = True
NAME2SLOT = {v: k for k, v in SLOT2NAME.items()}
for s, ns in sorted(coll.items()):
    bodies_old = set(ro[NAME2SLOT[n]] for n in ns)
    bodies_new = set(rn[s] for s in ns if s in rn)
    same_old = len(bodies_old) == 1
    same_new = len(bodies_new) == 1
    all_ok &= (same_old and same_new)
    print(f"  fold slot {s} (byte {s*8}): {ns} old_same={same_old} new_same={same_new}")
print("fold groups consistent:", all_ok)
print("missing:", [n for n, v in resolved.items() if v is None])

json.dump({k: (int(v) if v is not None else None) for k, v in resolved.items()},
          open(r'C:\Users\Admin\Desktop\rustme\tools\slot_map_resolved.json', 'w'), indent=1)
json.dump(notes, open(r'C:\Users\Admin\Desktop\rustme\tools\slot_map_notes.json', 'w'), indent=1)
print("saved slot_map_resolved.json")
