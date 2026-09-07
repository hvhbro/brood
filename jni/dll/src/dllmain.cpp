#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>
#include <cstdio>
#include <cstring>

#include "jni_types.h"
#include "jvm/jvm_offsets.h"
#include "agent_payload.h"
#include "logger.h"
#include "memory_utils.h"
#include "offsets.h"

namespace {

// ============================================================
// DR0-breakpoint на входе валидатора protected-классов (jvm.dll RVA 0x1C2D0).
// Валидатор отклоняет чужие классы ('Corrupted classfile'); VirtualProtect
// на .text jvm.dll заблокирован протектором (gle=87). Обход: VEH ловит
// SINGLE_STEP от DR0, подменяет RIP на заглушку 'mov al,1; ret' — валидатор
// мгновенно «успешен». DR ставится только на нашем потоке, память jvm.dll
// не изменяется.
// ============================================================
void* g_drTarget = nullptr;
void* g_drStub = nullptr;
bool g_drActive = false;

LONG CALLBACK drVeh(PEXCEPTION_POINTERS ep) {
    if (g_drActive && ep->ExceptionRecord &&
        ep->ExceptionRecord->ExceptionCode == (DWORD)0x80000004 &&
        ep->ContextRecord && (void*)ep->ContextRecord->Rip == g_drTarget) {
        ep->ContextRecord->Rip = (DWORD64)g_drStub;
        return EXCEPTION_CONTINUE_EXECUTION;
    }
    return EXCEPTION_CONTINUE_SEARCH;
}

DWORD WINAPI agent_thread(LPVOID) {
    using namespace jni_offsets;

    logger_init_console();
    CreateDirectoryA("C:\\Logs", nullptr);
    logger_open_file("C:\\Logs\\jni_rva_check.log");
    log_line("[+] agent loaded");

    HMODULE jvm_mod = GetModuleHandleA("jvm.dll");
    if (!jvm_mod) {
        log_line("[-] GetModuleHandleA(\"jvm.dll\") failed, gle=%lu", GetLastError());
        logger_close_file();
        return 0;
    }
    auto jvm_base = reinterpret_cast<std::uintptr_t>(jvm_mod);
    std::int64_t jvm = static_cast<std::int64_t>(jvm_base + kJavaVmRva);
    log_line("[+] jvm.dll base = 0x%p", reinterpret_cast<void*>(jvm_base));

    auto vtable = *reinterpret_cast<std::int64_t**>(jvm);
    auto attachCurrentThread = reinterpret_cast<AttachCurrentThreadFn>(vtable[4]);

    // 1) Attach
    void* envPtr = nullptr;
    jint res = reinterpret_cast<GetEnvFn>(vtable[6])(jvm, &envPtr, kJniVersion16);
    if (res == kJniDetached) {
        res = attachCurrentThread(jvm, &envPtr, nullptr);
        log_line("[+] AttachCurrentThread res=%d env=0x%p", res, envPtr);
    }
    if (res != kJniOk || !envPtr) {
        log_line("[-] failed to obtain JNIEnv*");
        logger_close_file();
        return 0;
    }
    auto env = reinterpret_cast<std::int64_t>(envPtr);
    std::int64_t* envVTable = *reinterpret_cast<std::int64_t**>(env);

    #define JNI_SLOT(name) (static_cast<int>(JniOffset::name) / 8)

    auto findClass = reinterpret_cast<FindClassFn>(envVTable[JNI_SLOT(FindClass)]);
    auto getMethodID = reinterpret_cast<std::int64_t(__fastcall*)(std::int64_t, std::int64_t, const char*, const char*)>(
        envVTable[JNI_SLOT(GetMethodID)]);
    auto callObjA = reinterpret_cast<std::int64_t(__fastcall*)(std::int64_t, std::int64_t, std::int64_t, const void*)>(
        envVTable[JNI_SLOT(CallObjectMethodA)]);
    auto callBoolA = reinterpret_cast<unsigned char(__fastcall*)(std::int64_t, std::int64_t, std::int64_t, const void*)>(
        envVTable[JNI_SLOT(CallBooleanMethodA)]);
    auto newStringUTF = reinterpret_cast<std::int64_t(__fastcall*)(std::int64_t, const char*)>(
        envVTable[JNI_SLOT(NewStringUTF)]);
    auto excClear = reinterpret_cast<void(__fastcall*)(std::int64_t)>(
        envVTable[JNI_SLOT(ExceptionClear)]);

    // 2) Найти класслоадер, который видит rustme.* (перебор потоков).
    //    В меню это loader игры; в мире первым может попасться лоадер
    //    лаунчера (ru.meproject.Main) — проверяем каждый через loadClass.
    std::int64_t classLoader = 0;
    {
        void* clsThread = findClass(env, "java/lang/Thread");
        excClear(env);
        auto callStaticObjA = reinterpret_cast<std::int64_t(__fastcall*)(std::int64_t, std::int64_t, std::int64_t, const void*)>(
            envVTable[JNI_SLOT(CallStaticObjectMethodA)]);
        auto getStaticMid = reinterpret_cast<std::int64_t(__fastcall*)(std::int64_t, std::int64_t, const char*, const char*)>(
            envVTable[JNI_SLOT(GetStaticMethodID)]);

        std::int64_t midCur = getStaticMid(env, reinterpret_cast<std::int64_t>(clsThread),
                                           "getAllStackTraces", "()Ljava/util/Map;");
        excClear(env);
        std::int64_t threadsMap = callStaticObjA(env, reinterpret_cast<std::int64_t>(clsThread), midCur, nullptr);
        excClear(env);
        if (!threadsMap) {
            log_line("[-] getAllStackTraces failed");
            logger_close_file();
            return 0;
        }

        void* clsMap = findClass(env, "java/util/Map");
        void* clsSet = findClass(env, "java/util/Set");
        void* clsIt = findClass(env, "java/util/Iterator");
        void* clsLoaderCls = findClass(env, "java/lang/ClassLoader");
        excClear(env);
        std::int64_t midKeySet = getMethodID(env, reinterpret_cast<std::int64_t>(clsMap), "keySet", "()Ljava/util/Set;");
        std::int64_t midIter = getMethodID(env, reinterpret_cast<std::int64_t>(clsSet), "iterator", "()Ljava/util/Iterator;");
        std::int64_t midHasNext = getMethodID(env, reinterpret_cast<std::int64_t>(clsIt), "hasNext", "()Z");
        std::int64_t midNext = getMethodID(env, reinterpret_cast<std::int64_t>(clsIt), "next", "()Ljava/lang/Object;");
        std::int64_t midGetCL = getMethodID(env, reinterpret_cast<std::int64_t>(clsThread), "getContextClassLoader", "()Ljava/lang/ClassLoader;");
        std::int64_t midLoad = getMethodID(env, reinterpret_cast<std::int64_t>(clsLoaderCls), "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        excClear(env);
        if (!midLoad) {
            log_line("[-] loadClass mid not resolved");
            logger_close_file();
            return 0;
        }

        std::int64_t keySet = callObjA(env, threadsMap, midKeySet, nullptr);
        std::int64_t iter = callObjA(env, keySet, midIter, nullptr);
        excClear(env);

        int nThread = 0;
        while (classLoader == 0) {
            unsigned char has = callBoolA(env, iter, midHasNext, nullptr);
            if (!has) break;
            std::int64_t th = callObjA(env, iter, midNext, nullptr);
            if (!th) continue;
            ++nThread;
            std::int64_t cl = callObjA(env, th, midGetCL, nullptr);
            if (!cl) continue;
            // лоадер обязан видеть игровые классы (в мире первый ctxCL —
            // лоадер лаунчера, он видит только ru.meproject.*)
            std::int64_t jn = newStringUTF(env, "rustme.liIlIliIiI");
            std::int64_t testCls = callObjA(env, cl, midLoad, &jn);
            excClear(env);
            log_line("[loader] thread #%d loader=0x%llx test -> 0x%llx", nThread, cl, testCls);
            if (testCls) {
                classLoader = cl;
                log_line("[+] correct classloader found (thread #%d)", nThread);
            }
        }
        if (!classLoader) {
            log_line("[-] no classloader sees rustme.* (меню без загрузки игры?)");
            logger_close_file();
            return 0;
        }
    }

    // 3) DefineClass агента через DR0+VEH bypass валидатора protected-классов.
    //    Валидные plain/enc-блобы без bypass отклоняются ('Corrupted classfile').
    std::int64_t defined = 0;
    {
        void* validator = reinterpret_cast<void*>(jvm_base + 0x1C2D0);
        static unsigned char drStubBytes[] = {0xB0, 0x01, 0xC3};  // mov al,1; ret
        void* mem = VirtualAlloc(nullptr, sizeof(drStubBytes), MEM_COMMIT | MEM_RESERVE,
                                 PAGE_EXECUTE_READWRITE);
        if (!mem) {
            log_line("[-] VirtualAlloc for stub failed");
            logger_close_file();
            return 0;
        }
        memcpy(mem, drStubBytes, sizeof(drStubBytes));
        FlushInstructionCache(GetCurrentProcess(), mem, sizeof(drStubBytes));

        g_drTarget = validator;
        g_drStub = mem;
        HANDLE curThread = GetCurrentThread();
        CONTEXT ctx = {};
        ctx.ContextFlags = CONTEXT_DEBUG_REGISTERS;
        if (GetThreadContext(curThread, &ctx)) {
            ctx.Dr0 = (DWORD64)validator;
            ctx.Dr7 = (ctx.Dr7 & ~0xFULL) | 0x1ULL;  // DR0 local enable, exec
            ctx.ContextFlags = CONTEXT_DEBUG_REGISTERS;
            if (!SetThreadContext(curThread, &ctx)) {
                log_line("[-] SetThreadContext(DR) failed gle=%lu", GetLastError());
                logger_close_file();
                return 0;
            }
        } else {
            log_line("[-] GetThreadContext failed gle=%lu", GetLastError());
            logger_close_file();
            return 0;
        }

        AddVectoredExceptionHandler(1, drVeh);
        g_drActive = true;

        auto defineClassFn = reinterpret_cast<std::int64_t(__fastcall*)(std::int64_t, const char*,
                                                                        std::int64_t, const unsigned char*, jint)>(
            envVTable[JNI_SLOT(DefineClass)]);

        // Определяем ВСЕ классы из payload'а (name=null: парсер читает имя
        // из байткода). DR0 armed один раз на весь пакет.
        unsigned int okCount = 0;
        for (unsigned int ci = 0; ci < g_agentClassCount; ++ci) {
            const AgentClassBlob& blob = g_agentClasses[ci];
            std::int64_t cls = 0;
            __try {
                cls = defineClassFn(env, nullptr, classLoader, blob.data, (jint)blob.size);
            } __except (EXCEPTION_EXECUTE_HANDLER) {
                log_line("[-] define[%s] SEH 0x%08lx", blob.name, GetExceptionCode());
                continue;
            }
            excClear(env);
            if (cls) {
                ++okCount;
                log_line("[+] define[%s] -> jclass=0x%llx", blob.name, cls);
            } else {
                log_line("[-] define[%s] -> 0", blob.name);
            }
            if (defined == 0 && _stricmp(blob.name, g_agentEntryName) == 0) {
                defined = cls;
            }
        }
        if (defined == 0 && okCount > 0) {
            // entry не найден по имени — берём первый определённый
            defined = defineClassFn(env, g_agentClasses[0].name, classLoader,
                                    g_agentClasses[0].data, (jint)g_agentClasses[0].size);
            excClear(env);
            log_line("[+] entry fallback define -> jclass=0x%llx", defined);
        }

        g_drActive = false;
        RemoveVectoredExceptionHandler(drVeh);
        CONTEXT ctx2 = {};
        ctx2.ContextFlags = CONTEXT_DEBUG_REGISTERS;
        if (GetThreadContext(curThread, &ctx2)) {
            ctx2.Dr0 = 0;
            ctx2.Dr7 &= ~0x1ULL;
            ctx2.ContextFlags = CONTEXT_DEBUG_REGISTERS;
            SetThreadContext(curThread, &ctx2);
        }
        log_line("[+] define done: %u/%u classes, entry jclass=0x%llx",
                 okCount, g_agentClassCount, defined);
    }

    if (!defined) {
        log_line("[-] agent class not defined");
        logger_close_file();
        return 0;
    }

    // 4) install() — запускает агентный Java-поток
    {
        auto getStaticMid = reinterpret_cast<std::int64_t(__fastcall*)(
            std::int64_t, std::int64_t, const char*, const char*)>(
            envVTable[JNI_SLOT(GetStaticMethodID)]);
        auto callStaticVoidA = reinterpret_cast<void(__fastcall*)(
            std::int64_t, std::int64_t, std::int64_t, const void*)>(
            envVTable[JNI_SLOT(CallStaticVoidMethodA)]);

        std::int64_t midInstall = getStaticMid(env, defined, "install", "()V");
        excClear(env);
        if (!midInstall) {
            log_line("[-] GetStaticMethodID(install) -> 0");
            logger_close_file();
            return 0;
        }
        __try {
            callStaticVoidA(env, defined, midInstall, nullptr);
        } __except (EXCEPTION_EXECUTE_HANDLER) {
            log_line("[-] install() SEH 0x%08lx", GetExceptionCode());
        }
        excClear(env);
        log_line("[+] install() called — агентный поток запущен");
    }

    log_line("[+] agent injected successfully");
    logger_close_file();
    return 0;
}

} // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(module);
        HANDLE thread = CreateThread(nullptr, 0, agent_thread, nullptr, 0, nullptr);
        if (thread) CloseHandle(thread);
    }
    return TRUE;
}
