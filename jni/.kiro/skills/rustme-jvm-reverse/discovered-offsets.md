# Discovered offsets

All offsets, RVAs, and structural addresses identified during the rustme JVM
analysis. **Verified** = confirmed via runtime observation; **inferred** =
based on consistency across multiple probes but not directly tested for the
exact semantic.

> **Important convention.** Values in `JniOffset` / `JvmtiOffset` enums in
> `dll/src/jvm/jvm_offsets.h` are **byte offsets** within the vtable.
> Divide by 8 to get the slot index used by the JNI spec / OpenJDK source.

---

## 2026-09-03 UPDATE: new build (dump rustme_26116) — current offsets

The game updated; jvm.dll was rebuilt and the protector (Astraea) **reshuffled
the live JNIEnv table**. All values below were derived from the minidump
offline (method + scripts: `tools/` in the repo root; calibrated against the
old dump where all four RVAs reproduced the previously known values).

```
0xE6D248   JavaVM* (main_vm)                  (offsets.h::kJavaVmRva)
0xC5DCD0   expected invoke (JavaVM) vtable    (offsets.h::kInvokeTableRva) — .rdata, 3 NULLs + 5 fns
0xBD2898   pristine JNIEnv table (template)   (offsets.h::kNativeTableRva) — .rdata, lazy-init wrappers
0xE6CAF0   observed live JNIEnv vtable        (offsets.h::kObservedLiveEnvTableRva) — .data
```

Key facts about the new build:
- Runtime check: invoke vtable match = YES, live env vtable match = YES.
  ("JNIEnv vtable match = NO" is expected — live envs point at the .data copy,
  never at the .rdata template.)
- The old slot map (FindClass=209 etc.) is INVALID for this build — the
  protector reshuffles slot order per build.
- The 8 hooked slots are the primitive field getters (Get*Field). Their
  trampolines (heap) were decoded and the original bodies recovered:
  GetBooleanField=22, GetCharField=36, GetIntField=127, GetShortField=134,
  GetByteField=182, GetLongField=150, GetFloatField=196, GetDoubleField=208.
- ICF-folded pairs (both builds): GetStaticShort/CharField, GetStaticBool/ByteField,
  GetByteArrayRegion/GetBooleanArrayRegion, SetIntArrayRegion/SetFloatArrayRegion,
  GetFloatArrayRegion/GetIntArrayRegion, SetDouble/SetLongArrayRegion,
  SetCharArrayRegion/SetShortArrayRegion, GetDoubleArrayRegion/GetLongArrayRegion,
  SetBooleanArrayRegion/SetByteArrayRegion, GetShortArrayRegion/GetCharArrayRegion.
- Full recovered map (95 functions, byte offsets): `dll/src/jvm/jvm_offsets.h`.
- Runtime-verified FindClass slot for the new build: **230** (byte 1840);
  dllmain probes it after attach.

---

## Old build (dump rustme_19600) — superseded values

These are RVAs (offsets from `jvm.dll` base, which is ASLR-randomized but the
RVAs are stable across runs of the same binary).

```
0x9CFA10   Java_java_lang_ClassLoader_defineClass1      (verified — used as hook target)
0x9CFBC0   Java_java_lang_ClassLoader_defineClass2      (inferred — same prologue starts at +0x1B0 in defineClass1 dump)
0x429BA0   jvm_define_class_common / internal definer   (verified — relative call from defineClass1 at offset 0x13E)
0x4288E0   resolve_from_stream or similar deeper helper (verified — relative call from internal definer at offset 0xA5)
0x5E9920   shared "native_function not-yet-resolved" resolver stub
0xDEBF68   JavaVM* (from jvm_offsets / project's offsets.h::kJavaVmRva)
0xBEBB10   expected invoke (JavaVM) vtable
0xB628F8   expected JNIEnv vtable
0xDEC0F0   observed live JNIEnv vtable
```

## JNI vtable layout

In `dll/src/jvm/jvm_offsets.h` — already maintained by project owner. Highlights:

| Function              | Byte offset | Slot | Used by    |
| --------------------- | ----------- | ---- | ---------- |
| GetStringUTFChars     | 56          | 7    | dumper     |
| GetObjectField        | 96          | 12   | -          |
| CallStaticObjectMethodA | 104       | 13   | -          |
| NewStringUTF          | 152         | 19   | -          |
| CallObjectMethodA     | 176         | 22   | reflection |
| GetByteArrayRegion    | 600         | 75   | dumper     |
| DeleteLocalRef        | 736         | 92   | reflection |
| NewGlobalRef          | 784         | 98   | -          |
| CallIntMethodA        | 1088        | 136  | reflection |
| CallObjectMethod      | 848         | 106  | -          |
| GetMethodID           | 720         | 90   | reflection |
| GetArrayLength        | 1296        | 162  | reflection |
| GetStaticMethodID     | 1376        | 172  | reflection |
| CallVoidMethod        | 1400        | 175  | -          |
| GetObjectClass        | 1768        | 221  | -          |
| ExceptionOccurred     | 1608        | 201  | dumper     |
| ExceptionClear        | 1616        | 202  | dumper     |
| FindClass             | 1672        | 209  | dumper     |
| ReleaseStringUTFChars | 1864        | 233  | dumper     |
| GetObjectArrayElement | 248         | 31   | reflection |

`RegisterNatives` slot is NOT yet identified — would be useful if added.

## Method struct layout (in this fork)

Roughly inferred from probing 9 known Java methods (Object.toString/hashCode/equals,
String.length/hashCode/isEmpty, HashMap.size, ArrayList.size, Integer.intValue).

```
Method @ X
   +0x00  vptr -> InstanceKlass vtable in jvm.data (e.g. 0x7ffa04a1e388)
   +0x08  packed flags (low 32) + sometimes high bits
   +0x10  _method_data (MethodData*, lazy — null until profiler runs)
   +0x18  _method_counters (MethodCounters*, lazy — null until first invocation)
   +0x20  _i2i_entry (interpreter entry, ext.exec in CodeCache)
   +0x28  packed (vtable index?)
   +0x30  _adapter or related (ext.data, sometimes shared across methods)
   +0x38  packed
   +0x40  _from_compiled_entry (ext.exec, shared)
   +0x48  _constMethod  ← VERIFIED. Always non-null, always ext.data, unique per method
   +0x50  _from_interpreted_entry or related ext.exec
   +0x58  another shared interpreter entry ext.exec
   +0x60  _native_function (jvm.text) — only for NATIVE methods
   +0x68+ varies (more interpreter entries, padding)
```

`sizeof(Method)` for non-native ≈ 0x60 bytes (96 bytes). Native methods get
+16 bytes appended for `_native_function` + `_signature_handler`.

## ConstMethod struct layout (in this fork)

Inferred from probing ConstMethod via `Method[+0x48]` deref. Base size ≈
0x48 (72 bytes). After the fixed header, the actual bytecode and small tables
follow inline.

```
ConstMethod @ Y
   +0x00  _fingerprint (often 0)
   +0x08  some pointer (varies per method, sometimes null — possibly stackmap_data)
   +0x10  packed u4+ — likely _flags + something
   +0x18  u4 (possibly idnum or counter — values like 0x18, 0x14, 0x188)
   +0x20  more packed
   +0x28  more packed
   +0x30  _constants (ConstantPool*)  ← VERIFIED. SHARED across all methods of the same class
   +0x38  packed (max_stack? max_locals? size_of_parameters?)
   +0x40  start of bytecode (length = some u2 field above) — encoded in rustme format
   +0x40+code_size  LineNumberTable / exception table / etc.
```

For native methods, `code_size = 0` so `+0x40` immediately holds Method or
padding.

## Class oop (java.lang.Class instance) layout

```
Class oop @ Z (heap address, e.g. 0x5de0007e0 — compressed heap range)
   +0x000  markOop
   +0x008  compressed klass (4 bytes + padding)
   +0x010  _klass (InstanceKlass*)  ← VERIFIED via "differs per class, all ext.data" heuristic
   +0x018  _array_klass (Klass* of [T])
   +0x020-0x05F  reserved / various
   +0x060+  embedded static fields (variable per class)
```

## InstanceKlass struct layout

Verified offsets:

```
InstanceKlass @ K (ext.data, e.g. 0x7c0042b58)
   +0x000  vptr -> 0x7ffa04a03b08 (InstanceKlass vtable in jvm.dll .rdata)
   +0x008  packed (possibly _layout_helper + compressed _java_mirror)
   +0x010  null OR _super klass
   +0x018  packed flags + access
   +0x058  self-reference (probably _klass or _java_mirror tied)
   +0x060  super or array_klass
   +0x0B0  some count + ptr
   +0x0B8  Array<?> pointer (Class oops list?)
   +0x0C0  another Klass*
   +0x0C8  packed
   +0x0D0  some metaspace allocation
   +0x0E8  packed (init_state etc.)
   +0x108  metaspace ptr
   +0x110  packed (this_class_index/maybe)
   +0x118  packed
   +0x120  another ptr (could be _method_ordering or _default_methods)
   +0x128  packed
   +0x150  metaspace ptr — maybe _local_interfaces
   +0x170  metaspace ptr → Array<u1> at +0x10 of which are CP tags  ← VERIFIED (tags 0x01, 0x07, 0x0a, 0x04 visible)
   +0x178  another small metaspace ptr (mostly null)
```

`InstanceKlass+0x170` does NOT directly point to ConstantPool — it points to
an `Array<u1>` (the `_tags` array). The ConstantPool itself is reachable
either by:
- `ConstMethod+0x30` (any method of the class) — direct
- Walking from InstanceKlass forward — exact offset of `_constants` not yet
  identified, but it's in the same metaspace allocation block as the
  Method/ConstMethod set (typically near them in metaspace).

## ConstantPool layout (NOT yet probed in detail)

What we know:
- Address obtained from `ConstMethod[+0x30]`
- Shared by all methods of one InstanceKlass
- `_tags` lives separately, reachable as `InstanceKlass[+0x170] → Array<u1>`
- Visible CP tag bytes have valid JVMS values: 0x01 (Utf8), 0x07 (Class),
  0x08 (String), 0x09 (Fieldref), 0x0a (Methodref), 0x0c (NameAndType), 0x04
  (Float — suspicious, may be packed marker)
- Length of Object's `_tags` array starts with `7c 00 00 00` = 124 entries
  (likely)

Standard HotSpot 8 ConstantPool fields (probably at similar offsets here):
- `+0x00` vptr
- `+0x08` `_tags Array<u1>*`
- `+0x10` `_cache ConstantPoolCache*`
- `+0x18` `_pool_holder InstanceKlass*`
- `+0x20` `_operands Array<u2>*`
- `+0x28` `_resolved_klasses Array<Klass*>*`
- `+0x30` `_length int`
- `+0x34` various
- Followed by inline `_entries[_length-1]`, each `jlong` (8 bytes)

NEEDS PROBING. The cheap-and-quick step from the current state.

## Per-class metaspace allocation pattern

For one class (e.g. java.lang.Object), the JVM allocates a contiguous(ish)
metaspace block containing:
- InstanceKlass struct
- ConstantPool struct + inline entries
- `_tags Array<u1>`
- Per-method ConstMethod + Method pairs (each pair takes `0x48 + bytecode +
  attrs + sizeof(Method)` bytes, with Method always after its ConstMethod)
- Symbol* objects for names (in a different allocation usually)

For Object, the block starts around address `0x...5C000000` and methods/CP
all live in that range. This makes it easy to identify a "metaspace
allocation for this class" by address-range proximity.
