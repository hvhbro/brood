#!/usr/bin/env python3
"""Full opcode disasm of a method range; usage: disasm_range.py <classfile> <method> [lo] [hi]"""
import struct, sys
sys.path.insert(0, r'C:\Users\Admin\Desktop\rustme\tools')
from class_analyzer import parse_cp, utf8

OPS = {0xb4:'getfield',0xb5:'putfield',0xb2:'getstatic',0xb3:'putstatic',
       0xb6:'invokevirtual',0xb7:'invokespecial',0xb8:'invokestatic',
       0xbb:'new',0xc0:'checkcast',0xc1:'instanceof'}
NAMES1 = {0x00:'nop',0x01:'aconst_null',0x02:'iconst_m1',0x03:'iconst_0',0x04:'iconst_1',
0x05:'iconst_2',0x06:'iconst_3',0x07:'iconst_4',0x08:'iconst_5',0x09:'lconst_0',0x0a:'lconst_1',
0x0b:'fconst_0',0x0c:'fconst_1',0x0d:'fconst_2',0x0e:'dconst_0',0x0f:'dconst_1',
0x1a:'iload_0',0x1b:'iload_1',0x1c:'iload_2',0x1d:'iload_3',
0x1e:'lload_0',0x1f:'lload_1',0x20:'lload_2',0x21:'lload_3',
0x22:'fload_0',0x23:'fload_1',0x24:'fload_2',0x25:'fload_3',
0x26:'dload_0',0x27:'dload_1',0x28:'dload_2',0x29:'dload_3',
0x2a:'aload_0',0x2b:'aload_1',0x2c:'aload_2',0x2d:'aload_3',
0x2e:'iaload',0x2f:'laload',0x30:'faload',0x31:'daload',0x32:'aaload',0x33:'baload',0x34:'caload',0x35:'saload',
0x3b:'istore_0',0x3c:'istore_1',0x3d:'istore_2',0x3e:'istore_3',
0x3f:'lstore_0',0x40:'lstore_1',0x41:'lstore_2',0x42:'lstore_3',
0x43:'fstore_0',0x44:'fstore_1',0x45:'fstore_2',0x46:'fstore_3',
0x47:'dstore_0',0x48:'dstore_1',0x49:'dstore_2',0x4a:'dstore_3',
0x4b:'astore_0',0x4c:'astore_1',0x4d:'astore_2',0x4e:'astore_3',
0x4f:'iastore',0x50:'lastore',0x51:'fastore',0x52:'dastore',0x53:'aastore',0x54:'bastore',0x55:'castore',0x56:'sastore',
0x57:'pop',0x58:'pop2',0x59:'dup',0x5a:'dup_x1',0x5b:'dup_x2',0x5c:'dup2',0x5d:'dup2_x1',0x5e:'dup2_x2',0x5f:'swap',
0xbe:'arraylength',0xbf:'athrow',0xc2:'monitorenter',0xc3:'monitorexit',
0xac:'ireturn',0xad:'lreturn',0xae:'freturn',0xaf:'dreturn',0xb0:'areturn',0xb1:'return',
0x60:'iadd',0x61:'ladd',0x62:'fadd',0x63:'dadd',0x64:'isub',0x65:'lsub',0x66:'fsub',0x67:'dsub',
0x68:'imul',0x69:'lmul',0x6a:'fmul',0x6b:'dmul',0x6c:'idiv',0x6d:'ldiv',0x6e:'fdiv',0x6f:'ddiv',
0x70:'irem',0x71:'lrem',0x72:'frem',0x73:'drem',0x74:'ineg',0x75:'lneg',0x76:'fneg',0x77:'dneg',
0x78:'ishl',0x79:'lshl',0x7a:'ishr',0x7b:'lshr',0x7c:'iushr',0x7d:'lushr',0x7e:'iand',0x7f:'land',
0x80:'ior',0x81:'lor',0x82:'ixor',0x83:'lxor',
0x85:'i2l',0x86:'i2f',0x87:'i2d',0x88:'l2i',0x89:'l2f',0x8a:'l2d',0x8b:'f2i',0x8c:'f2l',0x8d:'f2d',
0x8e:'d2i',0x8f:'d2l',0x90:'d2f',0x91:'i2b',0x92:'i2c',0x93:'i2s',
0x94:'lcmp',0x95:'fcmpl',0x96:'fcmpg',0x97:'dcmpl',0x98:'dcmpg'}
BRN = {0x99:'ifeq',0x9a:'ifne',0x9b:'iflt',0x9c:'ifge',0x9d:'ifgt',0x9e:'ifle',
0x9f:'if_icmpeq',0xa0:'if_icmpne',0xa1:'if_icmplt',0xa2:'if_icmpge',0xa3:'if_icmpgt',0xa4:'if_icmple',
0xa5:'if_acmpeq',0xa6:'if_acmpne',0xa7:'goto',0xc6:'ifnull',0xc7:'ifnonnull'}


def disasm(cb, cp, lo, hi):
    j = 0
    out = []

    def cpget(idx):
        return cp[idx] if 0 < idx < len(cp) else None
    while j < len(cb):
        op = cb[j]
        if lo <= j <= hi:
            if op in OPS:
                idx = struct.unpack_from('>H', cb, j + 1)[0]
                e = cpget(idx)
                ref = str(idx)
                if e:
                    if e[0] in (9, 10, 11):
                        c, nt = e[1], e[2]
                        cn = utf8(cp, cp[c][1]) if cp[c] and cp[c][0] == 7 else '?'
                        mn = utf8(cp, cp[nt][1]) if cp[nt] and cp[nt][0] == 12 else '?'
                        ref = cn + '.' + mn
                    elif e[0] == 7:
                        ref = utf8(cp, e[1])
                    elif e[0] == 8:
                        ref = '"' + utf8(cp, e[1]) + '"'
                out.append('  %5d %-16s %s' % (j, OPS[op], ref)); j += 3
            elif op in BRN:
                off = struct.unpack_from('>h', cb, j + 1)[0]
                out.append('  %5d %-16s -> %d' % (j, BRN[op], j + off)); j += 3
            elif op == 0x10:
                out.append('  %5d bipush          %d' % (j, struct.unpack_from('b', cb, j + 1)[0])); j += 2
            elif op == 0x11:
                out.append('  %5d sipush          %d' % (j, struct.unpack_from('>h', cb, j + 1)[0])); j += 3
            elif op == 0x12:
                idx = cb[j + 1]; e = cpget(idx)
                if e and e[0] == 8: r = '"' + utf8(cp, e[1]) + '"'
                elif e and e[0] == 7: r = 'class ' + utf8(cp, e[1])
                else: r = '#' + str(idx)
                out.append('  %5d ldc             %s' % (j, r)); j += 2
            elif op in (0x13, 0x14):
                idx = struct.unpack_from('>H', cb, j + 1)[0]; e = cpget(idx)
                if e and e[0] == 8: r = '"' + utf8(cp, e[1]) + '"'
                elif e and e[0] == 7: r = 'class ' + utf8(cp, e[1])
                else: r = '#' + str(idx)
                out.append('  %5d %-16s %s' % (j, 'ldc_w' if op == 0x13 else 'ldc2_w', r)); j += 3
            elif op == 0x15:
                out.append('  %5d iload           %d' % (j, cb[j + 1])); j += 2
            elif op in (0x16, 0x17, 0x18, 0x19):
                out.append('  %5d load%d          %d' % (j, op - 0x15, cb[j + 1])); j += 2
            elif op == 0x36:
                out.append('  %5d istore          %d' % (j, cb[j + 1])); j += 2
            elif op in (0x37, 0x38, 0x39, 0x3a):
                out.append('  %5d store%d         %d' % (j, op - 0x37, cb[j + 1])); j += 2
            elif op == 0x84:
                out.append('  %5d iinc            %d += %d' % (j, cb[j + 1], struct.unpack_from('b', cb, j + 2)[0])); j += 3
            elif op == 0xa9:
                out.append('  %5d ret             %d' % (j, cb[j + 1])); j += 2
            elif op == 0xc8:
                off = struct.unpack_from('>i', cb, j + 1)[0]
                out.append('  %5d goto_w          -> %d' % (j, j + off)); j += 5
            elif op == 0xc9:
                j += 5
            elif op == 0xc4:
                out.append('  %5d wide            op=%s idx=%d' % (j, hex(cb[j + 1]), struct.unpack_from('>H', cb, j + 2)[0]))
                j += 6 if cb[j + 1] == 0x84 else 4
            elif op == 0xc5:
                out.append('  %5d multianewarray  %d dim=%d' % (j, struct.unpack_from('>H', cb, j + 1)[0], cb[j + 3])); j += 4
            elif op == 0xaa:
                p0 = (j + 4) & ~3
                df = struct.unpack_from('>i', cb, p0 - 4)[0]
                lo_, hi_ = struct.unpack_from('>ii', cb, p0)
                out.append('  %5d tableswitch     default->%d lo=%d hi=%d' % (j, j + df, lo_, hi_))
                for k in range(hi_ - lo_ + 1):
                    t = struct.unpack_from('>i', cb, p0 + 4 + 4 * k)[0]
                    out.append('        case %d: -> %d' % (lo_ + k, j + t))
                j = p0 + 8 + 4 * (hi_ - lo_ + 1)
            elif op == 0xab:
                p0 = (j + 4) & ~3
                df = struct.unpack_from('>i', cb, p0 - 4)[0]
                np_ = struct.unpack_from('>i', cb, p0)[0]
                out.append('  %5d lookupswitch    default->%d pairs=%d' % (j, j + df, np_))
                for k in range(np_):
                    m, t = struct.unpack_from('>ii', cb, p0 + 4 + 8 * k)
                    out.append('        match %d: -> %d' % (m, j + t))
                j = p0 + 4 + 8 * np_
            elif op == 0xba:
                out.append('  %5d invokedynamic   #%d' % (j, struct.unpack_from('>H', cb, j + 1)[0])); j += 5
            elif op == 0xbc:
                out.append('  %5d newarray        atype=%d' % (j, cb[j + 1])); j += 2
            elif op == 0xbd:
                idx = struct.unpack_from('>H', cb, j + 1)[0]
                e = cpget(idx)
                out.append('  %5d anewarray       %s' % (j, utf8(cp, e[1]) if e and e[0] == 7 else str(idx))); j += 3
            elif op in NAMES1:
                out.append('  %5d %s' % (j, NAMES1[op]))
            else:
                out.append('  %5d ??? %s' % (j, hex(op)))
            j += 1
        else:
            if op in OPS or op in BRN:
                j += 3
            elif op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xbc, 0xa9):
                j += 2
            elif op in (0x11, 0x13, 0x14, 0x84):
                j += 3
            elif op in (0xc8, 0xc9, 0xba):
                j += 5
            elif op == 0xc4:
                j += 6 if cb[j + 1] == 0x84 else 4
            elif op == 0xc5:
                j += 4
            elif op == 0xaa:
                p0 = (j + 4) & ~3
                lo_, hi_ = struct.unpack_from('>ii', cb, p0)
                j = p0 + 8 + 4 * (hi_ - lo_ + 1)
            elif op == 0xab:
                p0 = (j + 4) & ~3
                np_ = struct.unpack_from('>i', cb, p0)[0]
                j = p0 + 4 + 8 * np_
            else:
                j += 1
    return out


def main():
    path = sys.argv[1]
    want = sys.argv[2]
    lo = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    hi = int(sys.argv[4]) if len(sys.argv) > 4 else 10 ** 9
    d = open(path, 'rb').read()
    cp, minor, major, i = parse_cp(d)
    access, this_c, super_c = struct.unpack_from('>HHH', d, i); i += 6
    n_ifc = struct.unpack_from('>H', d, i)[0]; i += 2 + 2 * n_ifc

    def members(i):
        n = struct.unpack_from('>H', d, i)[0]; p = i + 2
        out = []
        for _ in range(n):
            acc, ni, di = struct.unpack_from('>HHH', d, p); p += 6
            na = struct.unpack_from('>H', d, p)[0]; p += 2
            attrs = []
            for _x in range(na):
                an = utf8(cp, struct.unpack_from('>H', d, p)[0])
                ln = struct.unpack_from('>I', d, p + 2)[0]
                attrs.append((an, d[p + 6:p + 6 + ln])); p += 6 + ln
            out.append((acc, utf8(cp, ni), utf8(cp, di), attrs))
        return out, p

    fields, i = members(i)
    methods, i = members(i)
    for acc, nm, de, attrs in methods:
        if want not in nm:
            continue
        for an, body in attrs:
            if an != 'Code':
                continue
            clen = struct.unpack_from('>I', body, 4)[0]
            cb = body[8:8 + clen]
            print('=== %s %s (code %d) range %d-%d' % (nm, de, clen, lo, hi))
            for ln in disasm(cb, cp, lo, hi):
                print(ln)


if __name__ == '__main__':
    main()
