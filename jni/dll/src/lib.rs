//! brood — Rust-порт C++ DLL (эталон: jni/dll.backup/src/dllmain.cpp).
//!
//! Цепочка: attach → loader-цепочка → DR0+VEH → DefineClass → install().
//! Правила проекта (см. ZNANIA): инжект 1 раз на запуск игры; нативного
//! хук-пути (env-vtable/трамплины) здесь нет и не добавляется.
//!
//! Отличия от C++ (намеренные, поведение то же):
//! - `__try/__except` вокруг DefineClass/install убраны по требованию
//!   (SEH не нужен) — прямые вызовы.
//! - RemoveVectoredExceptionHandler вызывается с хендлом от Add (в C++
//!   ошибочно передавался указатель функции; хендл всё равно снимается,
//!   g_drActive=false дублирует защиту).
//! - freopen CONOUT$ из логгера опущен (логгер пишет через WriteConsoleA
//!   напрямую, printf никто не пользуется).
//! - Мёртвый src/jvm_offsets.h (верхний, с несуществующим jni_min_types.h,
//!   нигде не включался) не портирован — живая карта это jvm_offsets.rs.

mod jni_types;
mod jvm_offsets;
mod logger;
mod memory_utils;
mod offsets;

// Сгенерировано tools/pack_agent.py (protected-блобы агента):
// G_AGENT_CLASSES: &[AgentClassBlob { name, data }], G_AGENT_ENTRY_NAME.
include!("agent_payload.rs");

use core::ffi::c_void;
use core::ptr::{null, null_mut};
use core::sync::atomic::{AtomicBool, AtomicPtr, Ordering};
use jni_types::*;
use jvm_offsets::{jni_slot, JniOffset};
use windows_sys::Win32::Foundation::{BOOL, CloseHandle, GetLastError, HMODULE, NTSTATUS, TRUE};
use windows_sys::Win32::Storage::FileSystem::CreateDirectoryA;
use windows_sys::Win32::System::Diagnostics::Debug::{
    AddVectoredExceptionHandler, FlushInstructionCache, GetThreadContext,
    RemoveVectoredExceptionHandler, SetThreadContext, CONTEXT, EXCEPTION_POINTERS,
};
use windows_sys::Win32::System::LibraryLoader::{DisableThreadLibraryCalls, GetModuleHandleA};
use windows_sys::Win32::System::Memory::{
    VirtualAlloc, MEM_COMMIT, MEM_RESERVE, PAGE_EXECUTE_READWRITE,
};
use windows_sys::Win32::System::SystemServices::DLL_PROCESS_ATTACH;
use windows_sys::Win32::System::Threading::{
    CreateThread, GetCurrentProcess, GetCurrentThread,
};

// ============================================================
// DR0-breakpoint на входе валидатора protected-классов
// (jvm.dll + K_VALIDATOR_RVA). Валидатор отклоняет чужие классы
// ('Corrupted classfile'); VirtualProtect на .text jvm.dll заблокирован
// протектором (gle=87). Обход: VEH ловит SINGLE_STEP от DR0, подменяет
// RIP на заглушку 'mov al,1; ret' — валидатор мгновенно «успешен».
// DR ставится только на нашем потоке, память jvm.dll не изменяется.
// ============================================================

/// Код SINGLE_STEP (Windows SDK: 0x80000004; NTSTATUS = i32).
const EXCEPTION_SINGLE_STEP: NTSTATUS = 0x8000_0004u32 as i32;
const EXCEPTION_CONTINUE_EXECUTION: i32 = -1;
const EXCEPTION_CONTINUE_SEARCH: i32 = 0;
/// CONTEXT_DEBUG_REGISTERS для AMD64 = CONTEXT_AMD64 (0x100000) | 0x10.
const CONTEXT_DEBUG_REGISTERS: u32 = 0x0010_0010;

static DR_TARGET: AtomicPtr<c_void> = AtomicPtr::new(null_mut());
static DR_STUB: AtomicPtr<c_void> = AtomicPtr::new(null_mut());
static DR_ACTIVE: AtomicBool = AtomicBool::new(false);
static DR_VEH: AtomicPtr<c_void> = AtomicPtr::new(null_mut());

unsafe extern "system" fn dr_veh(ep: *mut EXCEPTION_POINTERS) -> i32 {
    if DR_ACTIVE.load(Ordering::SeqCst) && !ep.is_null() {
        let rec = (*ep).ExceptionRecord;
        if !rec.is_null() && (*rec).ExceptionCode == EXCEPTION_SINGLE_STEP {
            let ctx = (*ep).ContextRecord;
            if !ctx.is_null() && (*ctx).Rip as *mut c_void == DR_TARGET.load(Ordering::SeqCst) {
                (*ctx).Rip = DR_STUB.load(Ordering::SeqCst) as u64;
                return EXCEPTION_CONTINUE_EXECUTION;
            }
        }
    }
    EXCEPTION_CONTINUE_SEARCH
}

unsafe fn slot_fn<T: Copy>(vtable: *const i64, bytes: u32) -> T {
    core::mem::transmute_copy::<i64, T>(&*vtable.add(jni_slot(bytes)))
}

unsafe extern "system" fn agent_thread(_param: *mut c_void) -> u32 {
    logger::logger_init_console();
    CreateDirectoryA(c"C:\\Logs".as_ptr() as *const u8, null());
    logger::logger_open_file(c"C:\\Logs\\brood.log");
    log_line!("[+] agent loaded");

    let jvm_mod = GetModuleHandleA(c"jvm.dll".as_ptr() as *const u8);
    if jvm_mod.is_null() {
        log_line!(
            "[-] GetModuleHandleA(\"jvm.dll\") failed, gle={}",
            GetLastError()
        );
        logger::logger_close_file();
        return 0;
    }
    let jvm_base = jvm_mod as usize as u64;
    let jvm = jvm_base.wrapping_add(offsets::K_JAVA_VM_RVA as u64) as i64;
    log_line!("[+] jvm.dll base = 0x{:x}", jvm_base);

    let vtable = *(jvm as *const *const i64);
    let attach: AttachCurrentThreadFn = core::mem::transmute(*vtable.add(4));
    let get_env: GetEnvFn = core::mem::transmute(*vtable.add(6));

    // 1) Attach
    let mut env_ptr: *mut c_void = null_mut();
    let mut res = get_env(jvm, &mut env_ptr, offsets::K_JNI_VERSION_16);
    if res == offsets::K_JNI_DETACHED {
        res = attach(jvm, &mut env_ptr, null_mut());
        log_line!(
            "[+] AttachCurrentThread res={} env=0x{:x}",
            res,
            env_ptr as usize
        );
    }
    if res != offsets::K_JNI_OK || env_ptr.is_null() {
        log_line!("[-] failed to obtain JNIEnv*");
        logger::logger_close_file();
        return 0;
    }
    let env = env_ptr as i64;
    let env_vtable = *(env as *const *const i64);

    let find_class: FindClassFn = slot_fn(env_vtable, JniOffset::FindClass);
    let get_method_id: GetMethodIdFn = slot_fn(env_vtable, JniOffset::GetMethodID);
    let call_obj_a: CallObjectAFn = slot_fn(env_vtable, JniOffset::CallObjectMethodA);
    let call_bool_a: CallBooleanAFn = slot_fn(env_vtable, JniOffset::CallBooleanMethodA);
    let new_string_utf: NewStringUtfFn = slot_fn(env_vtable, JniOffset::NewStringUTF);
    let exc_clear: ExcClearFn = slot_fn(env_vtable, JniOffset::ExceptionClear);

    // 2) Найти класслоадер, который видит rustme.* (перебор потоков).
    //    В меню это loader игры; в мире первым может попасться лоадер
    //    лаунчера (ru.meproject.Main) — проверяем каждый через loadClass.
    let mut class_loader: i64 = 0;
    {
        let cls_thread = find_class(env, c"java/lang/Thread".as_ptr());
        exc_clear(env);
        let call_static_obj_a: CallObjectAFn =
            slot_fn(env_vtable, JniOffset::CallStaticObjectMethodA);
        let get_static_mid: GetMethodIdFn = slot_fn(env_vtable, JniOffset::GetStaticMethodID);

        let mid_cur = get_static_mid(
            env,
            cls_thread as i64,
            c"getAllStackTraces".as_ptr(),
            c"()Ljava/util/Map;".as_ptr(),
        );
        exc_clear(env);
        let threads_map =
            call_static_obj_a(env, cls_thread as i64, mid_cur, null());
        exc_clear(env);
        if threads_map == 0 {
            log_line!("[-] getAllStackTraces failed");
            logger::logger_close_file();
            return 0;
        }

        let cls_map = find_class(env, c"java/util/Map".as_ptr());
        let cls_set = find_class(env, c"java/util/Set".as_ptr());
        let cls_it = find_class(env, c"java/util/Iterator".as_ptr());
        let cls_loader_cls = find_class(env, c"java/lang/ClassLoader".as_ptr());
        exc_clear(env);
        let mid_key_set = get_method_id(
            env,
            cls_map as i64,
            c"keySet".as_ptr(),
            c"()Ljava/util/Set;".as_ptr(),
        );
        let mid_iter = get_method_id(
            env,
            cls_set as i64,
            c"iterator".as_ptr(),
            c"()Ljava/util/Iterator;".as_ptr(),
        );
        let mid_has_next =
            get_method_id(env, cls_it as i64, c"hasNext".as_ptr(), c"()Z".as_ptr());
        let mid_next = get_method_id(
            env,
            cls_it as i64,
            c"next".as_ptr(),
            c"()Ljava/lang/Object;".as_ptr(),
        );
        let mid_get_cl = get_method_id(
            env,
            cls_thread as i64,
            c"getContextClassLoader".as_ptr(),
            c"()Ljava/lang/ClassLoader;".as_ptr(),
        );
        let mid_load = get_method_id(
            env,
            cls_loader_cls as i64,
            c"loadClass".as_ptr(),
            c"(Ljava/lang/String;)Ljava/lang/Class;".as_ptr(),
        );
        exc_clear(env);
        if mid_load == 0 {
            log_line!("[-] loadClass mid not resolved");
            logger::logger_close_file();
            return 0;
        }

        let key_set = call_obj_a(env, threads_map, mid_key_set, null());
        let iter = call_obj_a(env, key_set, mid_iter, null());
        exc_clear(env);

        let mut n_thread: i32 = 0;
        while class_loader == 0 {
            let has = call_bool_a(env, iter, mid_has_next, null());
            if has == 0 {
                break;
            }
            let th = call_obj_a(env, iter, mid_next, null());
            if th == 0 {
                continue;
            }
            n_thread += 1;
            let cl = call_obj_a(env, th, mid_get_cl, null());
            if cl == 0 {
                continue;
            }
            // лоадер обязан видеть игровые классы (в мире первый ctxCL —
            // лоадер лаунчера, он видит только ru.meproject.*)
            let jn = new_string_utf(env, c"rustme.liIlIliIiI".as_ptr());
            let args = &jn as *const i64 as *const c_void;
            let test_cls = call_obj_a(env, cl, mid_load, args);
            exc_clear(env);
            log_line!(
                "[loader] thread #{} loader=0x{:x} test -> 0x{:x}",
                n_thread,
                cl as u64,
                test_cls as u64
            );
            if test_cls != 0 {
                class_loader = cl;
                log_line!("[+] correct classloader found (thread #{})", n_thread);
            }
        }
        if class_loader == 0 {
            log_line!("[-] no classloader sees rustme.* (меню без загрузки игры?)");
            logger::logger_close_file();
            return 0;
        }
    }

    // 3) DefineClass агента через DR0+VEH bypass валидатора protected-классов.
    //    Валидные plain/enc-блобы без bypass отклоняются ('Corrupted classfile').
    let mut defined: i64 = 0;
    {
        let validator = (jvm_base as usize).wrapping_add(offsets::K_VALIDATOR_RVA) as *mut c_void;
        static STUB_BYTES: [u8; 3] = [0xB0, 0x01, 0xC3]; // mov al,1; ret
        let mem = VirtualAlloc(
            null(),
            STUB_BYTES.len(),
            MEM_COMMIT | MEM_RESERVE,
            PAGE_EXECUTE_READWRITE,
        );
        if mem.is_null() {
            log_line!("[-] VirtualAlloc for stub failed");
            logger::logger_close_file();
            return 0;
        }
        core::ptr::copy_nonoverlapping(STUB_BYTES.as_ptr(), mem as *mut u8, STUB_BYTES.len());
        FlushInstructionCache(GetCurrentProcess(), mem, STUB_BYTES.len());

        DR_TARGET.store(validator, Ordering::SeqCst);
        DR_STUB.store(mem, Ordering::SeqCst);
        let cur_thread = GetCurrentThread();
        let mut ctx: CONTEXT = core::mem::zeroed();
        ctx.ContextFlags = CONTEXT_DEBUG_REGISTERS;
        if GetThreadContext(cur_thread, &mut ctx) != 0 {
            ctx.Dr0 = validator as u64;
            ctx.Dr7 = (ctx.Dr7 & !0xF) | 0x1; // DR0 local enable, exec
            ctx.ContextFlags = CONTEXT_DEBUG_REGISTERS;
            if SetThreadContext(cur_thread, &ctx) == 0 {
                log_line!("[-] SetThreadContext(DR) failed gle={}", GetLastError());
                logger::logger_close_file();
                return 0;
            }
        } else {
            log_line!("[-] GetThreadContext failed gle={}", GetLastError());
            logger::logger_close_file();
            return 0;
        }

        let veh = AddVectoredExceptionHandler(1, Some(dr_veh));
        DR_VEH.store(veh, Ordering::SeqCst);
        DR_ACTIVE.store(true, Ordering::SeqCst);

        let define_class: DefineClassFn = slot_fn(env_vtable, JniOffset::DefineClass);

        // Определяем ВСЕ классы из payload'а (name=null: парсер читает имя
        // из байткода). DR0 armed один раз на весь пакет.
        let mut ok_count: u32 = 0;
        for blob in G_AGENT_CLASSES {
            let cls = define_class(
                env,
                null(),
                class_loader,
                blob.data.as_ptr(),
                blob.data.len() as i32,
            );
            exc_clear(env);
            if cls != 0 {
                ok_count += 1;
                log_line!("[+] define[{}] -> jclass=0x{:x}", blob.name, cls as u64);
            } else {
                log_line!("[-] define[{}] -> 0", blob.name);
            }
            if defined == 0 && blob.name.eq_ignore_ascii_case(G_AGENT_ENTRY_NAME) {
                defined = cls;
            }
        }
        if defined == 0 && ok_count > 0 {
            // entry не найден по имени — берём первый определённый.
            // В оригинале этот вызов БЕЗ __try — сохранено 1:1.
            // Имя — NUL-терминированной C-строкой, как в C++ (as_ptr у &str
            // терминатора не имеет, а JNI читает name как C-строку).
            let first = &G_AGENT_CLASSES[0];
            let first_name = std::ffi::CString::new(first.name).unwrap_or_default();
            defined = define_class(
                env,
                first_name.as_ptr(),
                class_loader,
                first.data.as_ptr(),
                first.data.len() as i32,
            );
            exc_clear(env);
            log_line!("[+] entry fallback define -> jclass=0x{:x}", defined as u64);
        }

        DR_ACTIVE.store(false, Ordering::SeqCst);
        let veh = DR_VEH.swap(null_mut(), Ordering::SeqCst);
        if !veh.is_null() {
            RemoveVectoredExceptionHandler(veh as *const c_void);
        }
        let mut ctx2: CONTEXT = core::mem::zeroed();
        ctx2.ContextFlags = CONTEXT_DEBUG_REGISTERS;
        if GetThreadContext(cur_thread, &mut ctx2) != 0 {
            ctx2.Dr0 = 0;
            ctx2.Dr7 &= !0x1;
            ctx2.ContextFlags = CONTEXT_DEBUG_REGISTERS;
            SetThreadContext(cur_thread, &mut ctx2);
        }
        log_line!(
            "[+] define done: {}/{} classes, entry jclass=0x{:x}",
            ok_count,
            G_AGENT_CLASSES.len(),
            defined as u64
        );
    }

    if defined == 0 {
        log_line!("[-] agent class not defined");
        logger::logger_close_file();
        return 0;
    }

    // 4) install() — запускает агентный Java-поток
    {
        let get_static_mid: GetMethodIdFn = slot_fn(env_vtable, JniOffset::GetStaticMethodID);
        let call_static_void_a: CallStaticVoidAFn =
            slot_fn(env_vtable, JniOffset::CallStaticVoidMethodA);

        let mid_install =
            get_static_mid(env, defined, c"install".as_ptr(), c"()V".as_ptr());
        exc_clear(env);
        if mid_install == 0 {
            log_line!("[-] GetStaticMethodID(install) -> 0");
            logger::logger_close_file();
            return 0;
        }
        call_static_void_a(env, defined, mid_install, null());
        exc_clear(env);
        log_line!("[+] install() called — агентный поток запущен");
    }

    log_line!("[+] agent injected successfully");
    logger::logger_close_file();
    0
}

#[no_mangle]
pub unsafe extern "system" fn DllMain(
    module: HMODULE,
    reason: u32,
    _reserved: *mut c_void,
) -> BOOL {
    if reason == DLL_PROCESS_ATTACH {
        DisableThreadLibraryCalls(module);
        let thread = CreateThread(null(), 0, Some(agent_thread), null(), 0, null_mut());
        if !thread.is_null() {
            CloseHandle(thread);
        }
    }
    TRUE
}
