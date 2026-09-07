---
inclusion: always
---

# Project context

This workspace is **jnicodex** — a reverse-engineering project targeting the
**rustme** Minecraft launcher's custom-forked HotSpot JVM. Anything you do
here will involve:
- Writing/modifying C++ code in `dll/` (the injected DLL) and `injector/` (the watcher injector)
- Interacting with the rustme JVM via JNI + metaspace pointer manipulation
- Working with encrypted class files and a custom bytecode format

## Don't start from scratch

A comprehensive knowledge base lives in `.kiro/skills/rustme-jvm-reverse/`.
Before doing anything substantial, **activate the `rustme-jvm-reverse` skill**
to load:
- Discovered memory offsets (Method/ConstMethod/Class/InstanceKlass) — `discovered-offsets.md`
- Working hook mechanism (Method[+0x60] slot patch) — `working-techniques.md`
- All 9 protection layers of rustme and which are/aren't bypassed — `protection-layers.md`
- Staged plan for further work — `roadmap.md`
- Chronological log of what was tried and what failed — `investigation-log.md`
- Quick reference (paths, commands, offsets, common pitfalls) — `quick-reference.md`

Activate the skill at session start to avoid re-discovering things or
attempting approaches that are already known to fail (like inline-hooking
the MEM_MAPPED `jvm.dll`).

## Key facts to remember without reading the full skill

- `jvm.dll` is **MEM_MAPPED**, not MEM_IMAGE — `VirtualProtect` is blocked, no inline hooks possible
- Hook mechanism: rewrite `Method[+0x60]` pointer in metaspace (NOT inline patch in jvm.dll)
- JNI vtable slots are shuffled — see `dll/src/jvm/jvm_offsets.h::JniOffset` (byte offsets, /8 for slot index)
- defineClass1 gets `name=null` — recover name via `Class.getName()` reflection on the returned jclass
- Captured class bytes are NOT CAFEBABE — they're in rustme's custom encrypted format
- Bytecode in `ConstMethod+0x40` is also non-standard — would need interpreter dispatch-table RE to decode

## Build & run

```cmd
cd dll      && build_dll.bat
cd injector && build_injector.bat
build\injector\inject.exe --no-pause   :: watcher mode, then launch rustme
```

Outputs go to `C:\Logs\` (trace log, dumped encrypted classes, structure
skeletons).
