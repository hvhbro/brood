#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <tlhelp32.h>
#include <psapi.h>

#include <cstdio>
#include <cstdarg>
#include <cstdlib>
#include <cwchar>
#include <fcntl.h>
#include <io.h>
#include <string>

static constexpr wchar_t kDefaultTargetPath[] =
    L"C:\\Users\\acc0u\\AppData\\Roaming\\rustme-launcher\\java\\prod-a\\bin\\rustme.exe";

static constexpr wchar_t kDefaultDllPath[] =
    L"C:\\Users\\acc0u\\OneDrive\\Рабочий стол\\jnicodex\\build\\dll\\jni_rva_check.dll";

static constexpr DWORD kDefaultPollMs = 10;

static bool g_no_pause = false;

static void logw(const wchar_t* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    std::vwprintf(fmt, args);
    va_end(args);
    fflush(stdout);
}

static void pause_before_exit() {
    if (g_no_pause) return;
    logw(L"[+] press Enter to exit...\n");
    (void)getchar();
}

static void print_last_error(const wchar_t* what) {
    DWORD gle = GetLastError();
    wchar_t* msg = nullptr;
    FormatMessageW(
        FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr,
        gle,
        0,
        reinterpret_cast<LPWSTR>(&msg),
        0,
        nullptr);
    logw(L"[-] %ls failed, gle=%lu%ls%ls\n", what, gle, msg ? L": " : L"", msg ? msg : L"");
    if (msg) LocalFree(msg);
}

static std::wstring absolute_path(const wchar_t* path) {
    wchar_t full[MAX_PATH * 4]{};
    DWORD n = GetFullPathNameW(path, static_cast<DWORD>(std::size(full)), full, nullptr);
    if (!n || n >= std::size(full)) return path;
    return full;
}

static std::wstring exe_name_from_path(const std::wstring& path) {
    size_t sep = path.find_last_of(L"\\/");
    return sep == std::wstring::npos ? path : path.substr(sep + 1);
}

static std::wstring full_path_from_pid(DWORD pid) {
    std::wstring path;
    HANDLE proc = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!proc) return path;

    wchar_t buf[MAX_PATH * 4]{};
    DWORD size = static_cast<DWORD>(std::size(buf));
    if (QueryFullProcessImageNameW(proc, 0, buf, &size)) {
        path.assign(buf, size);
    }

    CloseHandle(proc);
    return path;
}

// Быстрый поиск: сначала фильтр по szExeFile (без OpenProcess на каждый pid),
// потом точная проверка полного пути только для кандидатов.
static DWORD find_process(const std::wstring& exe_name, const std::wstring& target_path) {
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;

    PROCESSENTRY32W pe{};
    pe.dwSize = sizeof(pe);

    DWORD found = 0;
    if (Process32FirstW(snap, &pe)) {
        do {
            if (_wcsicmp(pe.szExeFile, exe_name.c_str()) != 0) continue;
            std::wstring path = full_path_from_pid(pe.th32ProcessID);
            if (path.empty()) continue;
            if (!_wcsicmp(path.c_str(), target_path.c_str())) {
                found = pe.th32ProcessID;
                break;
            }
        } while (Process32NextW(snap, &pe));
    }

    CloseHandle(snap);
    return found;
}

static bool inject_dll(DWORD pid, const std::wstring& dll_path) {
    HANDLE proc = OpenProcess(
        PROCESS_CREATE_THREAD |
            PROCESS_QUERY_INFORMATION |
            PROCESS_VM_OPERATION |
            PROCESS_VM_WRITE |
            PROCESS_VM_READ,
        FALSE,
        pid);
    if (!proc) {
        print_last_error(L"OpenProcess");
        return false;
    }
    logw(L"[+] OpenProcess OK (pid=%lu)\n", pid);

    bool ok = false;
    void* remote_mem = nullptr;
    HANDLE thread = nullptr;

    size_t bytes = (dll_path.size() + 1) * sizeof(wchar_t);
    remote_mem = VirtualAllocEx(proc, nullptr, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!remote_mem) {
        print_last_error(L"VirtualAllocEx");
        goto done;
    }
    logw(L"[+] VirtualAllocEx OK: 0x%p (%zu bytes)\n", remote_mem, bytes);

    {
        SIZE_T written = 0;
        if (!WriteProcessMemory(proc, remote_mem, dll_path.c_str(), bytes, &written) || written != bytes) {
            print_last_error(L"WriteProcessMemory");
            goto done;
        }
        logw(L"[+] WriteProcessMemory OK: %zu bytes\n", static_cast<size_t>(written));
    }

    {
        HMODULE kernel32 = GetModuleHandleW(L"kernel32.dll");
        auto load_library = reinterpret_cast<LPTHREAD_START_ROUTINE>(GetProcAddress(kernel32, "LoadLibraryW"));
        if (!load_library) {
            print_last_error(L"GetProcAddress(LoadLibraryW)");
            goto done;
        }

        thread = CreateRemoteThread(proc, nullptr, 0, load_library, remote_mem, 0, nullptr);
        if (!thread) {
            print_last_error(L"CreateRemoteThread");
            goto done;
        }
        logw(L"[+] CreateRemoteThread OK\n");
    }

    {
        DWORD wait = WaitForSingleObject(thread, 30000);
        if (wait != WAIT_OBJECT_0) {
            logw(L"[-] WaitForSingleObject returned %lu\n", wait);
            goto done;
        }

        DWORD remote_module = 0;
        if (!GetExitCodeThread(thread, &remote_module)) {
            print_last_error(L"GetExitCodeThread");
        } else {
            logw(L"[+] LoadLibraryW returned remote HMODULE: 0x%08lx\n", remote_module);
            ok = remote_module != 0;
        }
    }

done:
    if (thread) CloseHandle(thread);
    if (remote_mem) VirtualFreeEx(proc, remote_mem, 0, MEM_RELEASE);
    CloseHandle(proc);
    return ok;
}

struct CliArgs {
    std::wstring dll_path;
    std::wstring target_path;
    DWORD        poll_ms        = kDefaultPollMs;
    bool         once           = false;
    bool         keep_watching  = false; // продолжать после успешного инжекта
};

static void print_usage(const wchar_t* argv0) {
    logw(L"usage: %ls [--once] [--keep] [--interval <ms>] [--no-pause] [<dll>] [<target>]\n", argv0);
    logw(L"  default mode: watch for target exe and inject on first match\n");
    logw(L"  --once          single-shot search instead of watch\n");
    logw(L"  --keep          keep watching after successful inject (catch restarts)\n");
    logw(L"  --interval <ms> poll interval in watch mode (default %lu)\n", kDefaultPollMs);
    logw(L"  --no-pause      don't wait for Enter at the end\n");
}

static bool parse_cli(int argc, wchar_t** argv, CliArgs& out) {
    out.dll_path    = kDefaultDllPath;
    out.target_path = kDefaultTargetPath;

    int positional = 0;
    for (int i = 1; i < argc; ++i) {
        const wchar_t* a = argv[i];
        if (!_wcsicmp(a, L"--once"))       { out.once = true; continue; }
        if (!_wcsicmp(a, L"--keep"))       { out.keep_watching = true; continue; }
        if (!_wcsicmp(a, L"--no-pause"))   { g_no_pause = true; continue; }
        if (!_wcsicmp(a, L"--interval")) {
            if (i + 1 >= argc) { logw(L"[-] --interval requires a value\n"); return false; }
            out.poll_ms = static_cast<DWORD>(_wtoi(argv[++i]));
            if (out.poll_ms == 0) out.poll_ms = 1;
            continue;
        }
        if (!_wcsicmp(a, L"--help") || !_wcsicmp(a, L"-h") || !_wcsicmp(a, L"/?")) {
            print_usage(argv[0]);
            std::exit(0);
        }
        if (a[0] == L'-' && a[1] == L'-') {
            logw(L"[-] unknown option: %ls\n", a);
            print_usage(argv[0]);
            return false;
        }
        if (positional == 0)      out.dll_path    = absolute_path(a);
        else if (positional == 1) out.target_path = absolute_path(a);
        else { logw(L"[-] too many positional args\n"); return false; }
        ++positional;
    }
    return true;
}

int wmain(int argc, wchar_t** argv) {
    // Включаем wide-вывод в консоль — иначе кириллица в путях ломается.
    _setmode(_fileno(stdout), _O_U16TEXT);
    _setmode(_fileno(stderr), _O_U16TEXT);

    // Env-флаг для совместимости с прежним сценарием.
    wchar_t no_pause_env[8]{};
    if (GetEnvironmentVariableW(L"JNI_INJECTOR_NO_PAUSE", no_pause_env,
                                static_cast<DWORD>(std::size(no_pause_env))) > 0) {
        g_no_pause = !_wcsicmp(no_pause_env, L"1") || !_wcsicmp(no_pause_env, L"true");
    }

    CliArgs args;
    if (!parse_cli(argc, argv, args)) {
        pause_before_exit();
        return 1;
    }

    logw(L"[+] target path: %ls\n", args.target_path.c_str());
    logw(L"[+] dll path:    %ls\n", args.dll_path.c_str());

    DWORD attrs = GetFileAttributesW(args.dll_path.c_str());
    if (attrs == INVALID_FILE_ATTRIBUTES || (attrs & FILE_ATTRIBUTE_DIRECTORY)) {
        logw(L"[-] DLL file does not exist: %ls\n", args.dll_path.c_str());
        pause_before_exit();
        return 1;
    }

    const std::wstring exe_name = exe_name_from_path(args.target_path);

    if (args.once) {
        DWORD pid = find_process(exe_name, args.target_path);
        if (!pid) {
            logw(L"[-] target process was not found by full path\n");
            pause_before_exit();
            return 1;
        }
        logw(L"[+] found pid:   %lu\n", pid);
        bool ok = inject_dll(pid, args.dll_path);
        logw(L"[+] injection %ls. DLL log: C:\\Logs\\jni_rva_check.log\n",
             ok ? L"finished" : L"FAILED");
        pause_before_exit();
        return ok ? 0 : 1;
    }

    // Watcher mode
    logw(L"[watch] polling every %lu ms for %ls\n", args.poll_ms, exe_name.c_str());
    logw(L"[watch] press Ctrl+C to stop\n");

    DWORD last_pid = 0;
    int   injected_count = 0;

    for (;;) {
        DWORD pid = find_process(exe_name, args.target_path);

        if (pid && pid != last_pid) {
            logw(L"[watch] [%lu ms] pid=%lu appeared, injecting...\n", GetTickCount(), pid);
            last_pid = pid;

            bool ok = inject_dll(pid, args.dll_path);
            if (ok) {
                ++injected_count;
                logw(L"[watch] inject OK (#%d). DLL log: C:\\Logs\\jni_rva_check.log\n",
                     injected_count);
                if (!args.keep_watching) {
                    pause_before_exit();
                    return 0;
                }
                logw(L"[watch] --keep set, continuing to watch for restarts\n");
            } else {
                logw(L"[watch] inject FAILED for pid=%lu, will keep watching\n", pid);
            }
        } else if (!pid) {
            // Сбрасываем last_pid, как только процесс исчез — чтобы поймать рестарт
            // с тем же pid (редко, но бывает на коротких окнах).
            last_pid = 0;
        }

        Sleep(args.poll_ms);
    }
}
