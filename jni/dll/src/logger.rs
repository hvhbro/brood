// Логгер: порт src/logger.cpp/.h 1:1.
// Лог — C:\Logs\brood.log (+ консоль с цветами + OutputDebugStringA).

use core::ffi::c_void;
use core::ptr::{null, null_mut};
use core::sync::atomic::{AtomicPtr, AtomicU16, Ordering};
use windows_sys::Win32::Foundation::{
    CloseHandle, HANDLE, GENERIC_WRITE, INVALID_HANDLE_VALUE,
};
use windows_sys::Win32::Storage::FileSystem::{
    CreateFileA, WriteFile, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, FILE_SHARE_READ,
};
use windows_sys::Win32::System::Console::{
    AllocConsole, AttachConsole, GetConsoleScreenBufferInfo, GetStdHandle,
    SetConsoleTextAttribute, SetConsoleTitleA, WriteConsoleA, ATTACH_PARENT_PROCESS,
    CONSOLE_SCREEN_BUFFER_INFO, FOREGROUND_BLUE, FOREGROUND_GREEN, FOREGROUND_INTENSITY,
    FOREGROUND_RED, STD_OUTPUT_HANDLE,
};
use windows_sys::Win32::System::Diagnostics::Debug::OutputDebugStringA;

const FG_GREEN: u16 = FOREGROUND_GREEN | FOREGROUND_INTENSITY;
const FG_RED: u16 = FOREGROUND_RED | FOREGROUND_INTENSITY;
const FG_YELLOW: u16 = FOREGROUND_RED | FOREGROUND_GREEN | FOREGROUND_INTENSITY;
const FG_CYAN: u16 = FOREGROUND_BLUE | FOREGROUND_GREEN | FOREGROUND_INTENSITY;
const FG_GRAY: u16 = FOREGROUND_RED | FOREGROUND_GREEN | FOREGROUND_BLUE;

static G_LOG: AtomicPtr<c_void> = AtomicPtr::new(INVALID_HANDLE_VALUE);
static G_CONSOLE: AtomicPtr<c_void> = AtomicPtr::new(INVALID_HANDLE_VALUE);
static G_DEFAULT: AtomicU16 = AtomicU16::new(FG_GRAY);

fn pick_color(line: &[u8]) -> u16 {
    let mut s = line;
    while matches!(s.first(), Some(b' ' | b'\t')) {
        s = &s[1..];
    }
    if s.len() < 3 || s[0] != b'[' || s[2] != b']' {
        return G_DEFAULT.load(Ordering::Relaxed);
    }
    match s[1] {
        b'+' => FG_GREEN,
        b'-' => FG_RED,
        b'!' => FG_YELLOW,
        _ => FG_CYAN,
    }
}

fn write_file(buf: &[u8]) {
    let h: HANDLE = G_LOG.load(Ordering::Relaxed);
    if h == INVALID_HANDLE_VALUE {
        return;
    }
    unsafe {
        let mut written: u32 = 0;
        WriteFile(
            h,
            buf.as_ptr(),
            buf.len() as u32,
            &mut written,
            null_mut(),
        );
    }
}

fn write_console(buf: &[u8]) {
    let h: HANDLE = G_CONSOLE.load(Ordering::Relaxed);
    if h == INVALID_HANDLE_VALUE {
        return;
    }
    unsafe {
        SetConsoleTextAttribute(h, pick_color(buf));
        let mut written: u32 = 0;
        WriteConsoleA(h, buf.as_ptr(), buf.len() as u32, &mut written, null());
        SetConsoleTextAttribute(h, G_DEFAULT.load(Ordering::Relaxed));
    }
}

pub fn logger_init_console() {
    unsafe {
        if AllocConsole() == 0 {
            AttachConsole(ATTACH_PARENT_PROCESS);
        }
        SetConsoleTitleA(c"brood".as_ptr() as *const u8);
        let h: HANDLE = GetStdHandle(STD_OUTPUT_HANDLE);
        G_CONSOLE.store(h, Ordering::Relaxed);

        let mut csbi: CONSOLE_SCREEN_BUFFER_INFO = core::mem::zeroed();
        if !h.is_null()
            && h != INVALID_HANDLE_VALUE
            && GetConsoleScreenBufferInfo(h, &mut csbi) != 0
        {
            G_DEFAULT.store(csbi.wAttributes, Ordering::Relaxed);
        }
        // NOTE: в C++ здесь ещё freopen CONOUT$/CONIN$ для CRT stdio.
        // Наш логгер пишет через WriteConsoleA напрямую (как и оригинал),
        // printf никто не пользуется — freopen функционально не нужен.
    }
}

pub fn logger_open_file(path: &core::ffi::CStr) {
    unsafe {
        let h = CreateFileA(
            path.as_ptr() as *const u8,
            GENERIC_WRITE,
            FILE_SHARE_READ,
            null(),
            CREATE_ALWAYS,
            FILE_ATTRIBUTE_NORMAL,
            null_mut(),
        );
        G_LOG.store(h, Ordering::Relaxed);
    }
}

pub fn logger_close_file() {
    let h = G_LOG.swap(INVALID_HANDLE_VALUE, Ordering::Relaxed);
    if h == INVALID_HANDLE_VALUE {
        return;
    }
    unsafe {
        CloseHandle(h);
    }
}

/// Форматированный лог. Семантика C++ log_line: vsnprintf в буфер 2048
/// (резерв 3 байта) + "\r\n", затем OutputDebugStringA + файл + консоль.
pub fn log_line_msg(msg: &str) {
    // vsnprintf-транкейт оригинала: не больше 2045 байт полезной нагрузки.
    let mut bytes: &[u8] = msg.as_bytes();
    if bytes.len() > 2045 {
        bytes = &bytes[..2045];
    }
    let mut owned = Vec::with_capacity(bytes.len() + 3);
    owned.extend_from_slice(bytes);
    owned.extend_from_slice(b"\r\n\0");
    let len = owned.len() - 1; // без NUL для WriteFile/WriteConsoleA
    unsafe {
        OutputDebugStringA(owned.as_ptr());
    }
    write_file(&owned[..len]);
    write_console(&owned[..len]);
}

/// log_line("...") как в C++ (format!-стиль вместо printf-стиля).
#[macro_export]
macro_rules! log_line {
    ($($t:tt)*) => {
        $crate::logger::log_line_msg(&format!($($t)*))
    };
}
