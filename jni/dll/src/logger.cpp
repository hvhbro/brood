#include "logger.h"

#include <cstdarg>
#include <cstdio>

namespace {

constexpr WORD kFgGreen  = FOREGROUND_GREEN | FOREGROUND_INTENSITY;
constexpr WORD kFgRed    = FOREGROUND_RED   | FOREGROUND_INTENSITY;
constexpr WORD kFgYellow = FOREGROUND_RED | FOREGROUND_GREEN | FOREGROUND_INTENSITY;
constexpr WORD kFgCyan   = FOREGROUND_BLUE | FOREGROUND_GREEN | FOREGROUND_INTENSITY;
constexpr WORD kFgGray   = FOREGROUND_RED | FOREGROUND_GREEN | FOREGROUND_BLUE;

HANDLE g_log     = INVALID_HANDLE_VALUE;
HANDLE g_console = INVALID_HANDLE_VALUE;
WORD   g_default = kFgGray;

WORD pick_color(const char* line) {
    while (*line == ' ' || *line == '\t') ++line;
    if (line[0] != '[' || line[2] != ']') return g_default;
    switch (line[1]) {
        case '+': return kFgGreen;
        case '-': return kFgRed;
        case '!': return kFgYellow;
        default:  return kFgCyan;
    }
}

void write_file(const char* buf, DWORD n) {
    if (g_log == INVALID_HANDLE_VALUE) return;
    DWORD written = 0;
    WriteFile(g_log, buf, n, &written, nullptr);
}

void write_console(const char* buf, DWORD n) {
    if (g_console == INVALID_HANDLE_VALUE) return;
    SetConsoleTextAttribute(g_console, pick_color(buf));
    DWORD written = 0;
    WriteConsoleA(g_console, buf, n, &written, nullptr);
    SetConsoleTextAttribute(g_console, g_default);
}

} // namespace

void logger_init_console() {
    if (!AllocConsole()) AttachConsole(ATTACH_PARENT_PROCESS);
    SetConsoleTitleA("jni_rva_check");
    g_console = GetStdHandle(STD_OUTPUT_HANDLE);

    CONSOLE_SCREEN_BUFFER_INFO csbi{};
    if (g_console && g_console != INVALID_HANDLE_VALUE &&
        GetConsoleScreenBufferInfo(g_console, &csbi)) {
        g_default = csbi.wAttributes;
    }

    FILE* dummy = nullptr;
    freopen_s(&dummy, "CONOUT$", "w", stdout);
    freopen_s(&dummy, "CONOUT$", "w", stderr);
    freopen_s(&dummy, "CONIN$",  "r", stdin);
}

void logger_open_file(const char* path) {
    g_log = CreateFileA(path, GENERIC_WRITE, FILE_SHARE_READ, nullptr,
                        CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
}

void logger_close_file() {
    if (g_log == INVALID_HANDLE_VALUE) return;
    CloseHandle(g_log);
    g_log = INVALID_HANDLE_VALUE;
}

void log_line(const char* fmt, ...) {
    char buf[2048];
    va_list args;
    va_start(args, fmt);
    int n = vsnprintf(buf, sizeof(buf) - 3, fmt, args);
    va_end(args);
    if (n < 0) return;
    if (n > static_cast<int>(sizeof(buf) - 3)) n = static_cast<int>(sizeof(buf) - 3);
    buf[n++] = '\r';
    buf[n++] = '\n';
    buf[n]   = '\0';

    OutputDebugStringA(buf);
    write_file(buf, static_cast<DWORD>(n));
    write_console(buf, static_cast<DWORD>(n));
}
