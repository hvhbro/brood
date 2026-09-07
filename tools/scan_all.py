#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""scan_all.py — full CP scan of dump/classes/minecraft/rustme (9977 classes).
Builds scan_db.json: per-class fields/methods (name:desc), string constants,
class refs, counts. Uses own CP parser (parse_cp of class_analyzer lies on
big classes -> we use javap only for verification of winners)."""
import json, os, struct, sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DUMP = os.path.join(ROOT, 'dump', 'classes', 'minecraft', 'rustme')
OUT = os.path.join(ROOT, 'tools', 'scan_db.json')

def parse_class(path):
    d = open(path, 'rb').read()
    o = 8  # magic+minor+major
    n = struct.unpack_from('>H', d, o)[0]; o += 2
    consts = [None] * n
    i = 1
    while i < n:
        try:
            tag = d[o]; o += 1
            if tag == 1:
                l = struct.unpack_from('>H', d, o)[0]; o += 2
                try: consts[i] = (tag, d[o:o+l].decode('utf-8', 'replace'))
                except Exception: consts[i] = (tag, '')
                o += l
            elif tag in (7, 8, 16, 19, 20):
                consts[i] = (tag, struct.unpack_from('>H', d, o)[0]); o += 2
            elif tag == 15:
                consts[i] = (tag, d[o:o+3]); o += 3
            elif tag in (5, 6):
                consts[i] = (tag, struct.unpack_from('>Q', d, o)[0]); o += 8; i += 1
            elif tag in (3, 4):
                consts[i] = (tag, d[o:o+4]); o += 4
            elif tag in (17, 18):
                consts[i] = (tag, struct.unpack_from('>HH', d, o)); o += 4
            elif tag in (9, 10, 11, 12, 17, 18):
                consts[i] = (tag, struct.unpack_from('>HH', d, o)); o += 4
            else:
                raise ValueError('bad tag %d @%d' % (tag, o))
        except ValueError:
            raise
        except Exception as ex:
            raise ValueError('cp %d truncated @%d: %s' % (i, o, ex))
        i += 1
    acc, this_i, super_i = struct.unpack_from('>HHH', d, o); o += 6
    def utf(ci):
        e = consts[ci]
        if e is None: return ''
        if e[0] == 1: return e[1]
        if e[0] in (7, 8, 19, 20):
            j = e[1]
            if 1 <= j < n and consts[j] and consts[j][0] == 1: return consts[j][1]
        return ''
    def clsname(ci):
        e = consts[ci]
        if not e or e[0] != 7: return ''
        return utf(e[1])
    this_c = clsname(this_i); super_c = clsname(super_i)
    ifi_n = struct.unpack_from('>H', d, o)[0]; o += 2
    ifaces = [clsname(struct.unpack_from('>H', d, o + 2*k)[0]) for k in range(ifi_n)]
    o += 2 * ifi_n
    fields = []; methods = []
    for kind, arr in ((0, fields), (1, methods)):
        cnt = struct.unpack_from('>H', d, o)[0]; o += 2
        for _ in range(cnt):
            f_acc, f_name, f_desc = struct.unpack_from('>HHH', d, o); o += 6
            attr_n = struct.unpack_from('>H', d, o)[0]; o += 2
            for _a in range(attr_n):
                an_i = struct.unpack_from('>H', d, o)[0]
                al = struct.unpack_from('>I', d, o + 2)[0]
                o += 6 + al
                if an_i == 0 or al > len(d):
                    raise ValueError('attr truncated')
            arr.append((utf(f_name), utf(f_desc), f_acc))
    # class refs + strings from whole CP
    classes = set(); strings = []
    for e in consts:
        if not e: continue
        if e[0] == 7: classes.add(utf(e[1]))
        elif e[0] == 8: strings.append(utf(e[1]) if isinstance(e[1], int) else '')
    return dict(this=this_c, super=super_c, ifaces=ifaces, fields=fields,
                methods=methods, classes=sorted(classes), strings=strings,
                size=len(d))

def main():
    m = json.load(open(os.path.join(ROOT, 'tools', 'disk_name_map.json'), encoding='utf-8'))
    db = {}
    errors = []
    files = sorted(os.listdir(DUMP))
    for idx, fn in enumerate(files):
        if not fn.endswith('.class'): continue
        try:
            db[fn] = parse_class(os.path.join(DUMP, fn))
        except Exception as ex:
            errors.append((fn, str(ex)))
        if idx % 1000 == 0:
            print('%d/%d' % (idx, len(files)), flush=True)
    print('parsed', len(db), 'errors', len(errors))
    for e in errors[:20]: print('ERR', e)
    json.dump(db, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False)
    print('saved', OUT, os.path.getsize(OUT))

if __name__ == '__main__':
    main()
