#!/usr/bin/env python3
"""Полный дизас NoSlowAgent (init, tick, run, install)."""
import struct

data = open(r'C:\Users\Admin\Desktop\rustme\tools\NoSlowAgent.class', 'rb').read()
cpc = struct.unpack_from('>H', data, 8)[0]
p = 10
utf = {}; cls_str = {}; refs = {}; strings = {}; i = 1
while i < cpc:
    t = data[p]; p += 1
    if t == 1:
        ln = struct.unpack_from('>H', data, p)[0]; p += 2
        utf[i] = data[p:p+ln].decode('utf8', 'replace'); p += ln
    elif t == 7:
        cls_str[i] = struct.unpack_from('>H', data, p)[0]; p += 2
    elif t == 8:
        strings[i] = struct.unpack_from('>H', data, p)[0]; p += 2
    elif t in (3, 4):
        p += 4
    elif t in (5, 6):
        p += 8; i += 1
    elif t == 15:
        p += 3
    elif t == 16:
        p += 2
    elif t in (9, 10, 11, 12, 17, 18):
        refs[i] = (struct.unpack_from('>H', data, p)[0], struct.unpack_from('>H', data, p+2)[0])
        p += 4
    else:
        raise ValueError('tag %d @ %#x' % (t, p-1))
    i += 1

p += 6
ic = struct.unpack_from('>H', data, p)[0]; p += 2 + ic*2

def rd(p):
    ac = struct.unpack_from('>H', data, p)[0]; p += 2
    out = []
    for __ in range(ac):
        an = struct.unpack_from('>H', data, p)[0]
        al = struct.unpack_from('>I', data, p+2)[0]
        out.append((utf.get(an, '?'), data[p+6:p+6+al]))
        p += 6 + al
    return p, out

fc = struct.unpack_from('>H', data, p)[0]; p += 2
for _ in range(fc):
    p += 6
    p, _ = rd(p)
mc = struct.unpack_from('>H', data, p)[0]
p += 2
OPS = {0xb8:'invokestatic',0xb6:'invokevirtual',0xb7:'invokespecial',0xb2:'getstatic',0xb3:'putstatic',
       0xb4:'getfield',0xb5:'putfield',0xbb:'new',0x12:'ldc',0x13:'ldc_w',0xc0:'checkcast',
       0xc6:'ifnull',0xc7:'ifnonnull',0x9a:'ifne',0x99:'ifeq',0xa7:'goto',0xb1:'return',
       0x4c:'astore_1',0x4d:'astore_2',0x2a:'aload_0',0x2b:'aload_1',0x2c:'aload_2',0x4b:'astore_0',
       0x57:'pop',0xbf:'athrow',0x01:'aconst_null',0x19:'aload',0x4e:'astore',0xb0:'areturn'}
wide = {0x10:2,0x12:2,0x11:3,0x13:3,0x14:3,0x84:3,0xbc:2,0xb9:5,0xba:5,0xc5:4,0xc8:5,0xc9:5,
        0x15:2,0x16:2,0x36:2,0x19:2,0x4e:2}
for _ in range(mc):
    an = struct.unpack_from('>H', data, p)[0]
    mname = utf.get(an, '?')
    p += 6
    p, attrs = rd(p)
    if mname not in ('init', 'tick'):
        continue
    for nm, body in attrs:
        if nm != 'Code':
            continue
        clen = struct.unpack_from('>I', body, 4)[0]
        code = body[8:8+clen]
        print('==== %s (%d bytes) ====' % (mname, clen))
        i2 = 0
        while i2 < len(code):
            op = code[i2]
            l = wide.get(op, 1)
            if 0x99 <= op <= 0xa8 or op in (0xc6, 0xc7):
                l = 3
            elif 0xb2 <= op <= 0xb8 or op in (0xbb, 0xbd, 0xc0, 0xc1):
                l = 3
            elif 0x15 <= op <= 0x19 or 0x36 <= op <= 0x3a:
                l = 2
            if op in (0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8):
                idx = struct.unpack_from('>H', code, i2+1)[0]
                a, b = refs.get(idx, (0, 0))
                owner = utf.get(cls_str.get(a, 0), '?')
                nmA = utf.get(a, '?') if a in utf else '?'
                nmB = utf.get(b, '?') if b in utf else '?'
                print('  %04d %s %s.%s : %s' % (i2, OPS.get(op, hex(op)), owner, nmA, nmB))
            elif op == 0x12:
                idx = code[i2+1]
                if idx in strings:
                    print('  %04d ldc %r' % (i2, utf.get(strings[idx], '?')))
                elif idx in cls_str:
                    print('  %04d ldc class %s' % (i2, utf.get(cls_str[idx], '?')))
            elif op == 0x13:
                idx = struct.unpack_from('>H', code, i2+1)[0]
                if idx in strings:
                    print('  %04d ldc_w %r' % (i2, utf.get(strings[idx], '?')))
            i2 += l
