// Указатели на JNI-функции, как они лежат в выгруженной таблице jvm.dll.
// Порт src/jni_types.h + анонимные типы вызовов из dllmain.cpp.
// На x86-64 Windows __fastcall == system (Win64), поэтому "system".

use core::ffi::{c_char, c_void};

/// jint(__fastcall*)(int64 jvm, void** env, jint version) — слот invoke[6].
pub type GetEnvFn =
    unsafe extern "system" fn(jvm: i64, env: *mut *mut c_void, version: i32) -> i32;
/// jint(__fastcall*)(int64 jvm, void** env, void* args) — слот invoke[4].
pub type AttachCurrentThreadFn =
    unsafe extern "system" fn(jvm: i64, env: *mut *mut c_void, args: *mut c_void) -> i32;
/// void*(__fastcall*)(int64 env, const char* name).
pub type FindClassFn = unsafe extern "system" fn(env: i64, name: *const c_char) -> *mut c_void;
/// int64(__fastcall*)(int64, int64, const char*, const char*) — GetMethodID/GetStaticMethodID.
pub type GetMethodIdFn =
    unsafe extern "system" fn(env: i64, cls: i64, name: *const c_char, sig: *const c_char) -> i64;
/// int64(__fastcall*)(int64, int64, int64, const void*) — CallObjectMethodA/CallStaticObjectMethodA.
pub type CallObjectAFn =
    unsafe extern "system" fn(env: i64, obj: i64, mid: i64, args: *const c_void) -> i64;
/// u8(__fastcall*)(int64, int64, int64, const void*) — CallBooleanMethodA.
pub type CallBooleanAFn =
    unsafe extern "system" fn(env: i64, obj: i64, mid: i64, args: *const c_void) -> u8;
/// int64(__fastcall*)(int64, const char*) — NewStringUTF.
pub type NewStringUtfFn = unsafe extern "system" fn(env: i64, s: *const c_char) -> i64;
/// void(__fastcall*)(int64) — ExceptionClear.
pub type ExcClearFn = unsafe extern "system" fn(env: i64);
/// int64(__fastcall*)(int64, const char*, int64, const u8*, int) — DefineClass.
pub type DefineClassFn = unsafe extern "system" fn(
    env: i64,
    name: *const c_char,
    loader: i64,
    buf: *const u8,
    len: i32,
) -> i64;
/// void(__fastcall*)(int64, int64, int64, const void*) — CallStaticVoidMethodA.
pub type CallStaticVoidAFn =
    unsafe extern "system" fn(env: i64, cls: i64, mid: i64, args: *const c_void);
