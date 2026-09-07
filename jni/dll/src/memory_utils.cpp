#include "memory_utils.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>

bool readable_ptr(const void* p, std::size_t size) {
    MEMORY_BASIC_INFORMATION mbi{};
    if (!VirtualQuery(p, &mbi, sizeof(mbi))) return false;
    if (mbi.State != MEM_COMMIT) return false;
    if (mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD)) return false;

    auto start      = reinterpret_cast<std::uintptr_t>(p);
    auto end        = start + size;
    auto region_end = reinterpret_cast<std::uintptr_t>(mbi.BaseAddress) + mbi.RegionSize;
    return end >= start && end <= region_end;
}
