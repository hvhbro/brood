#pragma once

#include <cstdint>

#include "offsets.h"

// Указатели на JNI функции, как они выглядят в выгруженной таблице jvm.dll.
using GetEnvFn              = jint(__fastcall*)(std::int64_t jvm, void** env, jint version);
using AttachCurrentThreadFn = jint(__fastcall*)(std::int64_t jvm, void** env, void* args);
using FindClassFn           = void*(__fastcall*)(std::int64_t env, const char* name);
