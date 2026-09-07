#!/usr/bin/env python3
"""Parse a Windows user-mode minidump: list modules, build a VA->bytes reader.

Implements just enough of MINIDUMP format (STREAM enums, Memory64ListStream,
MemoryListStream, ModuleListStream) without external deps.
"""
import struct
import sys

# Stream types
MODULE_LIST_STREAM = 4
MEMORY_LIST_STREAM = 5
EXCEPTION_STREAM = 6
MEMORY64_LIST_STREAM = 9
SYSTEM_INFO_STREAM = 7

class Minidump:
    def __init__(self, path):
        self.f = open(path, 'rb')
        hdr = self.f.read(32)
        sig, ver, nstreams, rva, chk, ts, flags = struct.unpack('<IIIIIIQ', hdr)
        assert sig == 0x504D444D, 'not a minidump (MDMP)'  # 'MDMP' little-endian
        self.streams = {}
        for i in range(nstreams):
            self.f.seek(rva + i * 12)
            st, sz, srva = struct.unpack('<III', self.f.read(12))
            self.streams.setdefault(st, []).append((srva, sz))
        self.modules = []
        self.mem_regions = []   # (start_va, size, file_offset)
        self._parse_modules()
        self._parse_memory()

    def _parse_modules(self):
        if MODULE_LIST_STREAM not in self.streams:
            return
        srva, sz = self.streams[MODULE_LIST_STREAM][0]
        self.f.seek(srva)
        (n,) = struct.unpack('<I', self.f.read(4))
        off = srva + 4
        for i in range(n):
            self.f.seek(off)
            # MINIDUMP_MODULE: 108 bytes
            data = self.f.read(108)
            (base, msize, chk, ts, namerva, vsz, cvrva, cvsz, dwrva, dwsz) = \
                struct.unpack('<QIIIIIIIII', data[:44])
            # MINIDUMP_STRING at namerva: u4 length (bytes), then UTF-16
            self.f.seek(namerva)
            (ln,) = struct.unpack('<I', self.f.read(4))
            name = self.f.read(ln).decode('utf-16-le', errors='replace')
            self.modules.append((base, msize, name))
            off += 108

    def _parse_memory(self):
        if MEMORY64_LIST_STREAM in self.streams:
            srva, _ = self.streams[MEMORY64_LIST_STREAM][0]
            self.f.seek(srva)
            nranges, base_rva = struct.unpack('<QQ', self.f.read(16))
            cur = base_rva
            for i in range(nranges):
                self.f.seek(srva + 16 + i * 16)
                start, size = struct.unpack('<QQ', self.f.read(16))
                self.mem_regions.append((start, size, cur))
                cur += size
        elif MEMORY_LIST_STREAM in self.streams:
            srva, _ = self.streams[MEMORY_LIST_STREAM][0]
            self.f.seek(srva)
            (n,) = struct.unpack('<I', self.f.read(4))
            for i in range(n):
                self.f.seek(srva + 4 + i * 16)
                start, size, rva = struct.unpack('<QII', self.f.read(16))
                self.mem_regions.append((start, size, rva))
        self.mem_regions.sort()

    def read(self, va, size):
        """Read bytes at virtual address; returns bytes or None if unmapped."""
        out = bytearray()
        end = va + size
        while va < end:
            # binary search region containing va
            lo, hi = 0, len(self.mem_regions)
            idx = None
            while lo < hi:
                mid = (lo + hi) // 2
                s, sz, _ = self.mem_regions[mid]
                if va < s:
                    hi = mid
                elif va >= s + sz:
                    lo = mid + 1
                else:
                    idx = mid
                    break
            if idx is None:
                break
            s, sz, fo = self.mem_regions[idx]
            take = min(sz - (va - s), end - va)
            self.f.seek(fo + (va - s))
            out += self.f.read(take)
            va += take
        if len(out) != size:
            return None
        return bytes(out)

    def find_module(self, substr):
        for base, msize, name in self.modules:
            if substr.lower() in name.lower():
                return (base, msize, name)
        return None


if __name__ == '__main__':
    md = Minidump(sys.argv[1])
    print(f"modules: {len(md.modules)}, mem regions: {len(md.mem_regions)}")
    for base, msize, name in md.modules:
        print(f"  {base:#018x} +{msize:#x}  {name}")
