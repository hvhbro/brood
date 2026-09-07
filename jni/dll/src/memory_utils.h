#pragma once

#include <cstddef>

// Проверяет, что регион [p, p+size) полностью лежит в зафиксированной читаемой памяти.
bool readable_ptr(const void* p, std::size_t size = sizeof(void*));
