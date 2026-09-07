# Working techniques

The mechanics that actually do something useful against rustme.

## The Method[+0x60] slot patch (THE central trick)

This is the workaround for the MEM_MAPPED jvm.dll problem — we cannot inline-hook
jvm.dll, but we can rewrite a function pointer that the JVM reads on every
method invocation.

### How HotSpot dispatches a native method call

1. JVM compiles a Java→native call site into a per-method **stub** in CodeCache. The stub lives in regular `VirtualAlloc(PAGE_EXECUTE_READWRITE)` memory.
2. The stub reads a function pointer from a fixed offset in the corresponding `Method` struct.
3. The stub jumps to that function pointer (= `_native_function`, the C function implementing the native).

Crucially: the stub reads the pointer **every time**, not just once. So if we change the pointer between calls, the next call goes to our function.

### How we find the Method to patch

The `Method` struct for `ClassLoader.defineClass1` is found by:
1. `FindClass(env, "java/lang/ClassLoader")` → jclass
2. `GetStaticMethodID(env, cls, "defineClass1", "(Ljava/lang/String;[BIILjava/security/ProtectionDomain;Ljava/lang/String;)Ljava/lang/Class;")` → jmethodID
3. The jmethodID is `Method**` (HotSpot's indirect handle). Dereference once → `Method*`.

### How we know `_native_function` is at `+0x60`

Probed by comparing two native methods' `Method` structs and looking for offsets where:
- Value differs per method
- Value points into `jvm.dll` `.text` (executable)

For native methods only one such offset exists — `+0x60`. For non-native methods, `+0x60` is null/unused (their dispatch goes through `_i2i_entry` at `+0x20` instead). Bytecode of non-native methods is stored separately in `ConstMethod` accessible via `Method[+0x48]`.

### Resolver-then-real pattern

A subtle catch: the first time the JVM dispatches `defineClass1` after process startup, `Method[+0x60]` holds a **shared resolver stub** (RVA `0x5E9920` in our observation) — not the real `native_function`. The resolver:
1. Looks up the symbol internally
2. **Patches `Method[+0x60]` itself** to point to the real function
3. Tail-calls the real function

We must NOT save the resolver as the "original" pointer. If we do, our hook will try to invoke the resolver with `defineClass1` JNI args, which the resolver doesn't expect → crash.

**Detection.** The resolver's first bytes are recognizable:

```
48 89 54 24 10    mov [rsp+0x10], rdx
4c 89 44 24 18    mov [rsp+0x18], r8
4c 89 4c 24 20    mov [rsp+0x20], r9
57                push rdi
48 83 ec 60       sub rsp, 0x60
```

vs the real `defineClass1` which saves callee-saved registers (rbx, rbp, rsi, rdi, r12-r15) instead of arg registers, and allocates a 0x4E0-byte stack frame. The `looks_like_resolver()` helper in `class_dumper.cpp` checks for the resolver's `mov [rsp+0x10], rdx` pattern.

**Workaround.** Poll `Method[+0x60]` every 50ms until `!looks_like_resolver(value)`. The JVM patches it within ~100ms-2s after first defineClass1 invocation (which happens early — system class loading triggers it). Once polled-out, we have the real `native_function` address.

### The patch itself

```cpp
g_orig_defineClass1 = (DefineClass1Fn)original;        // save for trampoline
InterlockedExchangePointer(slot, &hook_defineClass1);  // patch in our hook
```

That's literally one 8-byte write. No `VirtualProtect`, no inline hook
machinery, no instruction-length disassembly. The slot is in metaspace which
is plain writable allocation.

### What the hook does

```cpp
jclass hook_defineClass1(env, cls, loader, name, byteArray, off, len, pd, source) {
    // 1. capture bytes BEFORE original (in case JVM modifies the byte[])
    std::vector<uint8_t> buf;
    seh_capture(env, byteArray, off, len, buf);

    // 2. call the saved original
    jclass result = g_orig_defineClass1(env, cls, loader, name, byteArray, off, len, pd, source);

    // 3. get real class name via reflection (name argument is always null in rustme)
    std::string className;
    seh_class_name(env, result, name, className);

    // 4. save bytes to disk under proper package tree
    seh_save_bytes(className, buf);

    // 5. dump structure (.java skeleton) via JNI reflection
    seh_struct(env, result, idx);

    return result;
}
```

Plus a `t_in_hook` thread-local guard against recursion if our reflection
calls happen to trigger another defineClass1.

## Watcher-based early injection

The injector polls every 10ms for a process named `rustme.exe`. When the PID appears, it immediately does the standard `OpenProcess` + `VirtualAllocEx` + `WriteProcessMemory` + `CreateRemoteThread(LoadLibraryW)` sequence. Total latency from process spawn to DLL injected: ~50-100ms.

Why this matters: if we inject after the launcher's classes are loaded, our `defineClass1` hook only catches LambdaForm anonymous classes (the JVM keeps generating those during normal execution). With watcher-style early injection, we catch the Minecraft + RML loader class loads from near the start.

Implementation in `injector/src/injector.cpp`. Default mode is watch; `--once` for single-shot.

## Reflection-based class name + structure extraction

Even though `defineClass1` receives `name=null`, the JVM internally figures out the class name during parsing. After `defineClass1` returns the `jclass`, we can call `Class.getName()` to get the real name.

The full reflection chain (cached method IDs at init time) gives us:

| Reflection API | Returns | Used for |
| --- | --- | --- |
| `Class.getName()` | String FQCN | filename + .java header |
| `Class.getModifiers()` | int bitfield | class keyword + flags |
| `Class.getSuperclass()` | Class | `extends` |
| `Class.getInterfaces()` | Class[] | `implements` |
| `Class.getDeclaredFields()` | Field[] | field list |
| `Class.getDeclaredMethods()` | Method[] | method signatures |
| `Class.getDeclaredConstructors()` | Constructor[] | ctor signatures |
| `Field.getName/getType/getModifiers` | | per-field |
| `Method.getName/getReturnType/getParameterTypes/getModifiers` | | per-method |
| `Constructor.getParameterTypes/getModifiers` | | per-ctor |

For each `Class[]` returned (e.g. parameter types), iterate via `GetArrayLength` + `GetObjectArrayElement`. Type names from `Class.getName()` follow the standard format:
- `int` → `"int"`
- `int[]` → `"[I"`
- `String[]` → `"[Ljava.lang.String;"`

The `prettify_type` helper in `class_dumper.cpp` converts these to Java syntax (`int[]`, `java.lang.String[]`).

## Output layout

Captured bytes go to `C:\Logs\dumped_classes\<package_path>\<SimpleName>.class` — same package-tree as Java source. Structure skeletons go to `C:\Logs\structure\<package_path>\<SimpleName>.java`. Names with `$` (nested classes) keep `$` (valid in Windows filenames). Anonymous-or-array names fall back to flat layout with `anon_NNNNN.class`.

Collision handling: if a file already exists at the target path (e.g., same FQCN loaded twice by different ClassLoaders), suffix `__2`, `__3`, ... are appended.

## What WOULD be different if jvm.dll were normal MEM_IMAGE

Then we'd have:
- Resolve `Java_java_lang_ClassLoader_defineClass1` via `GetProcAddress` (export still there in standard JVM)
- Install a 14-byte JMP `[rip+0]` inline hook on its prologue (LDE in `dll/src/hook/x64_lde.cpp` is ready for this)
- Save the prologue bytes to a trampoline page
- Done

This is the path implemented in `dll/src/hook/inline_hook.cpp`, kept around for reference but unused in production for rustme.


## Decoding the encrypted constant pool (UTF8 layer)

The protected classes are not standard `.class` even after the outer transform.
The decrypted class buffer starts with a custom marker `CA FE BA BE 46 7C 80 5F`
(not `CAFEBABE 0000 00xx`), and its constant pool is **obfuscated**: the UTF8
string *content* is plaintext, but the surrounding CP *metadata* (tags,
lengths, indices) is transformed. This is the layer the project owner referred
to as "the constant pool is encrypted".

### How the UTF8 entries are encoded

By cribbing on known plaintext (every UTF8 entry's byte content is readable, so
its true length is known), the UTF8 entry encoding falls out cleanly. It is a
fixed, global encoding (same across all classes):

```
standard Utf8 entry :  01            <u2 length>        <bytes>
rustme  Utf8 entry  :  F4            <u2 length_enc>    <bytes>   (content plaintext)

  tag      : real 0x01  stored as 0xF4
  length   : length_enc = (real_length + 0x5BB9) mod 0x10000   (16-bit ADD, with carry)
```

Verified examples (single class, but constants are global):
- `java/lang/Object`  len 0x10 -> stored `5B C9`  (0x10 + 0x5BB9 = 0x5BC9)
- `<init>`            len 0x06 -> stored `5B BF`
- a 0x4C-byte string  -> stored `5C 05`  (0x4C + 0x5BB9 = 0x5C05, carry into high byte)

So to recover every UTF8 entry of a class buffer, scan for tag byte `0xF4`,
read the next `u2`, subtract `0x5BB9` to get the string length, then read that
many bytes (plaintext). This yields the class name, superclass name, every
field/method name and descriptor, annotation type names, `SourceFile`,
`InnerClasses`, etc. — the full string-level API/skeleton of the real class
(NOT via reflection — straight from the decrypted CP bytes).

### Tag transform (partial)

For the entry-type byte, `real = 0xF5 - stored` fits the tags seen so far:
- `F4 -> 0x01` Utf8
- `ED -> 0x08` String
- `EE -> 0x07` Class

…but it does NOT cleanly explain every non-UTF8 tag, and walking entries with
standard JVMS sizes does not yet align between strings. So the **non-UTF8 CP
entries (their tag map, the `u2` CP indices, and `cp_count`) use an
as-yet-unresolved encoding**. Until that is cracked, the buffers cannot be
turned back into valid standard constant pools — only the UTF8 string list is
recoverable.

### Why this matters / what is still missing

- DONE: full UTF8 pool of every loaded protected class (class/field/method
  names, descriptors, signatures, annotation names).
- TODO for a real rebuildable `.class`:
  1. non-UTF8 CP entry encoding (tag map for all 17 tags, `u2` index transform,
     `cp_count`). Anchor on UTF8 positions and use `this_class -> Class.name_index`
     (which must point to the known class-name UTF8) to solve the index transform.
  2. the field/method/attribute structure (same metadata encoding as above).
  3. method bodies — the `Code` attribute bytecode is in rustme's remapped
     interpreter format (separate, large reverse-engineering effort).
