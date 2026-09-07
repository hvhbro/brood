# Investigation log — chronological summary of approaches tried

This is the historical record of what was attempted and what was learned at
each step. Useful context for understanding *why* the current design is the
way it is, and what dead-ends to avoid re-trying.

## 1. Initial state

Project already had:
- A DLL that probes JNI/JVMTI vtables in jvm.dll
- 234 slots of the JNIEnv vtable already enumerated in `dll/src/jvm/jvm_offsets.h`
- A basic injector
- ~108 LambdaForm classes previously dumped (suggesting an earlier
  defineClass hook existed but only fired after process startup)
- The `dll/src/jvm/jvm_offsets.h` lists offsets in **bytes** not slots
  (divide by 8 for slot index — confirmed by user)

## 2. Watcher injector

**Problem.** Previous dumps were all `LambdaForm_*` — late classes generated at runtime, after the actual Minecraft classes were already loaded.

**Solution.** Rewrote injector into watcher mode (polls for `rustme.exe` every 10ms, injects immediately on first sighting). Brings injection latency from "manual seconds" to "<100ms after process spawn", giving us the launch class loads.

## 3. Inline hook attempt on jvm.dll

**Plan.** Find `Java_java_lang_ClassLoader_defineClass1` in jvm.dll (the standard JNI export), install 14-byte JMP detour.

**What broke.** Two things:
1. `dumpbin /exports jvm.dll` showed only 6 exports total. `Java_*` names stripped. We could find the function by other means.
2. Even after finding the function via Method-struct probe, `VirtualProtect(.text page, PAGE_EXECUTE_READWRITE)` failed with `INVALID_PARAMETER (87)`. Tried 5 protection flags + `NtProtectVirtualMemory` direct via ntdll — all failed with `STATUS_INVALID_PAGE_PROTECTION`. `WriteProcessMemory` also failed with `ERROR_NOACCESS (998)`.

**Root cause.** `VirtualQuery` showed page Type = `0x40000 (MEM_MAPPED)`, not the usual `MEM_IMAGE`. The custom loader maps jvm.dll as a read-only file mapping. Windows refuses to make MEM_MAPPED file-backed pages writable when the mapping was opened without write rights.

**Conclusion.** Inline hooking jvm.dll is fundamentally blocked.

## 4. Method-struct probe finds defineClass1's native_function

The native function entry point is reachable indirectly:
1. `FindClass("java/lang/ClassLoader")` → jclass
2. `GetStaticMethodID` for defineClass1's exact signature → jmethodID
3. jmethodID is `Method**` in HotSpot — dereference → Method*
4. Compare two Method structs (defineClass1, findLoadedClass0) — find a per-method differing pointer that points into jvm.dll .text. Probe identifies offset 0x60 as `_native_function`.

This gave us the function address. But we still couldn't hook it directly (MEM_MAPPED).

## 5. Method[+0x60] slot patch — breakthrough

Realization: the per-method CodeCache stub reads `Method[+0x60]` on every call. Metaspace is regular writable memory. We can just write a different value into that slot — JVM jumps to our function on next call.

`InterlockedExchangePointer(slot, &hook_function)` — done. No protection trickery. No inline hook. Just an 8-byte atomic write.

First successful run: caught `ru.meproject.Main` (the RML loader's main class) with the correct 7730 bytes.

## 6. Resolver-stub gotcha

**Problem.** First few runs after the fix would crash after dumping one class. Reason: the `Method[+0x60]` slot initially holds a **shared resolver stub** (not the real `native_function`). Our saved "original" was the resolver. When the hook called the resolver with JNI args, the resolver expected different inputs and crashed.

**Fix.** Recognize the resolver by byte-pattern of its prologue (it saves arg registers to home space, not callee-saved). Poll the slot in 50ms loop until it changes from the resolver pattern, THEN install hook. The resolver always patches itself away to the real `native_function` after the first invocation by the JVM (which happens within ~100ms-2s of process start).

## 7. Captured bytes are NOT CAFEBABE

**Observation.** All dumped classes have random-looking starting bytes — not `CA FE BA BE`. Different starting bytes per class. Entropy ≈ 7.78 bits/byte. All 256 byte values used. RML's `Main.class` extracted from `RMLLoader.jar` on disk has the same encrypted form.

**Hypothesis 1 (rejected).** Just the magic header is replaced. Patching first 4 bytes to `CA FE BA BE` should give a valid classfile.
**Result.** Scanning the dumped Main.class for any of the standard JVMS strings (`Code`, `<init>`, `java/lang/Object`, `SourceFile`, `StackMapTable`, etc.) returns ZERO matches. Confirmed not just a header change — the entire file is encrypted/transformed.

**Hypothesis 2 (also rejected).** Decryption happens inside `Java_java_lang_ClassLoader_defineClass1`. We could find where the decrypted plaintext sits in memory and grab it.
**Result.** Dumped 8 KB of `defineClass1`, 16 KB of `jvm_define_class_common`, and 128 KB of the deeper helper. Scanned for `CAFEBABE` constant (any byte order) — ZERO occurrences. Scanned for AES-NI instructions (AESENC, AESDEC, PSHUFB...) — ZERO occurrences. The JVM's parser was REPLACED entirely; it doesn't expect CAFEBABE input at any point.

**Conclusion.** The encrypted bytes are the JVM's native input format. There is no plaintext CAFEBABE intermediate stage anywhere.

## 8. Pivot to in-memory structure walking

Since CAFEBABE isn't going to appear during parsing, the only way to get standard Java info is to walk the AFTER-PARSE structures (`InstanceKlass`, `Method`, `ConstMethod`, `ConstantPool`) in metaspace. The HotSpot internal data layout is largely standard even in this fork (just some offsets shifted).

## 9. Structure dump via JNI reflection

First low-effort win: use JNI to call `Class.getName`, `getDeclaredMethods`, etc. on each loaded class. This works fine — reflection isn't disabled. Output: `.java` skeleton per class with proper package tree.

This already gives the user a complete API map of the application.

## 10. Probing Method/ConstMethod/Class oop/InstanceKlass

Probe runs that identified:
- `Method+0x48 = _constMethod` (always non-null, per-method ext.data pointer)
- `Method+0x60 = _native_function` (for native methods, in jvm.text)
- `Method+0x10 = _method_data` (lazy)
- `Method+0x18 = _method_counters` (lazy)
- `Class oop +0x010 = _klass` (InstanceKlass*)
- `ConstMethod+0x30 = _constants` (ConstantPool* — same for all methods of one class)
- `InstanceKlass+0x170 → Array<u1> at +0x10 = ConstantPool tags`

## 11. Bytecode is also non-standard

**Observation.** Even after locating ConstMethod and pulling the bytecode bytes (at `ConstMethod+0x40`), they don't look like JVMS opcodes. Object.toString is 24 bytes (vs standard 36). Bytes show repeated 0x9f / 0x29 patterns that don't fit standard invokevirtual / aload_0 sequences.

**Conclusion.** rustme has remapped the interpreter dispatch table. Likely uses 1-byte CP indices and/or permuted opcode numbers. Standard tools can't decompile this.

Recovering standard JVMS bytecode requires reversing the dispatch table — multi-day RE work. Documented in [roadmap.md](roadmap.md) Stage 2.

## What ended up working vs not

| Approach                                       | Worked? |
| ---------------------------------------------- | ------- |
| Watcher-based early injection                  | ✅ yes |
| GetProcAddress("Java_*defineClass1*")          | ❌ exports stripped |
| Inline hook of jvm.dll defineClass1             | ❌ MEM_MAPPED blocks VirtualProtect |
| Method[+0x60] slot patch in metaspace          | ✅ yes — the central solution |
| Polling resolver→native_function transition    | ✅ yes — needed to avoid crash on initial slot |
| Reflection-based structure extraction          | ✅ yes — produces .java skeletons |
| Patching CAFEBABE into dumped bytes            | ❌ entire file is encrypted, not just header |
| Finding CAFEBABE inside decrypt code           | ❌ no decrypt step exists; parser is replaced |
| Walking ConstantPool / ConstMethod in metaspace | 🟡 partial — offsets found, full walker not yet written |
| Reading raw bytecode from ConstMethod          | 🟡 partial — bytes extractable but in custom format |
| Reverse rustme bytecode → JVMS bytecode        | 🔵 not attempted — multi-day project |


## N. Heap-dump cryptanalysis of protected classes (Python, rustme_19600_*.dmp)

Worked from the full process minidump (7.55 GB, MDMP) plus the on-disk
`classes.jar` (29406 entries) and ~201 dumped `ru/meproject/**.class` samples.

### Minidump parsing
- `analysis/mdmp.py` parses MDMP: Memory64List + ModuleList.
- jvm.dll base in this dump = `0x7ffa04b30000`, size `0x20f0000`.
- 1931 memory ranges, ~7730 MB committed.

### Metaspace structures located
- Histogram of qwords pointing into jvm.dll found the C++ vtables:
  - **InstanceKlass vtable** rva `0xcd3b08` → **21802 InstanceKlass instances**
  - ConstantPool vtable rva `0xcbe290` (~21969)
  - Method/ConstMethod vtable rva `0xcee388` (~120395)
- InstanceKlass layout confirmed: `+0x10` = `_super` (points to another IK;
  common super `0x7c00419e0` = java/lang/Object). `+0x08` is OBFUSCATED
  (random per class). Class **name Symbol is not directly readable**
  (matches prior note that names are recovered via reflection at runtime).
- Fork is **JDK 21** (LambdaForm major version 0x41=65), not JDK 8.

### Protected-class ENCRYPTION SCHEME (reverse-engineered) — KEY RESULT
Every encrypted entry (classes.jar, minecraft.jar, dumped samples) is:
```
  [256-byte per-file header, entropy ~8.0]  ||  [body]
  plaintext = body XOR KEY15      (15-byte repeating key, GLOBAL across all files)
  plaintext begins with 'CA FE BA BE'
```
Evidence:
- Column-bias over 29406 entries: offsets 0..255 uniform (~0.5%); **offset 256
  = 100% constant** byte; offsets 256+ structured (12–46% per-column bias).
  => exact 256-byte header, body starts at 256.
- Keystream periodicity test: mode-match spikes ONLY at multiples of **15**
  (P=15: 42%, P=30: 44%, others ~0.2%) => **15-byte repeating XOR key**.
- body[0:7] cipher byte-identical across ALL 29406 entries => key is GLOBAL,
  not per-file. (The 256-byte header is per-file — likely an RSA-2048 signature.)
- Recovered key residues **0..6 = [0x87,0xb3,0xa9,0x7e,0x1b,0xa1,0xdf]**,
  verified: body[0:4]^key = `CAFEBABE`, [4:7]→`00 00 00` (minor=0, major_hi=0).
  Confirmed on classes.jar AND on 10/10 ru/meproject samples.

### What blocks full decryption (residues 7..14)
- After CAFEBABE the layout is CUSTOM (does not parse as standard JVMS class —
  tested an exhaustive structural DFS parser: 0 valid parses).
- The constant-pool UTF8 content is encrypted by a FURTHER layer => no readable
  strings to crib-drag (confirmed: crib/Vigenère ascent converges to garbage,
  invalid major versions).
- No decrypted class is retained in the heap (only 748 raw CAFEBABE in 7.7 GB,
  all LambdaForms/JIT, none are classes.jar entries — verified via key-free
  signature `body[10:25]^body[25:40]`: 0 matches against the corpus).
- The 15-byte key is not present as a clean buffer (searched all period-15 runs
  ≥45 bytes for the key signature: none).

### Decisive next step to finish the key
One of:
1. Obtain ONE fully-decrypted reference class (gives KEY15 instantly via XOR).
2. Reverse the XOR-key constant / decrypt routine in jvm.dll (key is period-15,
   stored or derived inside the binary; not present as plaintext bytes 87b3a97e
   anywhere in the dump, so likely computed or obfuscated).

### Reusable tooling added under `analysis/`
- `mdmp.py` (minidump + MemoryMap), `find_vtables.py`, `find_klasses.py`
- `column_bias.py`, `period_test*.py` (scheme discovery)
- `match_pairs.py` (key-free plaintext/ciphertext matcher via 15-period signature)
- `decrypt_rustme.py` (consolidated scheme + partial key + verifier)


## N+1. LIVE-PROCESS extraction breakthrough (ReadProcessMemory, Python)

Pivoted from the static dump to the LIVE rustme JVM. Two rustme.exe processes
exist: the launcher (`...\RustMeLauncher\...\RustMe.exe`) and the JVM/game
(`...\rustme-launcher\java\prod-a\bin\rustme.exe`) — must target the latter.
Needs Admin (OpenProcess err 5 otherwise).

### Decrypted classes ARE present in live memory
Scanning the live JVM found **decrypted class buffers** that start with a
custom marker **`CA FE BA BE 46 7C 80 5F`** (NOT the standard `CAFEBABE 0000
00xx`). 593 such blobs at one moment; contain FULLY READABLE strings:
class names (`rustme/lIIilIlIl`, `com/github/weisj/jsvg/.../RenderContextAccessor`),
`Lkotlin/Metadata;`, method names, descriptors, `Code`, `LineNumberTable`,
`StackMapTable`, `<init>`, `<clinit>`, etc.

`analysis/live_dump_all.py` extracted **571 distinct decrypted classes** to
`decrypted_live/`, inventoried by module: rustme=428, com=34, java=28, ru=13,
org=10, kotlinx=5, kotlin=3 ...  (`decrypted_live/_inventory.txt`).

### TWO protection layers now clear
1. OUTER (on-disk classes.jar/minecraft.jar): `256-byte header (RSA-ish, per
   file) + body`. body has period-15 XOR structure; body[0:4]^[87 b3 a9 7e] =
   CAFEBABE.
2. INNER ("encrypted constant pool", as the owner stated): even in the
   decrypted in-memory blob, the CP **metadata** (tags / lengths / CP indices)
   is encoded/encrypted — there is NO standard `01 00 10` before
   `java/lang/Object`. Only the **UTF8 string CONTENT is plaintext**. So the
   blobs are not directly parseable as standard .class and not yet
   decompilable; they yield a readable string inventory per class.

### Important corrections to earlier assumptions
- The decrypted version field is `46 7C 80 5F`, NOT `00 00 00 MM`. So the
  earlier key residues 4..6 (derived assuming minor=0000/major_hi=00) were on a
  WRONG assumption. Header-only key would be key[0:8] = `4d4d13c0 1ba1df31` XOR
  `cafebabe 467c805f` = `87 b3 a9 7e 5d dd 5f 6e` — but this is NOT validated as
  a real pairing (both headers are constants, so their XOR is trivially
  constant). In-memory decrypted blobs do NOT match any classes.jar entry by
  the period-15 / key-independent signature -> the simple "jar_body XOR
  period15-key" model does NOT fully hold; the real transform is more involved
  (or these in-memory classes come from a path whose ciphertext isn't the jar
  body verbatim).

### Practical conclusion / next steps
- For a readable inventory + class strings: DONE (live extraction works; rerun
  during loading to capture more). Tooling: `live_dump_all.py`,
  `live_extract.py`, `live_keyfinder*.py`, `live_diag.py`, `live_matchkey.py`.
- For clean decompilable .class files, the cheapest reliable route is now to
  RECONSTRUCT from metaspace (the JVM already parsed everything): walk
  InstanceKlass -> ConstantPool (resolved tags/strings) -> ConstMethod, and
  re-serialize a standard class file. The metaspace finder (vtables, IK list)
  is already working in `analysis/find_klasses.py`.
- Alternatively, reverse the INNER CP-metadata codec (relate the encoded
  tag/length/index bytes to the readable string layout in a few known blobs).


## N+2. Constant-pool UTF8 encoding CRACKED -> 536 classes decoded

Analyzed the decrypted in-memory blobs (`cafebabe467c805f...`). The "encrypted
constant pool" leaves UTF8 string CONTENT plaintext but transforms the CP
metadata. Reverse-engineered the UTF8 entry encoding (global constants,
verified across blobs):
```
  Utf8 tag byte : real 0x01  stored as 0xF4
  Utf8 length   : stored_u2 = (real_length + 0x5BB9) mod 0x10000  (16-bit ADD)
                  verified incl. carry (len 0x4C -> 0x5C05)
```
Tag transform looks like `real = 0xF5 - stored` for some tags (Utf8 f4->01,
String ed->08, Class ee->07) but does NOT cleanly hold for all non-UTF8 tags,
and entry-size alignment between strings doesn't yet fit standard sizes -> the
NON-UTF8 CP entries (tags + u2 indices + cp_count) use an as-yet-undetermined
encoding. So full standard .class reconstruction is still blocked on that piece
(plus the custom bytecode layer).

`analysis/live_decode_strings.py` decodes EVERY decrypted class's UTF8 pool from
the live JVM and writes a readable per-class dump organized by module to
`decoded_classes/`:  rustme=405, com=41, ru=12, java=10, org=9, kotlin/kotlinx=8.
Each file lists the class name, superclass, all method names+descriptors, field
types, annotations, SourceFile, InnerClasses — i.e. the full class API/skeleton.
Example verified: com/github/weisj/jsvg/.../FontStyle$Normal decodes perfectly.

### Remaining for fully-decompilable .class
1. Crack non-UTF8 CP entry encoding (tag map for all 17 tags + u2 index/cp_count
   transform). Approach: anchor on UTF8 positions, use this_class/Class.name_index
   pointing to the known class-name UTF8 to solve the index transform.
2. Custom bytecode (Code attribute) — interpreter dispatch remap (separate, large).
Tooling: live_decode_strings.py, decode_cp.py, dump_cp_hex.py, analyze_blob_cp.py.
