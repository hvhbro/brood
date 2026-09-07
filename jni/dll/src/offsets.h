#pragma once

#include <cstdint>

using jint = int;

namespace jni_offsets {

// RVA внутри jvm.dll, полученные из минидампа запущенной игры
// (rustme_26116_1788379393.dmp, обновлено 2026-09-02).
inline constexpr std::uintptr_t kJavaVmRva               = 0xE6D248;
inline constexpr std::uintptr_t kInvokeTableRva          = 0xC5DCD0;
inline constexpr std::uintptr_t kNativeTableRva          = 0xBD2898;
inline constexpr std::uintptr_t kObservedLiveEnvTableRva = 0xE6CAF0;

// Константы JNI.
inline constexpr jint kJniVersion16 = 0x00010006;
inline constexpr jint kJniOk        = 0;
inline constexpr jint kJniDetached  = -2;

} // namespace jni_offsets
