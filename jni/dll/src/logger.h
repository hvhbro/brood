#pragma once

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

void logger_init_console();
void logger_open_file(const char* path);
void logger_close_file();
void log_line(const char* fmt, ...);
