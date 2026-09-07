---
name: rustme-jvm-reverse
description: Knowledge base and tooling guide for reverse-engineering the custom forked HotSpot JVM used by the rustme Minecraft launcher. Includes protection-layer analysis, discovered memory offsets, working hook mechanism, build/run instructions, and known-good paths forward.
keywords:
  - rustme
  - hotspot
  - jvm reverse engineering
  - class dump
  - jni hook
  - minecraft
  - rustme-launcher
  - jvm internals
  - method patching
  - metaspace
---

# rustme JVM Reverse Engineering

This skill captures everything learned from the jnicodex project — a custom DLL that hooks `ClassLoader.defineClass1` in the **rustme** Minecraft launcher's heavily-modified HotSpot JVM, dumps loaded classes and extracts structural metadata via JNI reflection.

## When to use this skill

Activate when the conversation mentions:
- The `rustme` Minecraft launcher
- A custom-forked HotSpot JVM with stripped exports / disabled JVMTI
- Dumping classes from a JVM that rejects standard CAFEBABE
- The `jnicodex` workspace at `c:\Users\acc0u\OneDrive\Рабочий стол\jnicodex`
- Any of these phrases: `defineClass1 hook`, `metaspace probe`, `Method[+0x48]`, `MEM_MAPPED jvm.dll`

## What rustme is

Russian Minecraft 1.12.2 launcher with a custom **forked HotSpot** JVM that runs the game. Installation path:
```
C:\Users\acc0u\AppData\Roaming\rustme-launcher\
  ├─ java\prod-a\bin\rustme.exe         # the JVM executable
  └─ java\prod-a\bin\server\jvm.dll     # modified HotSpot
  └─ profiles\prod-a\
       ├─ classes.jar                    # 29406 entries with hex names, encrypted
       ├─ minecraft.jar                  # same encrypted format
       └─ RMLLoader.jar                  # 1 entry: ru/meproject/Main.class (the loader)
```

## Protection layers (what makes this hard)

1. **JVMTI is publicly disabled.** `GetEnv(JVMTI_VERSION_*)` returns null. The interface table is still in the binary (we have its offsets in `jvm_offsets.h::JvmtiOffset`) but no live `JvmtiEnv*` is registered.
2. **JNI vtable slots are shuffled** vs standard OpenJDK order. `FindClass` is at slot 209 (byte offset 1672), not the standard slot 6. Full mapping in `dll/src/jvm/jvm_offsets.h`.
3. **jvm.dll exports stripped.** Only 6 exports remain: `IIiiI`, `JLI_LauncherMain`, `JNI_CreateJavaVM`, `JNI_GetCreatedJavaVMs`, `JNI_GetDefaultJavaVMInitArgs`, `NvOptimusEnablement`. No `Java_java_lang_ClassLoader_defineClass1`.
4. **jvm.dll uses MEM_MAPPED, not MEM_IMAGE.** Custom loader maps the DLL with restricted file-mapping permissions. `VirtualProtect` and `NtProtectVirtualMemory` both reject `PAGE_EXECUTE_READWRITE` on .text pages with `INVALID_PAGE_PROTECTION (0xC0000045)`. Inline hooking jvm.dll's code IS NOT POSSIBLE through standard means.
5. **PE on disk is hollow.** Most sections (including `.text`) have `rptr=0, rsize=0`. Real code lives in a custom section `.rD\` and is unpacked at process startup.
6. **Custom classfile format.** Files (`classes.jar/minecraft.jar` entries) do not start with `CA FE BA BE`. Entropy ≈ 7.78 bits/byte (near max), indicating heavy encryption or transformation. The JVM's `ClassFileParser` has been replaced to accept this format — `CAFEBABE` constant doesn't appear anywhere in 128 KB scan of the define-class call chain.
7. **Custom bytecode format inside `ConstMethod`.** Even after extracting from in-memory `ConstMethod`, the bytecode bytes are NOT standard JVMS opcodes. `Object.toString` is 24 bytes vs standard 36, with repeated `0x9f`/`0x29` patterns inconsistent with normal bytecode. The interpreter dispatch table has been remapped — likely using 1-byte CP indices and possibly permuted opcode numbers.
8. **`defineClass1` called with `name=null`.** Java-side URLClassLoader equivalent was modified to strip the class name when calling native `defineClass1`. We recover names later via reflection on the returned `jclass`.

## What does work

The trick that broke through: **`Method[+0x60]` slot patch**. The Method struct in metaspace has a slot at offset 0x60 holding the resolved `native_function` pointer. The per-method CodeCache stub reads this slot on every call. Since metaspace is regular VirtualAlloc memory (not MEM_MAPPED), we can **atomically write a new pointer there** — no `VirtualProtect` needed. This redirects all calls to defineClass1 to our hook function. The hook captures bytes + calls the saved original.

The full mechanism is documented in [working-techniques.md](working-techniques.md). All discovered memory offsets are listed in [discovered-offsets.md](discovered-offsets.md).

## What's been built (current state)

```
jnicodex/
├── dll/                        # injected DLL ("jni_rva_check.dll")
│   └── src/
│       ├── dllmain.cpp         # entry, JVM probe, kicks off class_dumper_init
│       ├── class_dumper.cpp    # MAIN: defineClass1 hook, structure extractor, probes
│       ├── hook/inline_hook.*  # 14-byte JMP detour (unused — blocked by MEM_MAPPED)
│       ├── hook/x64_lde.*      # length disassembler (unused for now)
│       ├── jvm/jvm_offsets.h   # JNI/JVMTI vtable offsets (byte offsets, /8 for slot)
│       ├── jvm/jni_min_types.h # minimal jni.h replacement
│       └── ...
├── injector/                   # watcher injector (CreateRemoteThread+LoadLibrary)
│   └── src/injector.cpp        # polls for rustme.exe, injects ASAP
└── build/                      # outputs
    ├── dll/jni_rva_check.dll
    └── injector/inject.exe
```

Output during operation:
- `C:\Logs\jni_rva_check.log` — runtime trace
- `C:\Logs\dumped_classes\<pkg>\<Name>.class` — encrypted bytes captured from defineClass1
- `C:\Logs\structure\<pkg>\<Name>.java` — Java-like skeleton from reflection
- `C:\Logs\defineClass1_bytes.bin` (8 KB) — native_function code dump
- `C:\Logs\internal_definer_bytes.bin` (16 KB) — jvm_define_class_common
- `C:\Logs\deeper_helper_bytes.bin` (128 KB) — define-class call chain

## How to build & run

```cmd
cd dll      && build_dll.bat        :: requires VS BuildTools x64
cd injector && build_injector.bat
```

```cmd
:: start watcher BEFORE launching the game
build\injector\inject.exe --no-pause
:: then click "Play" in rustme launcher
:: rustme.exe spawns -> watcher injects in <100ms -> dump starts
```

CLI options for the injector: `--once` (single-shot), `--keep` (continue watching after success), `--interval <ms>` (poll period, default 10).

## What's NOT achievable without significant additional RE

- **Standard CAFEBABE-format .class files with decompilable JVMS bytecode.** Would require:
  - Reversing the rustme bytecode interpreter dispatch table (handler-pattern matching vs OpenJDK reference)
  - Building a `rustme-opcode → JVMS-opcode` permutation table
  - Operand-size translation (likely 1-byte → 2-byte CP indices)
  - Estimated 3-5 days of focused RE work
- **Walking the encrypted classfile format from disk.** Same effort, parallel problem.

## What's the cheap next step

Add a ConstantPool walker (using `ConstMethod+0x30 -> ConstantPool*` and `InstanceKlass+0x170 -> tags Array<u1>*` that we already identified). CP entries are plaintext — gives all string literals, class refs, method refs per class. Output as `.cp.txt` alongside the `.java` skeleton. **Hours of work, very high info density.**

After that, the next milestone is the **opcode-mapping reverse**:
1. Find dispatch table in jvm.dll (signature `48 0F B6 ?? 48 FF C? 41 FF 24 C5`)
2. For each entry (256 handlers), match first N bytes against known OpenJDK handler patterns
3. Build permutation table
4. Translate captured ConstMethod bytecode to standard JVMS

See [roadmap.md](roadmap.md) for the staged plan.

## Detailed references

- [protection-layers.md](protection-layers.md) — every anti-tamper measure, in detail
- [discovered-offsets.md](discovered-offsets.md) — JNI vtable, Method/ConstMethod/Class/InstanceKlass offsets
- [working-techniques.md](working-techniques.md) — Method[+0x60] slot patch, polling resolver, reflection chain
- [roadmap.md](roadmap.md) — staged plan from current state to full reconstruction
- [investigation-log.md](investigation-log.md) — chronological summary of what was tried and what failed
