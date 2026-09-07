#!/usr/bin/env python3
"""Анализ env-таблиц: live (.data) vs pristine (.rdata) в дампе 26116.

Классифицирует каждую запись по модулю-цели и секции, ищет перестановки,
не-указатели и дубли. Не изменяет никакие файлы проекта, только читает.
"""
import json
import sys
from collections import Counter, defaultdict

sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme\tools")
from jc import qwords, live_table, pristine_table, meta, sections, img, read_va

BASE = meta('new')['base']
LIVE_RVA = meta('new')['live_rva']
PRISTINE_RVA = meta('new')['pristine_rva']
SEC = sections('new')

MODULES = []  # (base, size, name)
MD = None

def sec_of(rva):
    for s in SEC:
        if s['vaddr'] <= rva < s['vaddr'] + s['vsize']:
            return s['name']
    return '?'

def classify(ptr):
    """Куда указывает запись таблицы: (module, section) или 'heap'/'low'."""
    if ptr == 0:
        return ('NULL', '-')
    if ptr < 0x10000:
        return ('LOW', hex(ptr))
    if not (0x100000000 <= ptr < 0x7fffffffffff):
        return ('INVALID', hex(ptr))
    for base, msize, name in MODULES:
        if base <= ptr < base + msize:
            rva = ptr - base
            short = name.replace('\\', '/').split('/')[-1]
            return (short, sec_of(rva))
    # не в модуле: heap или unknown allocation
    return ('HEAP/UNMAPPED', '-')

def main():
    global MODULES, MD
    from mdump import Minidump
    MD = Minidump(r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp")
    MODULES = MD.modules
    print("modules in dump: %d" % len(MODULES))
    for base, msize, name in MODULES:
        short = name.replace('\\', '/').split('/')[-1]
        if short.lower() in ('jvm.dll', 'astraea.dll', 'rustme.exe', 'java.dll',
                             'verify.dll', 'instrument.dll', 'nio.dll', 'zip.dll',
                             'awt.dll', 'net.dll', 'jsound.dll', 'fontmanager.dll',
                             'javaws.dll', 'deploy.dll', 'jp2iexp.dll'):
            print("  %#x +%#x %s" % (base, msize, short))

    lt = live_table('new', 240)
    pt = pristine_table('new', 240)

    # --- 1. Точная статистика совпадений ---
    same = sum(1 for i in range(240) if lt[i] == pt[i])
    print("\nlive==pristine: %d/240" % same)

    # live указывает на pristine-таблицу?
    lt_pts = sorted(set(lt))
    pt_pts = sorted(set(pt))
    print("unique live targets: %d, unique pristine targets: %d" % (len(lt_pts), len(pt_pts)))

    # --- 2. Является ли live перестановкой pristine (multiset равенство) ---
    print("live multiset == pristine multiset:", Counter(lt) == Counter(pt))

    # --- 3. Классификация целей ---
    print("\n=== target classification (module, section) of LIVE table ===")
    cls_live = Counter(classify(p) for p in lt)
    for k, v in sorted(cls_live.items(), key=lambda x: -x[1]):
        print("  %-28s %s -> %d" % (k[0], k[1], v))
    print("\n=== target classification of PRISTINE table ===")
    cls_pr = Counter(classify(p) for p in pt)
    for k, v in sorted(cls_pr.items(), key=lambda x: -x[1]):
        print("  %-28s %s -> %d" % (k[0], k[1], v))

    # --- 4. Ранние NULL и хвост ---
    nulls_live = [i for i, p in enumerate(lt) if p == 0]
    print("\nNULL slots live: %s" % nulls_live)
    nulls_pr = [i for i, p in enumerate(pt) if p == 0]
    print("NULL slots pristine: %s" % nulls_pr)

    # --- 5. Что происходит в диапазоне killer-слотов ---
    print("\n=== killer slots detail ===")
    for s in [7, 50, 55, 69, 97, 213, 229]:
        cl = classify(lt[s]); cp = classify(pt[s])
        print("  slot %3d live=%#x (%s %s) pristine=%#x (%s %s)" % (
            s, lt[s], cl[0], cl[1], pt[s], cp[0], cp[1]))

    # --- 6. Верифицированные рабочие слоты ---
    print("\n=== verified-good slots detail ===")
    good = {230: 'FindClass', 149: 'GetObjectClass', 26: 'NewStringUTF',
            146: 'GetStringUTFChars', 137: 'GetJavaVM', 216: 'ExceptionOccurred',
            46: 'ExceptionClear', 123: 'PushLocalFrame', 111: 'PopLocalFrame',
            233: 'GetMethodID', 135: 'GetStaticMethodID', 52: 'CallIntMethodA',
            195: 'CallObjectMethodA', 200: 'CallBooleanMethodA',
            167: 'CallStaticObjectMethodA', 138: 'CallVoidMethodA', 194: 'CallFloatMethodA'}
    for s in sorted(good):
        cl = classify(lt[s]); cp = classify(pt[s])
        print("  slot %3d (%-22s) live=%#x (%s %s) pristine=%#x (%s %s)" % (
            s, good[s], lt[s], cl[0], cl[1], pt[s], cp[0], cp[1]))

if __name__ == '__main__':
    main()
