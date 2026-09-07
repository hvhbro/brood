# rustme JVM — Protection layers

A summary of every anti-tamper / anti-debug technique observed during the
reverse-engineering session, with the workaround for each layer where one
exists.

## 1. JVMTI publicly disabled

**Symptom.** `(*vm)->GetEnv(vm, &env, JVMTI_VERSION_1_2)` returns `JNI_EVERSION`. Standard JVMTI agents won't load.

**Root cause.** The JVM's `GetEnv` dispatcher was patched to reject any JVMTI version. The underlying `JvmtiEnvBase` and the interface table itself still exist in the binary — verified by the user's `JvmtiOffset` enum which has plausible function offsets (`GetBytecodes = 592`, `GetClassMethods = 408`, etc.).

**Workaround.** None used. Could potentially be circumvented by scanning data sections of `jvm.dll` for a live `JvmtiEnv*` and calling functions directly via the vtable, but it's tricky because no agent has actually registered an env — so JVMTI state structures might not be initialized.

## 2. JNI vtable slots shuffled

**Symptom.** Standard JNI function numbers don't map to expected functions. `envVTable[6]` (which should be `FindClass` per JNI spec) is something else.

**Root cause.** The JVM exports a custom-ordered JNI interface table.

**Workaround.** The mapping was reverse-engineered manually by the project owner and lives in `dll/src/jvm/jvm_offsets.h::JniOffset`. Numbers in that enum are **byte offsets** within the vtable; divide by 8 to get the slot index. Confirmed working entries include `FindClass=1672`, `GetStringUTFChars=56`, `GetMethodID=720`, `GetStaticMethodID=1376`, `CallObjectMethodA=176`, `CallIntMethodA=1088`, `GetArrayLength=1296`, `GetObjectArrayElement=248`, etc.

## 3. jvm.dll exports stripped

**Symptom.** `dumpbin /exports jvm.dll` shows only 6 exports — `IIiiI` (custom), `JLI_LauncherMain`, `JNI_CreateJavaVM`, `JNI_GetCreatedJavaVMs`, `JNI_GetDefaultJavaVMInitArgs`, `NvOptimusEnablement`. None of the usual `Java_*` natives.

**Root cause.** PE export table edited to hide named JNI natives. The functions still exist inside `.text`, just not enumerable.

**Workaround.** `GetProcAddress("Java_java_lang_ClassLoader_defineClass1")` fails. Instead, we find the function dynamically via the Method-struct probe (see [working-techniques.md](working-techniques.md)).

## 4. jvm.dll loaded as MEM_MAPPED, not MEM_IMAGE

**Symptom.** `VirtualProtect(.text_page, PAGE_EXECUTE_READWRITE)` fails with `ERROR_INVALID_PARAMETER (87)`. `NtProtectVirtualMemory` fails with `STATUS_INVALID_PAGE_PROTECTION (0xC0000045)`. `WriteProcessMemory` also fails. Page type from `VirtualQuery` reports `Type=0x40000 (MEM_MAPPED)` instead of the usual `Type=0x1000000 (MEM_IMAGE)`.

**Root cause.** The launcher loads `jvm.dll` via manual `CreateFileMapping`+`MapViewOfFile` with restricted file-mapping flags, bypassing the normal PE loader. Windows refuses to make MEM_MAPPED file-backed pages writable when the mapping object wasn't opened with `FILE_MAP_COPY`/`FILE_MAP_WRITE`.

**Implication.** **No inline hooking of `jvm.dll` code is possible** through any standard API. Tried:
- `VirtualProtect(PAGE_EXECUTE_READWRITE)` → fails
- `VirtualProtect(PAGE_EXECUTE_WRITECOPY)` → fails
- `VirtualProtect(PAGE_READWRITE)` / `PAGE_WRITECOPY` → fails
- `NtProtectVirtualMemory` direct call → fails (same kernel rejection)
- `WriteProcessMemory(GetCurrentProcess(), ...)` → fails (read-only file mapping)

All five protection flags + both API entry points were tried in `dll/src/hook/inline_hook.cpp`.

**Workaround.** **Method[+0x60] slot patch** in metaspace. Instead of inline-hooking jvm.dll code, we rewrite an 8-byte function pointer that lives in metaspace (regular writable VirtualAlloc memory). The per-method dispatch stub in CodeCache reads this pointer on each invocation. Details in [working-techniques.md](working-techniques.md).

## 5. PE file on disk is hollow

**Symptom.** `dumpbin /SECTIONS jvm.dll` shows nearly all sections (including `.text`) with `rptr=0, rsize=0`. The on-disk file is only ~12 MB while the loaded image is ~32 MB.

**Root cause.** Only a custom section `.rD\` (at `rptr=0xA00`, `rsize=0xAF9800`) contains data on disk. At runtime, the custom loader unpacks `.rD\` into the standard section virtual addresses.

**Implication.** Static analysis of the file is useless. All RE must happen against the in-memory image of the running process.

## 6. Custom classfile format

**Symptom.** Bytes received by `defineClass1` do not start with `CA FE BA BE`. Each loaded class begins with different random-looking bytes. Entropy of the data ≈ 7.78 bits/byte (out of 8); all 256 byte values are used.

**Root cause.** The JVM's `ClassFileParser` is fully replaced with a custom parser that accepts a different format. **No `CAFEBABE` magic check exists anywhere** in the define-class call chain — verified by scanning 128 KB of the relevant `jvm.dll` code around `Java_java_lang_ClassLoader_defineClass1`, `jvm_define_class_common`, and the deeper helper at RVA 0x429BA0.

**Implication.** The on-disk encrypted bytes can NOT be decoded by patching the magic header. Standard tools (`javap`, CFR, Procyon) reject them.

**Workaround.** None for the raw format. But after the JVM has parsed the class internally, the C++ `InstanceKlass` / `ConstantPool` / `Method` / `ConstMethod` structures in metaspace use the standard HotSpot layouts (with minor offset differences) — we can read those.

## 7. Custom bytecode format inside ConstMethod

**Symptom.** Even after extracting bytecode from `ConstMethod+0x40` (where standard HotSpot stores it), the bytes are NOT recognizable JVMS opcodes.

Example: `java.lang.Object.toString` ConstMethod bytecode is 24 bytes:
```
e3 9f 9e 82 03 9f 9f eb 29 9e 9f 29 9d 9f 29 9c 9f 77 9f 29 9c 9f eb 29
```
Standard OpenJDK 8 `Object.toString` compiles to ~36 bytes starting with `bb 00 27 59 b7 00 28 2a b6 00 29 ...` — totally different.

**Root cause hypothesis.** The interpreter dispatch table was remapped. Likely changes:
- Opcode numbers permuted (e.g. JVMS `0xb6` invokevirtual mapped to rustme `0x9f`)
- 2-byte CP indices compressed to 1 byte (would explain the shorter total)
- Possibly some merged "opcode+operand" compound instructions

**Implication.** Even with the in-memory ConstMethod accessible, the bytecode itself can't be fed to a standard decompiler. **Some form of opcode-table reverse is required** to get standard JVMS bytecode.

**Workaround paths.**
- **(easier)** Identify the dispatch table in jvm.dll, match each handler's prologue against known HotSpot handler patterns, derive the permutation. ~days of work.
- **(harder)** If new compound opcodes exist, they have to be analyzed individually.

## 8. defineClass1 called with name = null

**Symptom.** The `jstring name` argument of `Java_java_lang_ClassLoader_defineClass1` is always `null`. Default behavior in the dumper would write every class as `anon_NNNNN.class`.

**Root cause.** Java-side `URLClassLoader` (or its equivalent) was patched to pass `null` for the `name` parameter, even though the standard implementation always provides one.

**Workaround.** Call the original `defineClass1` first, then run `Class.getName()` reflection on the returned `jclass`. The returned Class object has its name set correctly internally. This is implemented in `class_name_via_reflection()` in `class_dumper.cpp`.

## 9. JVMTI-class observation chain

There's an additional softer protection: the Java-side launcher passes class data through what we believe is RML's own decryption pipeline before handing it to `defineClass1` — but that path is *output* of the launcher's own logic. Since the JVM accepts the bytes, the "input encryption" and "JVM accepted format" are the same; nothing extra to defeat at the native boundary.

## Summary table

| Layer                              | Bypass status                                   |
| ---------------------------------- | ----------------------------------------------- |
| JVMTI public API disabled          | not bypassed; manual `JvmtiEnv*` lookup possible |
| Shuffled JNI vtable                | done — offsets in `jvm_offsets.h`               |
| Stripped exports                   | done — Method-struct probe finds functions      |
| MEM_MAPPED jvm.dll                 | sidestepped — Method[+0x60] slot patch          |
| Hollow PE on disk                  | irrelevant — RE the in-memory image             |
| Custom classfile format            | sidestepped — read parsed metaspace structures  |
| Custom bytecode                    | **NOT bypassed** — opcode RE required           |
| null `name` in defineClass1        | done — `Class.getName()` reflection             |
