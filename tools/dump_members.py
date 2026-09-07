#!/usr/bin/env python3
"""Dump fields/methods with access flags for obfuscated dump classes."""
import sys, struct
sys.path.insert(0, r'C:\Users\Admin\Desktop\rustme\tools')
from class_analyzer import parse_cp, utf8, class_name


def flags(f):
    s = []
    if f & 0x0001: s.append('public')
    if f & 0x0002: s.append('private')
    if f & 0x0004: s.append('protected')
    if f & 0x0008: s.append('static')
    if f & 0x0010: s.append('final')
    if f & 0x0400: s.append('abstract')
    return ' '.join(s)


def dump(path):
    data = open(path, 'rb').read()
    cp, minor, major, i = parse_cp(data)
    access, this_c, super_c = struct.unpack_from('>HHH', data, i)
    i += 6
    n_ifc = struct.unpack_from('>H', data, i)[0]; i += 2
    i += 2 * n_ifc
    print(f'CLASS {class_name(cp, this_c)}  ({flags(access)})')
    print(f'SUPER {class_name(cp, super_c)}')

    def members(label):
        nonlocal i
        n = struct.unpack_from('>H', data, i)[0]
        p = i + 2
        out = []
        for _ in range(n):
            a, n_i, d_i = struct.unpack_from('>HHH', data, p); p += 6
            n_attr = struct.unpack_from('>H', data, p)[0]; p += 2
            for _a in range(n_attr):
                ln = struct.unpack_from('>I', data, p + 2)[0]
                p += 6 + ln
            out.append((flags(a), utf8(cp, n_i), utf8(cp, d_i)))
        i = p
        for fl, nm, d in out:
            print(f'  {label} {fl:35s} {nm:22s} {d}')

    members('FIELD')
    members('METHOD')


if __name__ == '__main__':
    for p in sys.argv[1:]:
        dump(p)
        print()
