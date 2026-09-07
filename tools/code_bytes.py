#!/usr/bin/env python3
"""Dump raw code bytes of named methods from an obfuscated class.

Usage: code_bytes.py <file-or-name-pattern> <method-substring>
Name pattern may be e.g. 'IIlIIliIiI(23).class', 'IIlIIliIiI', or 'IIlIIliIiI('.
"""
import sys, os, struct, glob
sys.path.insert(0, r'C:\Users\Admin\Desktop\rustme\tools')
from class_analyzer import parse_cp, utf8, class_name

BASE = r'C:\Users\Admin\Desktop\rustme\dump\classes\minecraft\rustme'


def resolve(pat):
    for cand in (
            os.path.join(BASE, pat),
            os.path.join(BASE, pat + '(*.class'),
            os.path.join(BASE, pat + '.class'),
    ):
        g = glob.glob(cand)
        if g:
            return g[0]
    raise SystemExit(f'no file for pattern {pat!r}')


def main():
    pat, want = sys.argv[1], sys.argv[2]
    data = open(resolve(pat), 'rb').read()
    cp, minor, major, i = parse_cp(data)
    access, this_c, super_c = struct.unpack_from('>HHH', data, i)
    i += 6
    n_ifc = struct.unpack_from('>H', data, i)[0]; i += 2 + 2 * n_ifc

    def members(i):
        n = struct.unpack_from('>H', data, i)[0]; p = i + 2
        out = []
        for _ in range(n):
            a, n_i, d_i = struct.unpack_from('>HHH', data, p); p += 6
            n_attr = struct.unpack_from('>H', data, p)[0]; p += 2
            attrs = []
            for _a in range(n_attr):
                an = utf8(cp, struct.unpack_from('>H', data, p)[0])
                ln = struct.unpack_from('>I', data, p + 2)[0]
                attrs.append((an, data[p + 6:p + 6 + ln])); p += 6 + ln
            out.append((a, utf8(cp, n_i), utf8(cp, d_i), attrs))
        return out, p

    fields, i = members(i)
    methods, i = members(i)
    print('CLASS', class_name(cp, this_c))
    for a, nm, d, attrs in methods:
        if want not in nm:
            continue
        print(f'METHOD {nm}{d}')
        for an, body in attrs:
            if an == 'Code':
                clen = struct.unpack_from('>I', body, 4)[0]
                cb = body[8:8 + clen]
                print(' ', ' '.join(f'{b:02x}' for b in cb))


if __name__ == '__main__':
    main()
