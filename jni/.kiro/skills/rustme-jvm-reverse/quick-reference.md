# Quick reference card

Fast lookup of the most-used facts. Read the other docs for context.

## Workspace

```
c:\Users\acc0u\OneDrive\Рабочий стол\jnicodex\
├── dll\                     DLL source (jni_rva_check.dll)
├── injector\                Watcher injector (inject.exe)
├── build\                   Outputs
├── extracted_classes\       Sample .class files (RML Main + LambdaForms)
├── rustme_19600_*.dmp       Full process memory dump (8 GB)
└── .kiro\skills\rustme-jvm-reverse\   This skill
```

## Game installation

```
C:\Users\acc0u\AppData\Roaming\rustme-launcher\
├── java\prod-a\bin\rustme.exe              JVM entry exe
├── java\prod-a\bin\server\jvm.dll          Modified HotSpot
└── profiles\prod-a\
    ├── classes.jar                          29406 entries (hex-named, encrypted)
    ├── minecraft.jar                        same format
    └── RMLLoader.jar                        Loader (ru/meproject/Main + manifest)
```

## Build & run

```cmd
:: build DLL
cd dll
build_dll.bat

:: build injector
cd injector
build_injector.bat

:: run (watcher mode — wait for rustme.exe)
build\injector\inject.exe --no-pause

:: then launch the game; watcher will inject within ~100ms
```

## Output

```
C:\Logs\
├── jni_rva_check.log                       runtime trace
├── defineClass1_bytes.bin                  8 KB of defineClass1 native code
├── internal_definer_bytes.bin              16 KB of jvm_define_class_common
├── deeper_helper_bytes.bin                 128 KB of deeper call chain
├── dumped_classes\<pkg>\<Name>.class       captured encrypted bytes
└── structure\<pkg>\<Name>.java             Java skeleton from reflection
```

## Key offsets

```
JNI vtable offsets:     jvm_offsets.h::JniOffset (bytes, /8 for slot)
Method+0x48     →       _constMethod (always ext.data)
Method+0x60     →       _native_function (jvm.text, native methods only)
ConstMethod+0x30 →      _constants (ConstantPool*, shared per class)
ConstMethod+0x40 →      bytecode bytes (in rustme custom format)
Class oop+0x010 →       _klass (InstanceKlass*)
InstanceKlass+0x170 →   Array<u1> (CP tags at +0x10)
```

## RVAs in jvm.dll

```
0x9CFA10   Java_java_lang_ClassLoader_defineClass1
0x9CFBC0   Java_java_lang_ClassLoader_defineClass2 (probably)
0x429BA0   jvm_define_class_common
0x4288E0   resolve_from_stream / deeper helper
0x5E9920   shared native-method resolver stub
0xDEBF68   JavaVM*
0xBEBB10   invoke vtable
```

## Common diagnostics

| Symptom | Cause | Fix |
| --- | --- | --- |
| Dumps stop at #1, then crash | Saved resolver as orig pointer | Already fixed — `looks_like_resolver` check in polling loop |
| All classes named `anon_NNNNN` | jstring name arg is null in rustme | Already fixed — get name via `Class.getName()` reflection on the returned jclass |
| `VirtualProtect` fails GLE=87 on jvm.dll | MEM_MAPPED jvm.dll | Don't inline-hook jvm.dll. Use Method[+0x60] slot patch instead. |
| Linker can't write DLL output | Game still running with DLL injected | Close rustme + game first |
| No classes dumped, "still at resolver" forever | Game didn't actually load any class | Make sure rustme is actually running and loading classes |

## SEH gotcha with std::vector

MSVC rejects `__try` in functions that have C++ objects with destructors as locals. When wrapping JNI calls with SEH, pass the `std::vector`/`std::string` as a reference parameter to a helper function and put the `__try` inside the helper. Pattern:

```cpp
// helper (no local vector — vector arrives via ref, no destruction here)
bool seh_capture(int64_t env, jbyteArray data, jint off, jint len,
                 std::vector<uint8_t>& out) {
    __try { return capture_bytes(env, data, off, len, out); }
    __except (EXCEPTION_EXECUTE_HANDLER) { return false; }
}

// caller (has the vector local, but no __try in this function)
void hook_callback(...) {
    std::vector<uint8_t> buf;
    seh_capture(env, data, off, len, buf);
    // ...
}
```

Existing `seh_capture`, `seh_class_name`, `seh_save_bytes`, `seh_struct` in `class_dumper.cpp` follow this pattern.
