// RVA внутри jvm.dll из минидампа запущенной игры
// (rustme_26116_1788379393.dmp, обновлено 2026-09-02).
// Порт src/offsets.h 1:1. Методика обновления при апдейте игры:
// tools/calibrate.py, tools/find_live3.py (см. ZNANIA раздел 2).

/// main_vm, JavaVM*.
pub const K_JAVA_VM_RVA: usize = 0xE6D248;
/// JavaVM vtable, 3 NULL + 5 fn (информативно: код берёт invoke[4]/[6] напрямую).
#[allow(dead_code)]
pub const K_INVOKE_TABLE_RVA: usize = 0xC5DCD0;
/// Pristine JNIEnv template, .rdata (информативно).
#[allow(dead_code)]
pub const K_NATIVE_TABLE_RVA: usize = 0xBD2898;
/// Живая JNIEnv таблица, .data (информативно).
#[allow(dead_code)]
pub const K_OBSERVED_LIVE_ENV_TABLE_RVA: usize = 0xE6CAF0;

/// Вход валидатора protected-классов (jvm.dll + RVA).
/// В C++ был захардкожен как 0x1C2D0 в dllmain.cpp — вынесен в константу,
/// значение то же.
pub const K_VALIDATOR_RVA: usize = 0x1C2D0;

// Константы JNI.
pub const K_JNI_VERSION_16: i32 = 0x0001_0006;
pub const K_JNI_OK: i32 = 0;
pub const K_JNI_DETACHED: i32 = -2;
