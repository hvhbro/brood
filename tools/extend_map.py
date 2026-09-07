#!/usr/bin/env python3
"""Extend jvm_offsets.h: infer unnamed JNI slots by span interpolation between
known anchors (emission order), using live-table slot positions directly
(no N-list index needed). Produces jvm_offsets.h v2 with extra functions."""
import json
from collections import defaultdict
sys_path = r'C:\Users\Admin\Desktop\rustme\tools'

d = json.load(open(r'C:\Users\Admin\Desktop\rustme\tools\rank_lists.json'))
rn = {int(k): v for k, v in d['rn'].items()}
N = d['N']
fp = json.load(open(r'C:\Users\Admin\Desktop\rustme\tools\final_pairs.json'))
pairs = dict((a, b) for a, b in fp['final_pairs'])

final = json.load(open(r'C:\Users\Admin\Desktop\rustme\tools\slot_map_FINAL.json'))
# em-rank по слоту: слот -> тело; тело может быть не в N (hooked bodies).
# Для интерполяции нужны em-ranks только тех функций, чьи тела есть в N.
name2rank = {}
for name, slot in final.items():
    if slot in rn:
        body = rn[slot]
        if body in N:
            name2rank[name] = N.index(body)

STD_ORDER = """GetVersion,DefineClass,FindClass,FromReflectedMethod,FromReflectedField,ToReflectedMethod,GetSuperclass,IsAssignableFrom,ToReflectedField,Throw,ThrowNew,ExceptionOccurred,ExceptionDescribe,ExceptionClear,FatalError,PushLocalFrame,PopLocalFrame,NewGlobalRef,DeleteGlobalRef,DeleteLocalRef,IsSameObject,NewLocalRef,EnsureLocalCapacity,AllocObject,NewObject,NewObjectV,NewObjectA,GetObjectClass,IsInstanceOf,GetMethodID,CallObjectMethod,CallObjectMethodV,CallObjectMethodA,CallBooleanMethod,CallBooleanMethodV,CallBooleanMethodA,CallByteMethod,CallByteMethodV,CallByteMethodA,CallCharMethod,CallCharMethodV,CallCharMethodA,CallShortMethod,CallShortMethodV,CallShortMethodA,CallIntMethod,CallIntMethodV,CallIntMethodA,CallLongMethod,CallLongMethodV,CallLongMethodA,CallFloatMethod,CallFloatMethodV,CallFloatMethodA,CallDoubleMethod,CallDoubleMethodV,CallDoubleMethodA,CallVoidMethod,CallVoidMethodV,CallVoidMethodA,CallNonvirtualObjectMethod,CallNonvirtualObjectMethodV,CallNonvirtualObjectMethodA,CallNonvirtualBooleanMethod,CallNonvirtualBooleanMethodV,CallNonvirtualBooleanMethodA,CallNonvirtualByteMethod,CallNonvirtualByteMethodV,CallNonvirtualByteMethodA,CallNonvirtualCharMethod,CallNonvirtualCharMethodV,CallNonvirtualCharMethodA,CallNonvirtualShortMethod,CallNonvirtualShortMethodV,CallNonvirtualShortMethodA,CallNonvirtualIntMethod,CallNonvirtualIntMethodV,CallNonvirtualIntMethodA,CallNonvirtualLongMethod,CallNonvirtualLongMethodV,CallNonvirtualLongMethodA,CallNonvirtualFloatMethod,CallNonvirtualFloatMethodV,CallNonvirtualFloatMethodA,CallNonvirtualDoubleMethod,CallNonvirtualDoubleMethodV,CallNonvirtualDoubleMethodA,CallNonvirtualVoidMethod,CallNonvirtualVoidMethodV,CallNonvirtualVoidMethodA,GetFieldID,GetObjectField,GetBooleanField,GetByteField,GetCharField,GetShortField,GetIntField,GetLongField,GetFloatField,GetDoubleField,SetObjectField,SetBooleanField,SetByteField,SetCharField,SetShortField,SetIntField,SetLongField,SetFloatField,SetDoubleField,GetStaticMethodID,CallStaticObjectMethod,CallStaticObjectMethodV,CallStaticObjectMethodA,CallStaticBooleanMethod,CallStaticBooleanMethodV,CallStaticBooleanMethodA,CallStaticByteMethod,CallStaticByteMethodV,CallStaticByteMethodA,CallStaticCharMethod,CallStaticCharMethodV,CallStaticCharMethodA,CallStaticShortMethod,CallStaticShortMethodV,CallStaticShortMethodA,CallStaticIntMethod,CallStaticIntMethodV,CallStaticIntMethodA,CallStaticLongMethod,CallStaticLongMethodV,CallStaticLongMethodA,CallStaticFloatMethod,CallStaticFloatMethodV,CallStaticFloatMethodA,CallStaticDoubleMethod,CallStaticDoubleMethodV,CallStaticDoubleMethodA,CallStaticVoidMethod,CallStaticVoidMethodV,CallStaticVoidMethodA,GetStaticFieldID,GetStaticObjectField,GetStaticBooleanField,GetStaticByteField,GetStaticCharField,GetStaticShortField,GetStaticIntField,GetStaticLongField,GetStaticFloatField,GetStaticDoubleField,SetStaticObjectField,SetStaticBooleanField,SetStaticByteField,SetStaticCharField,SetStaticShortField,SetStaticIntField,SetStaticLongField,SetStaticFloatField,SetStaticDoubleField,NewString,GetStringLength,GetStringChars,ReleaseStringChars,NewStringUTF,GetStringUTFLength,GetStringUTFChars,ReleaseStringUTFChars,GetArrayLength,NewObjectArray,GetObjectArrayElement,SetObjectArrayElement,NewBooleanArray,NewByteArray,NewCharArray,NewShortArray,NewIntArray,NewLongArray,NewFloatArray,NewDoubleArray,GetBooleanArrayElements,GetByteArrayElements,GetCharArrayElements,GetShortArrayElements,GetIntArrayElements,GetLongArrayElements,GetFloatArrayElements,GetDoubleArrayElements,ReleaseBooleanArrayElements,ReleaseByteArrayElements,ReleaseCharArrayElements,ReleaseShortArrayElements,ReleaseIntArrayElements,ReleaseLongArrayElements,ReleaseFloatArrayElements,ReleaseDoubleArrayElements,GetBooleanArrayRegion,GetByteArrayRegion,GetCharArrayRegion,GetShortArrayRegion,GetIntArrayRegion,GetLongArrayRegion,GetFloatArrayRegion,GetDoubleArrayRegion,SetBooleanArrayRegion,SetByteArrayRegion,SetCharArrayRegion,SetShortArrayRegion,SetIntArrayRegion,SetLongArrayRegion,SetFloatArrayRegion,SetDoubleArrayRegion,RegisterNatives,UnregisterNatives,MonitorEnter,MonitorExit,GetJavaVM,GetStringRegion,GetStringUTFRegion,GetPrimitiveArrayCritical,ReleasePrimitiveArrayCritical,GetStringCritical,ReleaseStringCritical,NewWeakGlobalRef,DeleteWeakGlobalRef,ExceptionCheck,NewDirectByteBuffer,GetDirectBufferAddress,GetDirectBufferCapacity,GetObjectRefType""".split(',')
STD = {n: 4 + i for i, n in enumerate(STD_ORDER)}

known_ranks = sorted((r, n) for n, r in name2rank.items() if n in STD)
infer_rank2names = defaultdict(list)
span_ok = span_bad = 0
for (rA, nA), (rB, nB) in zip(known_ranks, known_ranks[1:]):
    sA, sB = STD[nA], STD[nB]
    if rB - rA == sB - sA and sB > sA:
        span_ok += 1
        for k in range(1, rB - rA):
            infer_rank2names.setdefault(rA + k, []).append(STD_ORDER[sA - 4 + k])
    else:
        span_bad += 1
print(f'spans ok={span_ok} bad={span_bad}; inferable ranks={len(infer_rank2names)}')

# convert inferred em-ranks to live slots: em-rank r -> body N[r] -> slots
# Need O/N: em-rank index into N list (bodies sorted). But careful: our pairs used
# old_rank->new_rank where new_rank = index into N. So infer_ranks keys are NEW
# em-ranks (from name2rank built on N). Yes.
inferred = {}
conflicts = []
for r, names in infer_rank2names.items():
    if len(names) != 1:
        continue
    name = names[0]
    body = N[r]
    slots = sorted(k for k, v in rn.items() if v == body and k < 234)
    if slots:
        inferred[name] = slots[0] if len(slots) == 1 else slots

# merge with FINAL (existing entries dominate)
merged = dict(final)
for name, slot in inferred.items():
    if name not in merged:
        merged[name] = slot
print(f'inferred extra: {len(merged) - len(final)}')
print('new functions:', {k: v for k, v in inferred.items() if k not in final})

json.dump(merged, open(r'C:\Users\Admin\Desktop\rustme\tools\slot_map_extended.json', 'w'), indent=1)

# generate header v2
lines = []
for name in sorted(merged, key=lambda n: merged[n]):
    v = merged[name]
    v = v[0] if isinstance(v, list) else v
    lines.append(f'    {name} = {v * 8},')
header = """// Auto-generated from minidump analysis (rustme_26116 dump vs old-build dump).
// %d functions. ВАЖНО: порядок слотов env-таблицы в этом форке НЕ стандартный
// (не как в jni.h) и меняется между билдами игры - регенерировать после каждого
// обновления (методика и скрипты: tools/ в корне проекта).
//
// Верификация: 8 примитивных геттеров восстановлены из хук-трамплинов (тела
// совпали 1:1), остальные - позиционное выравнивание emission-последовательности
// .text между билдами (difflib по мнемоникам, mean 0.86) + span-интерполяция
// по стандартному порядку JNI между соседними якорями (без сдвигов внутри спэна).
#pragma once
#include <cstdint>

enum class JniOffset : uint32_t {
%s
};
""" % (len(merged), '\n'.join(lines))

with open(r'C:\Users\Admin\Desktop\rustme\jni\dll\src\jvm\jvm_offsets.h', 'w', encoding='utf-8') as f:
    f.write(header)
print('written jvm_offsets.h v2 with', len(merged), 'entries')
