// Порт src/memory_utils.cpp/.h 1:1.
// Проверяет, что регион [p, p+size) лежит в закоммиченной читаемой памяти.
// (В оригинале функция нигде не вызывалась из dllmain — оставлена для
// паритета и будущих проб; поэтому allow(dead_code).)

use core::ffi::c_void;
use windows_sys::Win32::System::Memory::{
    VirtualQuery, MEMORY_BASIC_INFORMATION, MEM_COMMIT, PAGE_GUARD, PAGE_NOACCESS,
};

#[allow(dead_code)]
pub fn readable_ptr(p: *const c_void, size: usize) -> bool {
    unsafe {
        let mut mbi: MEMORY_BASIC_INFORMATION = core::mem::zeroed();
        if VirtualQuery(p, &mut mbi, core::mem::size_of::<MEMORY_BASIC_INFORMATION>()) == 0 {
            return false;
        }
        if mbi.State != MEM_COMMIT {
            return false;
        }
        if (mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD)) != 0 {
            return false;
        }
        let start = p as usize;
        let end = start.wrapping_add(size);
        let region_end = (mbi.BaseAddress as usize).wrapping_add(mbi.RegionSize);
        end >= start && end <= region_end
    }
}

/// Дефолт C++ (size = sizeof(void*)).
#[allow(dead_code)]
pub fn readable_ptr_word(p: *const c_void) -> bool {
    readable_ptr(p, core::mem::size_of::<*const c_void>())
}
