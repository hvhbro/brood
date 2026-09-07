#!/usr/bin/env python3
"""Mini-disassembler for a method of an obfuscated class file (opcode walker)."""
import sys, os, struct
sys.path.insert(0, r'C:\Users\Admin\Desktop\rustme\tools')
from class_analyzer import parse_cp, utf8, class_name

OPS3 = {0xb4: 'getfield', 0xb5: 'putfield', 0xb2: 'getstatic', 0xb3: 'putstatic',
        0xb6: 'invokevirtual', 0xb7: 'invokespecial', 0xb8: 'invokestatic',
        0xb9: 'invokeinterface', 0xba: 'invokedynamic'}
BRANCH = {0xc6, 0xc7, 0xa7, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f,
          0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xc5}
OPNAMES = {0xb4: 'getfield', 0xb5: 'putfield', 0xb2: 'getstatic', 0xb3: 'putstatic',
           0xb6: 'invokevirtual', 0xb7: 'invokespecial', 0xb8: 'invokestatic',
           0xb9: 'invokeinterface', 0x19: 'aload', 0x2a: 'aload_0', 0x2b: 'aload_1',
           0x2c: 'aload_2', 0x2d: 'aload_3', 0x4b: 'astore_0', 0x4c: 'astore_1',
           0xbb: 'new', 0xc0: 'checkcast', 0xc1: 'instanceof', 0xb1: 'return',
           0xac: 'ireturn', 0xb0: 'areturn', 0xae: 'freturn', 0xa9: 'lreturn', 0xaf: 'dreturn'}


def cpdesc(cp, idx):
    e = cp[idx]
    if e is None:
        return '?'
    if e[0] in (9, 10, 11):  # field/method refs
        c, nt = e[1], e[2]
        cn = utf8(cp, cp[c][1]) if cp[c] and cp[c][0] == 7 else '?'
        if cp[nt] and cp[nt][0] == 12:
            return f'{cn}.{utf8(cp, cp[nt][1])}:{utf8(cp, cp[nt][2])}'
        return cn
    if e[0] == 7:
        return utf8(cp, e[1])
    if e[0] == 8:
        return f'"{utf8(cp, e[1])}"'
    return f'tag{e[0]}'


def main():
    BASE = r'C:\Users\Admin\Desktop\rustme\dump\classes\minecraft\rustme'
    import glob
    pat = sys.argv[1]
    want = sys.argv[2]
    stem = pat.split('(')[0]
    fns = [f for f in glob.glob(os.path.join(BASE, stem + '(*.class')) if os.path.basename(f).startswith(stem + '(')]
    if not fns:
        fns = glob.glob(os.path.join(BASE, pat + "(*)"))
    fn = fns[0]
    data = open(fn, 'rb').read()
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
    print('=== methods matching:', want)
    for a, nm, d, attrs in methods:
        if want not in nm:
            continue
        print(f'METHOD {nm} {d} ({"static" if a & 8 else "virtual"})')
        for an, body in attrs:
            if an != 'Code':
                continue
            clen = struct.unpack_from('>I', body, 4)[0]
            cbody = body[8:8 + clen]
            j = 0
            while j < len(cbody):
                op = cbody[j]
                if op in (0xb4, 0xb5, 0xb2, 0xb3, 0xb6, 0xb7, 0xb8):
                    cpidx = struct.unpack_from('>H', cbody, j + 1)[0]
                    print(f'  {j:5d} {OPNAMES.get(op, hex(op)):16s} {cpdesc(cp, cpidx)}')
                    j += 3
                elif op == 0xb9:
                    cpidx = struct.unpack_from('>H', cbody, j + 1)[0]
                    print(f'  {j:5d} invokeinterface   {cpdesc(cp, cpidx)}')
                    j += 5
                elif op in (0xbb, 0xc0, 0xc1):
                    cpidx = struct.unpack_from('>H', cbody, j + 1)[0]
                    print(f'  {j:5d} {OPNAMES.get(op, hex(op)):16s} {cpdesc(cp, cpidx)}')
                    j += 3
                elif op in BRANCH:
                    j += 3
                elif op == 0xc5:
                    j += 4
                elif op == 0x13 or op == 0x14:  # ldc2_w
                    j += 3
                elif op == 0x11:
                    j += 3
                elif op in (0x10, 0x12):
                    j += 2
                elif op == 0xc4:  # wide
                    j += 6
                elif op == 0xaa:  # tableswitch
                    p0 = (j + 4) & ~3
                    lo, hi = struct.unpack_from('>ii', cbody, p0)
                    j = p0 + 8 + 4 * (hi - lo + 1)
                elif op == 0xab:  # lookupswitch
                    p0 = (j + 4) & ~3
                    npairs = struct.unpack_from('>i', cbody, p0)[0]
                    j = p0 + 4 + 8 * npairs
                else:
                    j += 1


if __name__ == '__main__':
    main()
