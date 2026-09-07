#!/usr/bin/env python3
"""Parse all obfuscated classes (constant pool, hierarchy, counts) into one corpus."""
import os, sys, json, struct
from collections import defaultdict

BASE = r'C:\Users\Admin\Desktop\rustme\dump\minecraft\rustme'
OUT = r'C:\Users\Admin\Desktop\rustme\tools\class_report'
os.makedirs(OUT, exist_ok=True)


def parse_cp(data):
    """Parse constant pool; returns list of (tag, value)."""
    minor, major = struct.unpack_from('>HH', data, 4)
    cp_count = struct.unpack_from('>H', data, 8)[0]
    cp = [None] * cp_count
    i = 10
    idx = 1
    while idx < cp_count:
        tag = data[i]
        if tag == 1:  # Utf8
            ln = struct.unpack_from('>H', data, i + 1)[0]
            cp[idx] = (1, data[i + 3:i + 3 + ln])
            i += 3 + ln
        elif tag == 7:  # Class
            cp[idx] = (7, struct.unpack_from('>H', data, i + 1)[0])
            i += 3
        elif tag == 9 or tag == 10 or tag == 11:  # Field/Method/InterfaceMethodref
            c, nt = struct.unpack_from('>HH', data, i + 1)
            cp[idx] = (tag, c, nt)
            i += 5
        elif tag == 8:  # String
            cp[idx] = (8, struct.unpack_from('>H', data, i + 1)[0])
            i += 3
        elif tag == 3:  # Integer
            cp[idx] = (3, struct.unpack_from('>i', data, i + 1)[0])
            i += 5
        elif tag == 4:  # Float
            cp[idx] = (4, struct.unpack_from('>f', data, i + 1)[0])
            i += 5
        elif tag == 5:  # Long
            cp[idx] = (5, struct.unpack_from('>q', data, i + 1)[0])
            i += 9; idx += 1
        elif tag == 6:  # Double
            cp[idx] = (6, struct.unpack_from('>d', data, i + 1)[0])
            i += 9; idx += 1
        elif tag == 12:  # NameAndType
            n, d = struct.unpack_from('>HH', data, i + 1)
            cp[idx] = (12, n, d)
            i += 5
        elif tag == 15:  # MethodHandle
            cp[idx] = (15, data[i + 1], struct.unpack_from('>H', data, i + 2)[0])
            i += 4
        elif tag == 16:  # MethodType
            cp[idx] = (16, struct.unpack_from('>H', data, i + 1)[0])
            i += 3
        elif tag == 17 or tag == 18:  # Dynamic/InvokeDynamic
            b, nt = struct.unpack_from('>HH', data, i + 1)
            cp[idx] = (tag, b, nt)
            i += 5
        elif tag == 19 or tag == 20:  # Module/Package
            cp[idx] = (tag, struct.unpack_from('>H', data, i + 1)[0])
            i += 3
        else:
            raise ValueError(f'bad tag {tag} at {i}')
        idx += 1
    return cp, minor, major, i


def utf8(cp, idx):
    if idx is None or idx >= len(cp) or cp[idx] is None:
        return ''
    t = cp[idx]
    if t[0] == 1:
        try:
            return t[1].decode('utf-8', errors='replace')
        except Exception:
            return ''
    return ''


def class_name(cp, idx):
    if idx is None or idx >= len(cp) or cp[idx] is None or cp[idx][0] != 7:
        return ''
    return utf8(cp, cp[idx][1])


def parse_class(data):
    cp, minor, major, i = parse_cp(data)
    access, this_c, super_c = struct.unpack_from('>HHH', data, i)
    i += 6
    n_ifc = struct.unpack_from('>H', data, i)[0]; i += 2
    ifaces = [class_name(cp, struct.unpack_from('>H', data, i + 2 * k)[0]) for k in range(n_ifc)]
    i += 2 * n_ifc

    def read_members(count_pos):
        n = struct.unpack_from('>H', data, count_pos)[0]
        p = count_pos + 2
        members = []
        for _ in range(n):
            a, n_i, d_i = struct.unpack_from('>HHH', data, p); p += 6
            n_attr = struct.unpack_from('>H', data, p)[0]; p += 2
            code_len = 0
            for _a in range(n_attr):
                an = utf8(cp, struct.unpack_from('>H', data, p)[0])
                ln = struct.unpack_from('>I', data, p + 2)[0]
                if an == 'Code':
                    code_len = ln
                p += 6 + ln
            members.append((utf8(cp, n_i), utf8(cp, d_i), code_len))
        return members, p

    fields, i = read_members(i)
    methods, i = read_members(i)

    strs = set()
    refs = set()
    for e in cp:
        if e is None:
            continue
        if e[0] == 1 and isinstance(e[1], bytes):
            try:
                strs.add(e[1].decode('utf-8', errors='replace'))
            except Exception:
                pass
        elif e[0] == 7:
            nm = utf8(cp, e[1])
            if nm:
                refs.add(nm)
        elif e[0] == 8:
            strs.add(utf8(cp, e[1]))

    return {
        'minor': minor, 'major': major,
        'this': class_name(cp, this_c),
        'super': class_name(cp, super_c),
        'ifaces': ifaces,
        'fields': fields, 'methods': methods,
        'strings': sorted(strs), 'refs': sorted(refs),
    }


def main():
    corpus = {}
    files = sorted(os.listdir(BASE))
    errs = 0
    for k, f in enumerate(files):
        try:
            data = open(os.path.join(BASE, f), 'rb').read()
            info = parse_class(data)
            info['file'] = f
            info['size'] = len(data)
            corpus[f] = info
        except Exception as e:
            errs += 1
        if k % 2000 == 0:
            print(f'{k}/{len(files)}')
    print(f'parsed {len(corpus)}, errors {errs}')
    with open(os.path.join(OUT, 'corpus.json'), 'w', encoding='utf-8') as f:
        json.dump(corpus, f)
    print('saved corpus.json')


if __name__ == '__main__':
    main()
