#!/usr/bin/env python3
"""Dump members of a class directly from minecraft_FULL_DEOBF.jar by slash name."""
import struct, sys, zipfile
sys.path.insert(0, r'C:\Users\Admin\Desktop\rustme\tools')
from class_analyzer import parse_cp, utf8

JAR = r'C:\Users\Admin\Desktop\rustme\dump\minecraft_FULL_DEOBF.jar'

def flags(f):
    s = []
    if f & 0x0001: s.append('public')
    if f & 0x0002: s.append('private')
    if f & 0x0004: s.append('protected')
    if f & 0x0008: s.append('static')
    if f & 0x0010: s.append('final')
    if f & 0x0400: s.append('abstract')
    if f & 0x0800: s.append('native')
    if f & 0x0040: s.append('volatile')
    return ' '.join(s)

def main():
    name = sys.argv[1]  # e.g. rustme/liIIliliiI
    zf = zipfile.ZipFile(JAR)
    d = zf.read(name + '.class')
    cp, minor, major, i = parse_cp(d)
    access, this_c, super_c = struct.unpack_from('>HHH', d, i); i += 6
    n_ifc = struct.unpack_from('>H', d, i)[0]; i += 2 + 2 * n_ifc
    print('CLASS %s  (%s)' % (utf8(cp, this_c), flags(access)))
    print('SUPER %s' % utf8(cp, super_c))
    for k in range(n_ifc):
        print('IFACE %s' % utf8(cp, struct.unpack_from('>H', d, i + 2 * k)[0]))
    i += 2 * n_ifc

    def members(i):
        n = struct.unpack_from('>H', d, i)[0]; p = i + 2
        out = []
        for _ in range(n):
            acc, ni, di = struct.unpack_from('>HHH', d, p); p += 6
            na = struct.unpack_from('>H', d, p)[0]; p += 2
            attrs = []
            for _a in range(na):
                an = utf8(cp, struct.unpack_from('>H', d, p)[0])
                ln = struct.unpack_from('>I', d, p + 2)[0]
                attrs.append((an, d[p + 6:p + 6 + ln])); p += 6 + ln
            out.append((acc, utf8(cp, ni), utf8(cp, di), attrs))
        return out, p

    fields, i = members(i)
    for acc, nm, de, _ in fields:
        print('  FIELD %-28s %-22s %s' % (flags(acc), nm, de))
    methods, i = members(i)
    for acc, nm, de, _ in methods:
        print('  METHOD %-28s %-22s %s' % (flags(acc), nm, de))

if __name__ == '__main__':
    main()
