# Roadmap

Staged plan from current capability to full class reconstruction, with
realistic effort estimates.

## ✅ Stage 0 — Foundation (DONE)

- Custom DLL with Method[+0x60] slot patch hook on defineClass1
- Watcher injector for early process catch
- Encrypted class bytes captured per defineClass1 invocation
- Reflection-based structure extraction → .java skeletons per class
- Files organized by package tree

Output is usable as a high-level API map of the application. Suitable for
"what classes exist, what's their hierarchy, what methods exist with what
signatures" questions.

## 🔵 Stage 1 — ConstantPool extraction (small effort, high value)

**Goal.** For each loaded class, dump all ConstantPool entries in textual
form alongside the .java skeleton.

**Effort.** A few hours.

**Steps.**
1. From `Method[+0x48]` (any method of the class) get `ConstMethod*`.
2. From `ConstMethod[+0x30]` get `ConstantPool*`.
3. From `InstanceKlass[+0x170]` deref to `Array<u1>*` of tags (the array starts at +0x10 of that pointer with `u4 length` then `tags[length]`).
4. ConstantPool entries layout: in standard HotSpot 8, entries are stored inline after the ConstantPool fixed header. Need to PROBE the CP itself to find:
   - The `_length` field offset (or compute from `Array<u1>` tags length)
   - The inline entries' starting offset
5. For each tag at index `i`:
   - tag 1 (Utf8) → entry is `Symbol*`; read length+bytes
   - tag 3 (Integer) → 4-byte int inline
   - tag 4 (Float) → 4-byte float
   - tag 5 (Long) → 8-byte long (takes 2 slots)
   - tag 6 (Double) → 8-byte double
   - tag 7 (Class) → `Symbol*` (the class name) or resolved Klass*
   - tag 8 (String) → `Symbol*`
   - tag 9 (Fieldref) → packed (class_index << 16) | nat_index
   - tag 10 (Methodref) → packed
   - tag 11 (InterfaceMethodref) → packed
   - tag 12 (NameAndType) → packed (name_index << 16) | desc_index
   - tag 15 (MethodHandle) → packed
   - tag 16 (MethodType) → Symbol*
   - tag 18 (InvokeDynamic) → packed
6. Output to `C:\Logs\structure\<pkg>\<Name>.cp.txt`. Format:

```
#1  Utf8       "java/lang/Object"
#2  Utf8       "<init>"
#3  Utf8       "()V"
#4  Class      #1  -> java/lang/Object
#5  NameAndType #2:#3  -> <init>:()V
#6  Methodref  #4.#5  -> java/lang/Object.<init>:()V
...
```

**Why valuable.** All string literals and call references are in plaintext
in the CP. Lets you understand what each method calls and what data it
manipulates — without needing to decode the actual bytecode opcodes.

## 🟡 Stage 2 — Bytecode opcode-table reverse (multi-day, blocker for full reconstruction)

**Goal.** Build a `rustme_opcode → JVMS_opcode` translation table so that
captured bytecode bytes from `ConstMethod+0x40` can be translated to standard
JVMS bytecode.

**Effort.** 2-5 days of focused RE work. High variance — depends on whether
rustme just permuted opcode numbers or also added new compound instructions.

**Steps.**

### 2a. Find the interpreter dispatch table

The HotSpot template interpreter dispatches each opcode via:
```
movzx eax, byte [rsi]   ; read next byte (current opcode)
inc rsi                  ; advance bytecode pointer
jmp [r13 + rax*8]        ; r13 holds dispatch table base
```

Find this exact instruction sequence in jvm.dll. The relative offset of `r13`
setup (somewhere earlier in the function) points to the table base.

Sigscan pattern (rough):
```
48 0F B6 ?? 48 FF C? ?? FF 24 C5 ?? ?? ?? ??
```

Alternatively, the table itself is recognizable: 256 consecutive 8-byte
pointers, all pointing into jvm.dll `.text`. Scan `.rdata` / `.data` for
such an array.

### 2b. Match each handler against OpenJDK reference

Each entry in the dispatch table is a code pointer to a handler. The
handler's first instructions are a recognizable pattern per opcode. For
example, `aload_0` is just a few instructions that push local 0 onto the
operand stack:

```
mov rax, [r14]      ; load local var 0 (r14 holds locals base)
mov [r13 + ...], rax ; push to operand stack
... bump rbcp, jump to next opcode
```

Compare each rustme handler's first ~16-32 bytes against patterns derived
from OpenJDK 8 sources (built without modifications). The match tells you
which standard opcode that rustme entry implements.

Doing this for all 256 entries (or just the ~200 commonly-used ones) builds
the mapping table.

### 2c. Operand size handling

If rustme uses 1-byte CP indices instead of 2-byte (likely — explains the
24-vs-36-byte Object.toString delta), the translator also needs to:
- Detect "wide-index" instructions (`invokevirtual`, `new`, `getfield`, etc.)
- Read 1 byte from rustme bytecode
- Emit 2 bytes (with high byte = 0 or by some packing scheme — needs
  observation)

### 2d. Build the translator

```cpp
std::vector<uint8_t> translate(const uint8_t* rustme_code, size_t len) {
    std::vector<uint8_t> jvms;
    size_t i = 0;
    while (i < len) {
        uint8_t r_op = rustme_code[i++];
        uint8_t j_op = OPCODE_MAP[r_op];
        jvms.push_back(j_op);
        int operand_bytes = JVMS_OPERAND_SIZE[j_op];
        if (operand_bytes == 0) continue;
        // Read rustme operand (could be 1 byte if compressed)
        // Emit JVMS operand at full width
        ...
    }
    return jvms;
}
```

## 🟢 Stage 3 — Standard CAFEBABE classfile assembly

**Goal.** Given:
- Class metadata from reflection (Stage 0)
- ConstantPool entries (Stage 1)
- Translated bytecode (Stage 2)

Produce a valid CAFEBABE-format `.class` file readable by `javap`, CFR,
Procyon.

**Effort.** ~1 day once Stages 1+2 are done.

**Steps.**
1. Build constant pool: collect all utf8/class/name+type/method+field refs needed
2. Compute correct CP indices
3. Build field_info table from reflection
4. Build method_info table:
   - access flags
   - name + descriptor indices
   - Code attribute:
     - max_stack, max_locals (need to extract from ConstMethod fields or compute)
     - code_length + translated bytecode bytes
     - exception_table (extract from ConstMethod inline data)
     - LineNumberTable etc.
5. Class attributes (SourceFile, InnerClasses, etc.)
6. Serialize and write to disk

Result: full standard `.class` files, decompilable by any Java tool.

## 🔴 Stage 4 — Decryption of disk-stored `classes.jar` / `minecraft.jar` (separate problem)

This is parallel work, NOT required if Stage 3 succeeds. Goes after the
on-disk file format (not in-memory). Different RE entirely — would need to
reverse the JVM's custom `ClassFileParser` to understand how it decodes the
encrypted bytes into in-memory structures.

**Probably not needed** because Stage 3 produces clean .class files directly
from in-memory state, side-stepping the encrypted disk format completely.

## What to prioritize for which goal

| Goal | Need stages |
| --- | --- |
| Understand the application's class hierarchy / API | 0 only (done) |
| Read string literals and find call references | 0 + 1 |
| Get fully decompilable Java source | 0 + 1 + 2 + 3 |
| Decrypt the on-disk .jar entries | 0 + 4 (separate track) |

Recommended path for the next session: do Stage 1, evaluate value, then
decide on Stage 2 commitment.
