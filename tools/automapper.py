#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
automapper.py — статический ремаппер обфусцированных сборок игры.

Задача: по двум дампам классов (старый + новый билд) построить
  classmap.json   {old_class: new_class}
  membermap.json  [{k:[oldC, kind, oldN], v:{newC, newN, via, desc}}]
  REVIEW.json / STOP.json / SKIP.json  — очереди ручного разбора
и (опционально, --apply) single-pass применить пары к исходникам агента.

Проверенные техники (якоря), закодированные как эвристики:
  H-setpos   — (DDD)V-метод, дёргающий 3 (D)V-сеттера по порядку  => X,Y,Z
  H-copy     — безаргументный метод с парами read->write          => prev-трипл
  H-movegrp  — группы (read pos -> write) в большом move          => pos/prev/last
  H-twins    — init/close близнецы через зеркало MC/GS            => нуждается в --roles
  H-csp      — сигнатуры вызывающих для неоднозначных дескрипторов
Всё, что не прошло validation-gate, в карту НЕ попадает — только в REVIEW/STOP.

Использование:
  python tools/automapper.py --old-dump dump/classes/minecraft/rustme \
      --new-dump newdump/minecraft/rustme --out out/
  Быстрый прогон одной пары (smoke, только внутриклассовые эвристики):
  python tools/automapper.py --pair IIlIIliIiI:liiIiiIIiI --pairs-only --out out/
  Применение к исходникам (после ручной проверки карт!):
  python tools/automapper.py --apply --maps out/ --src jni/agent/src

Зависимостей нет (чистый stdlib). Скрипты разведки в Temp не используются
и не изменяются — парсер классфайлов здесь собственный, dependency-free.
"""

import argparse
import json
import os
import re
import struct
import sys
from collections import Counter, defaultdict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEF_OLD = os.path.join(REPO, 'dump', 'classes', 'minecraft', 'rustme')
DEF_NEW = os.path.join(REPO, 'newdump', 'minecraft', 'rustme')

# --------------------------------------------------------------------------
# 0. Парсер классфайлов: ordered accs (field + invoke), поля, строки, codelen
# --------------------------------------------------------------------------

FIX = {0x10: 1, 0x11: 2, 0x12: 1, 0x13: 2, 0x14: 2, 0x15: 1, 0x16: 1, 0x17: 1,
       0x18: 1, 0x19: 1, 0x36: 1, 0x37: 1, 0x38: 1, 0x39: 1, 0x3A: 1, 0xA9: 1,
       0x84: 2, 0x99: 2, 0x9A: 2, 0x9B: 2, 0x9C: 2, 0x9D: 2, 0x9E: 2, 0x9F: 2,
       0xA0: 2, 0xA1: 2, 0xA2: 2, 0xA3: 2, 0xA4: 2, 0xA5: 2, 0xA6: 2, 0xA7: 2,
       0xA8: 2, 0xC6: 2, 0xC7: 2, 0xC8: 4, 0xC9: 4, 0xB2: 2, 0xB3: 2, 0xB4: 2,
       0xB5: 2, 0xB6: 2, 0xB7: 2, 0xB8: 2, 0xB9: 4, 0xBA: 4, 0xBB: 2, 0xBC: 1,
       0xBD: 2, 0xC0: 2, 0xC1: 2, 0xC5: 3}

FIELD_OPS = (0xB2, 0xB3, 0xB4, 0xB5)
INVOKE_OPS = (0xB6, 0xB7, 0xB8, 0xB9, 0xBA)

# Сгенерированные файлы агента (FontData ~1МБ Base64): наивные regex/замены по
# ним ломаются и портят пейлоад. Всегда исключать из сканов и apply.
GENERATED_JAVA = ('FontData.java', 'AssetData.java', 'IconData.java')

# Только такие имена членов трогает apply (обфусцированные I/l-имена).
# Ванильные/JDK имена (getName, values, isEmpty, ...) — СТАБИЛЬНЫ между
# билдами, их глобальная замена портит чужой код. Сравнение строгое,
# case-sensitive (Python ==; PowerShell-сканы здесь запрещены — см. ZNANIA).
import re as _re
OBF_MEMBER_RE = _re.compile(r'^[Il]+$')


def normalize_desc(d):
    """Нормализованный дескриптор: L-ссылки -> L#; (имена классов между
    билдами перетасовываются обфускатором, сырые дескрипторы дают 0.3-0.8
    на истинных парах вместо 0.95-1.0 — замер 09-13: entity 0.520->0.975).
    Массивы сохраняют глубину ([L#; отличается от L#;)."""
    return _re.sub(r'L[^;]+;', 'L#;', d)


def _parse_cp(d, o):
    """Возвращает (tag, val, n, o_after_cp). Бросает ValueError на битом CP."""
    n = struct.unpack_from('>H', d, o)[0]
    o += 2
    tag = [0] * n
    val = [None] * n
    i = 1
    while i < n:
        t = d[o]
        o += 1
        tag[i] = t
        if t == 1:
            ln = struct.unpack_from('>H', d, o)[0]
            o += 2
            try:
                val[i] = d[o:o + ln].decode('utf-8', 'replace')
            except Exception:
                val[i] = ''
            o += ln
        elif t in (7, 8, 16, 19, 20):
            val[i] = struct.unpack_from('>H', d, o)[0]
            o += 2
        elif t == 15:
            val[i] = (d[o], struct.unpack_from('>H', d, o + 1)[0])
            o += 3
        elif t in (5, 6):
            val[i] = struct.unpack_from('>Q', d, o)[0]
            o += 8
            i += 1
        elif t in (3, 4):
            val[i] = d[o:o + 4]
            o += 4
        elif t in (9, 10, 11, 12, 17, 18):
            val[i] = struct.unpack_from('>HH', d, o)
            o += 4
        else:
            raise ValueError('bad cp tag %d' % t)
        i += 1
    return tag, val, n, o


def parse_class(path):
    """Полный разбор .class.

    Возвращает dict(name, super, ifaces, fields, methods, strings) где
      fields:  [{name, desc, static}]
      methods: {(name, desc): {accs: [(op, owner, name, desc)...] (в порядке
                кода), codelen, scanned, acc (флаги доступа метода)}}
      ifaces:  [имена интерфейсов] (для impl-поиска, напр. ISound)
      strings: [utf8-константы] (для string-якорей)
    accs дополнительно содержат ldc-свидетельства: ('ldc', '', value, '')
    для int/float/double/String-констант (напр. 0.2-мультипликаторы) и
    ('str', '', s, '') для строк. Полевые/вызовные кортежи неизменны.
    Не бросает: битые файлы помечаются broken=True.
    """
    try:
        d = open(path, 'rb').read()
    except Exception as e:
        return {'broken': True, 'error': str(e)}
    try:
        tag, val, n, o = _parse_cp(d, 8)
    except Exception as e:
        return {'broken': True, 'error': 'cp: %s' % e}

    def utf(ci):
        if 1 <= ci < n and tag[ci] == 1:
            return val[ci]
        if 1 <= ci < n and tag[ci] in (7, 8, 19, 20):
            j = val[ci]
            if 1 <= j < n and tag[j] == 1:
                return val[j]
        return ''

    def cls(ci):
        return utf(val[ci]) if 1 <= ci < n and tag[ci] == 7 else ''

    try:
        acc, ti, si = struct.unpack_from('>HHH', d, o)
        o += 6
        this_c = cls(ti)
        sup_c = cls(si)
        ifi = struct.unpack_from('>H', d, o)[0]
        o += 2
        ifaces = []
        for _i in range(ifi):
            ifaces.append(cls(struct.unpack_from('>H', d, o)[0]))
            o += 2
        fcnt = struct.unpack_from('>H', d, o)[0]
        o += 2
        fields = []
        for _ in range(fcnt):
            fa, ni, di, an = struct.unpack_from('>HHHH', d, o)
            o += 8
            fields.append({'name': utf(ni), 'desc': utf(di),
                           'static': bool(fa & 0x0008)})
            for _a in range(an):
                al = struct.unpack_from('>I', d, o + 2)[0]
                o += 6 + al
        methods = {}
        mcnt = struct.unpack_from('>H', d, o)[0]
        o += 2
        for _ in range(mcnt):
            ma, mn, md = struct.unpack_from('>HHH', d, o)
            o += 6
            mname, mdesc = utf(mn), utf(md)
            an = struct.unpack_from('>H', d, o)[0]
            o += 2
            accs = []
            codelen = 0
            scanned = True
            for _a in range(an):
                ai = struct.unpack_from('>H', d, o)[0]
                al = struct.unpack_from('>I', d, o + 2)[0]
                if utf(ai) == 'Code':
                    try:
                        p = o + 6 + 2 + 2
                        cl = struct.unpack_from('>I', d, p)[0]
                        p += 4
                        codelen = cl
                        code = d[p:p + cl]
                        q = 0
                        while q < cl:
                            op = code[q]
                            if 0x02 <= op <= 0x08:
                                # iconst_m1..iconst_5 — для якорных констант (320/240 и др.)
                                accs.append(('const', '', op - 0x03, ''))
                            elif op == 0x10:
                                accs.append(('const', '',
                                             struct.unpack('b', code[q + 1:q + 2])[0], ''))
                            elif op == 0x11:
                                accs.append(('const', '',
                                             struct.unpack('>h', code[q + 1:q + 3])[0], ''))
                            elif op in (0x12, 0x13, 0x14):
                                # ldc-свидетельства (0.2-мультипликаторы, строки).
                                # a[0]=='ldc'/'str' — НЕ 'const', якоря 320/240 их не видят.
                                try:
                                    ci = (code[q + 1] if op == 0x12
                                          else struct.unpack_from('>H', code, q + 1)[0])
                                    if 1 <= ci < n:
                                        t = tag[ci]
                                        if t == 8:
                                            accs.append(('str', '', utf(val[ci]), ''))
                                        elif t == 3:
                                            accs.append(('ldc', '',
                                                         struct.unpack('>i', val[ci])[0], 'I'))
                                        elif t == 4:
                                            accs.append(('ldc', '',
                                                         struct.unpack('>f', val[ci])[0], 'F'))
                                        elif t == 6 and op == 0x14:
                                            # long/double делят тег 6: пишем сырые
                                            # биты (kind 'Q'), интерпретация —
                                            # за свидетелем-эвристикой.
                                            accs.append(('ldc', '', val[ci], 'Q'))
                                except Exception:
                                    pass
                            if op == 0xC4:
                                q += 6 if code[q + 1] == 0x84 else 4
                                continue
                            if op == 0xAA:
                                r = q + 1
                                r += (4 - (r % 4)) % 4
                                lo = struct.unpack_from('>i', code[r + 4:r + 8])[0]
                                hi = struct.unpack_from('>i', code[r + 8:r + 12])[0]
                                q = r + 12 + (hi - lo + 1) * 4
                                continue
                            if op == 0xAB:
                                r = q + 1
                                r += (4 - (r % 4)) % 4
                                np = struct.unpack_from('>i', code[r + 4:r + 8])[0]
                                q = r + 8 + np * 8
                                continue
                            if op in FIELD_OPS:
                                ci = struct.unpack_from('>H', code, q + 1)[0]
                                if 1 <= ci < n and tag[ci] == 9:
                                    c2, nt = val[ci]
                                    ow = cls(c2)
                                    nm2, ds2 = '', ''
                                    if 1 <= nt < n and tag[nt] == 12:
                                        ni2, di2 = val[nt]
                                        nm2, ds2 = utf(ni2), utf(di2)
                                    accs.append((op, ow, nm2, ds2))
                            elif op in (0xB6, 0xB7, 0xB8, 0xB9):
                                ci = struct.unpack_from('>H', code, q + 1)[0]
                                want = 11 if op == 0xB9 else 10
                                if 1 <= ci < n and tag[ci] == want:
                                    c2, nt = val[ci]
                                    ow = cls(c2)
                                    nm2, ds2 = '', ''
                                    if 1 <= nt < n and tag[nt] == 12:
                                        ni2, di2 = val[nt]
                                        nm2, ds2 = utf(ni2), utf(di2)
                                    accs.append((op, ow, nm2, ds2))
                            elif op == 0xBA:
                                ci = struct.unpack_from('>H', code, q + 1)[0]
                                if 1 <= ci < n and tag[ci] == 18:
                                    _, nt = val[ci]
                                    nm2, ds2 = '', ''
                                    if 1 <= nt < n and tag[nt] == 12:
                                        ni2, di2 = val[nt]
                                        nm2, ds2 = utf(ni2), utf(di2)
                                    accs.append((op, '', nm2, ds2))
                            q += 1 + FIX.get(op, 0)
                    except Exception:
                        scanned = False
                o += 6 + al
            methods[(mname, mdesc)] = {'accs': accs, 'codelen': codelen,
                                       'scanned': scanned, 'acc': ma}
        strings = [val[i] for i in range(1, n) if tag[i] == 1 and val[i]]
        return {'broken': False, 'name': this_c, 'super': sup_c,
                'ifaces': ifaces,
                'fields': fields, 'methods': methods, 'strings': strings}
    except Exception as e:
        return {'broken': True, 'error': 'body: %s' % e}


# --------------------------------------------------------------------------
# 1. Поверхность дампа
# --------------------------------------------------------------------------

def build_surface(dump_dir, pairs_only=None, log=print):
    """Сканирует каталог .class. Возвращает {realname: parsed}.

    pairs_only — множество real-имен (old или new сторона): парсить только
    файлы, чей this_class в этом множестве (быстрый smoke-режим). Реализовано
    двухпроходно: сначала дешёвый this_class по каждому файлу.
    """
    files = []
    for dp, dn, fns in os.walk(dump_dir):
        for f in fns:
            if f.endswith('.class'):
                files.append(os.path.join(dp, f))
    surf = {}
    broken = 0
    if pairs_only is not None:
        wanted = set(pairs_only)
        chosen = []
        for fp in files:
            try:
                d = open(fp, 'rb').read()
                tag, val, n, o = _parse_cp(d, 8)
                acc, ti, si = struct.unpack_from('>HHH', d, o)
                nm = ''
                if 1 <= ti < n and tag[ti] == 7:
                    j = val[ti]
                    if 1 <= j < n and tag[j] == 1:
                        nm = val[j]
                if nm in wanted:
                    chosen.append(fp)
            except Exception:
                continue
        files = chosen
    for i, fp in enumerate(files):
        r = parse_class(fp)
        if r.get('broken'):
            broken += 1
            continue
        if r['name'] in surf:
            # дубликат real-имени (case-коллизии уже сняты (N) — не должно быть)
            continue
        surf[r['name']] = r
        if (i + 1) % 2000 == 0:
            log('  surface: %d/%d' % (i + 1, len(files)))
    log('  surface: %d классов, битых: %d' % (len(surf), broken))
    return surf


# --------------------------------------------------------------------------
# 2. Матчинг классов
# --------------------------------------------------------------------------

def struct_fingerprint(info):
    # Дескрипторы НОРМАЛИЗОВАНЫ (L-ссылки -> L#;): сырые имена классов
    # перетасовываются между билдами и топят истинные пары (0.52 вместо
    # 0.975 на entity). Сырые дескрипторы для матчинга членов НЕ
    # используются как равенство (там только точные примитивные дески
    # либо REVIEW).
    mdescs = Counter(normalize_desc(md) for (_, md) in info['methods'])
    fdescs = Counter(normalize_desc(f['desc']) + ('!S' if f['static'] else '')
                     for f in info['fields'])
    return mdescs, fdescs


def jaccard_multiset(a, b):
    keys = set(a) | set(b)
    inter = sum(min(a[k], b[k]) for k in keys)
    union = sum(max(a[k], b[k]) for k in keys)
    return (inter / union) if union else 1.0


def match_classes(surf_old, surf_new, seed=None, thresh=0.90):
    """Возвращает (classmap, review_classes).

    seed — {old: new} доверенные пары. Остальное — структурный скоринг
    (мультимножества дескрипторов методов+полей). Ниже thresh — в review.
    """
    seed = seed or {}
    cmap = dict(seed)
    used_new = set(seed.values())
    review = []
    # индекс new по корзинам численности (НЕ точное равенство: между билдами
    # методы добавляются/удаляются; точные корзины давали 0/86 на паре дампов).
    def bucket(n):
        return n // 5
    by_count = defaultdict(list)
    for nn, ni in surf_new.items():
        if nn in used_new:
            continue
        by_count[bucket(len(ni['methods']))].append(nn)
    for on, oi in surf_old.items():
        if on in cmap:
            continue
        om, of = struct_fingerprint(oi)
        best, best_s = None, -1.0
        bc = bucket(len(oi['methods']))
        cands = by_count.get(bc, []) + by_count.get(bc - 1, []) + by_count.get(bc + 1, [])
        for nn in cands:
            ni = surf_new[nn]
            nm, nf = struct_fingerprint(ni)
            s = 0.65 * jaccard_multiset(om, nm) + 0.35 * jaccard_multiset(of, nf)
            if s > best_s:
                best, best_s = nn, s
        if best is not None and best_s >= thresh:
            cmap[on] = best
            used_new.add(best)
        else:
            review.append({'old': on, 'best': best, 'score': round(best_s, 3),
                           'reason': 'структурный скор ниже порога'})
    return cmap, review


# --------------------------------------------------------------------------
# 3. Матчинг членов внутри пары классов
# --------------------------------------------------------------------------

def _trivial_getters(info, owner):
    """{field: getter} для чистых ()D-геттеров (ровно один getfield-D)."""
    out = {}
    for (mn, md), mi in info['methods'].items():
        if md == '()D' and len(mi['accs']) == 1:
            op, ow, nm, ds = mi['accs'][0]
            if op == 0xB4 and ds == 'D' and ow == owner:
                out[nm] = mn
    return out


def _d_setters(info, owner):
    """{field: setter} для (D)V с ровно одним D-putfield (трансформ допустим)."""
    out = {}
    for (mn, md), mi in info['methods'].items():
        if md == '(D)V':
            puts = [nm for op, ow, nm, ds in mi['accs']
                    if op in (0xB5, 0xB3) and ds == 'D' and ow == owner]
            if len(puts) == 1:
                out[puts[0]] = mn
    return out


def match_members_unique(oi, ni, on, nn):
    """Каскад unique -> type-unique. Возвращает (pairs, ambiguous).

    pair = (kind, oldN, newN, desc, via).
    """
    pairs, ambiguous = [], []
    for kind in ('method', 'field'):
        if kind == 'method':
            og = defaultdict(list)
            ng = defaultdict(list)
            for (mn, md) in oi['methods']:
                og[md].append(mn)
            for (mn, md) in ni['methods']:
                ng[md].append(mn)
        else:
            og = defaultdict(list)
            ng = defaultdict(list)
            for f in oi['fields']:
                og[f['desc']].append(f['name'])
            for f in ni['fields']:
                ng[f['desc']].append(f['name'])
        for desc, olist in og.items():
            nlist = ng.get(desc, [])
            if len(olist) == 1 and len(nlist) == 1:
                pairs.append((kind, olist[0], nlist[0], desc, 'unique'))
            elif len(olist) > 1 or len(nlist) > 1:
                ambiguous.append((kind, desc, olist, nlist))
    return pairs, ambiguous


def caller_signature(dump_files, target_name, target_desc, owner_hint=None):
    """Множество (файл, метод) вызывающих name+desc. owner_hint сужает."""
    sig = set()
    for fp in dump_files:
        try:
            r = parse_class(fp)
        except Exception:
            continue
        if r.get('broken'):
            continue
        base = os.path.basename(fp)
        for (mn, md), mi in r['methods'].items():
            for op, ow, nm, ds in mi['accs']:
                if op in (0xB6, 0xB7, 0xB8, 0xB9) and nm == target_name and ds == target_desc:
                    if owner_hint is None or ow == owner_hint:
                        sig.add((base, mn))
    return sig


def match_members_csp(oi, ni, on, nn, ambiguous, cmap, dump_new_files):
    """CSP по сигнатурам вызывающих для неоднозначных дескрипторов (new side).

    Для каждого new-кандидата считаем вызывающих, маппим их классы через
    seed-cmap в old-пространство и сравниваем с вызывающими old-кандидатов.
    Возвращает (pairs, review).
    """
    pairs, review = [], []
    # обратный индекс seed: new класс -> old класс
    rev = {v: k for k, v in cmap.items()}
    for kind, desc, olist, nlist in ambiguous:
        if kind != 'method':
            review.append({'class': [on, nn], 'kind': kind, 'desc': desc,
                           'reason': 'неоднозначные поля — только ручной разбор'})
            continue
        # вызывающие old-кандидатов не нужны dump-wide: достаточно new-стороны
        # + сравнение между new-кандидатами невозможно без old-сигнатур.
        # Упрощение: считаем сигнатуры new-кандидатов; если ровно у одного
        # есть вызывающие, а у остальных 0 — победитель очевиден.
        scored = []
        for cand in nlist:
            sig = caller_signature(dump_new_files, cand, desc, owner_hint=nn)
            mapped = set()
            for base, mn in sig:
                mapped.add((base, mn))
            scored.append((cand, mapped))
        with_calls = [(c, s) for c, s in scored if s]
        if len(with_calls) == 1 and len(olist) == 1:
            pairs.append(('method', olist[0], with_calls[0][0], desc, 'anchor-csp'))
        else:
            review.append({'class': [on, nn], 'kind': kind, 'desc': desc,
                           'old': olist, 'new': sorted(nlist),
                           'reason': 'CSP не различил (вызывающих: %s)' %
                                     {c: len(s) for c, s in scored}})
    return pairs, review


# --------------------------------------------------------------------------
# 4. Ролевые эвристики (доказанные приёмы)
# --------------------------------------------------------------------------

def h_setpos(info, owner):
    """H-setpos: (DDD)V, дёргающий >=3 (D)V-сеттеров по порядку => X,Y,Z.

    Возвращает ([(field,setter)] в порядке X,Y,Z, evidence) или (None, reason).
    """
    setters = _d_setters(info, owner)
    sset = set(setters.values())
    cands = []
    for (mn, md), mi in info['methods'].items():
        if '(DDD' not in md:
            continue
        seq = [nm for op, ow, nm, ds in mi['accs']
               if op in (0xB6, 0xB7) and ow == owner and nm in sset and ds == '(D)V']
        if len(seq) >= 3:
            cands.append((mn, md, seq[:3]))
    if not cands:
        return None, 'нет (DDD)V с 3+ сеттерами'
    # консенсус: все кандидаты должны давать одну тройку в том же порядке
    triples = set(tuple(c[2]) for c in cands)
    if len(triples) != 1:
        return None, 'кандидаты расходятся: %s' % sorted(triples)
    seq = cands[0][2]
    fld_of = {v: k for k, v in setters.items()}
    triple = [(fld_of[s], s) for s in seq]
    ev = 'setpos %s%s: %s' % (cands[0][0], cands[0][1], seq)
    return triple, ev


def h_copy(info, owner, pos_getters):
    """H-copy (только evidence, НЕ клеймит): окна read->write x3 поверх pos.

    Окно не различает prev и last (у teleport тоже есть read->write x3),
    поэтому используется лишь как corroboration. Клеймит H-ordered.
    Возвращает список найденных окон для журнала.
    """
    setters = _d_setters(info, owner)
    found = []
    for (mn, md), mi in info['methods'].items():
        if mn in ('<init>', '<clinit>'):
            continue
        seq = [(nm, ds) for op, ow, nm, ds in mi['accs']
               if op in (0xB6, 0xB7) and ow == owner
               and ((ds == '()D' and nm in pos_getters) or
                    (ds == '(D)V' and nm in setters.values()))]
        for s in range(len(seq) - 5):
            win = seq[s:s + 6]
            if [d for _, d in win] != ['()D', '(D)V'] * 3:
                continue
            reads = [win[i][0] for i in (0, 2, 4)]
            writes = [win[i][0] for i in (1, 3, 5)]
            if set(reads) == set(pos_getters) and len(set(writes)) == 3:
                found.append('%s%s@%d writes=%s' % (mn, md, s, writes))
    return found


def h_ordered_simulation(info, owner, pos_triple):
    """H-ordered: тройки сеттеров в SIMULATION-методах (деск начинается с DDD).

    В move/teleport/setpos порядок групп: [P,P,P, T1,T1,T1, (T2,T2,T2)].
    copyFrom (Lentity;...) для порядка НЕ используется (там P,L,V).
    Консенсус по всем simulation-методам: T1=prev, T2=last (если есть).
    Возвращает ((prev_triple|None, pev), (last_triple|None, lev)) где
    triple = [(field, setter)] X,Y,Z.
    """
    setters = _d_setters(info, owner)
    pos_s = [s for _, s in pos_triple]
    t1votes, t2votes = Counter(), Counter()
    where = {}
    for (mn, md), mi in info['methods'].items():
        if not md.startswith('(DDD'):
            continue
        w = [nm for op, ow, nm, ds in mi['accs']
             if op in (0xB6, 0xB7) and ow == owner and ds == '(D)V'
             and nm in setters.values()]
        if len(w) < 6 or w[0:3] != pos_s:
            continue
        # чанки по 3 после pos-группы; каждый чанк обязан быть 3 distinct
        rest = w[3:]
        groups = []
        for i in range(0, len(rest) - 2, 3):
            g = tuple(rest[i:i + 3])
            if len(set(g)) == 3:
                groups.append(g)
        if not groups:
            continue
        t1votes[groups[0]] += 1
        where.setdefault(groups[0], []).append('%s%s' % (mn, md))
        if len(groups) >= 2:
            t2votes[groups[1]] += 1
            where.setdefault(groups[1], []).append('%s%s' % (mn, md))
    fld_of = {v: k for k, v in setters.items()}
    prev = last = None
    pev = lev = 'нет simulation-консенсуса'
    if t1votes:
        (g1, c1), = t1votes.most_common(1)
        total = sum(t1votes.values())
        if c1 == total and not (set(g1) & set(pos_s)):
            prev = [(fld_of[s], s) for s in g1]
            pev = 'T1=%s в %s' % (list(g1), where[g1])
        else:
            pev = 'T1 рассогласован: %s' % dict(t1votes)
    if t2votes:
        (g2, c2), = t2votes.most_common(1)
        total = sum(t2votes.values())
        prev_s = set(s for _, s in prev) if prev else set()
        if c2 == total and not (set(g2) & (set(pos_s) | prev_s)):
            last = [(fld_of[s], s) for s in g2]
            lev = 'T2=%s в %s' % (list(g2), where[g2])
        else:
            lev = 'T2 рассогласован: %s' % dict(t2votes)
    return (prev, pev), (last, lev)


def h_scaledres(oinfo, ninfo, on, nn):
    """H-scaledres: SCALE/WIDTH/HEIGHT по форме ctor (кейс 509x).

    Признаки (проверены на паре liIIiIliiI/lililIliiI):
      - int-поля; в <init> одно поле пишется РОВНО 3 раза (init/loop/unicode),
        остальные int — по 2 (init/final) => поле 3x = scaleFactor;
      - в том же <init> getfield-I непосредственно перед const 320 => WIDTH,
        перед const 240 => HEIGHT (условия ванильного scale-цикла);
      - ()I-геттеры, читающие эти поля (ровно по одному ридеру).
    Возвращает ({'SCALE':(f,g),'WIDTH':(f,g),'HEIGHT':(f,g)} old/new-пар,
    evidence) или (None, reason). Ничего не угадывает: любой провал => None.
    """
    def analyze(info, owner):
        inits = [(mn, md, mi) for (mn, md), mi in info['methods'].items()
                 if mn == '<init>']
        if not inits:
            return None, 'нет <init>'
        writes = Counter()
        order = []
        for _, _, mi in inits:
            for op, ow, nm, ds in mi['accs']:
                if op in (0xB5, 0xB3) and ds == 'I' and ow == owner:
                    writes[nm] += 1
                    order.append(nm)
        three = [f for f, c in writes.items() if c == 3]
        two = [f for f, c in writes.items() if c == 2]
        if len(three) != 1 or len(two) < 2:
            return None, 'паттерн записей не 3x/2x: %s' % dict(writes)
        scale_f = three[0]
        # 320/240-привязка
        # 320/240-привязка: в акках цикл выглядит как
        #   getfield ДИВИДЕНД, getfield SCALE, const 1, const 320|240
        # (арифметика/ветвления в акках невидимы). last-getfield тут НЕ годится:
        # перед const стоит геттер делителя (scale), а не делимого!
        def const_dividend(accs, value):
            for i, a in enumerate(accs):
                if a[0] == 'const' and a[2] == value and i >= 3:
                    c1, g2, g1 = accs[i - 1], accs[i - 2], accs[i - 3]
                    if (c1[0] == 'const' and g2[0] in (0xB4, 0xB2) and g2[3] == 'I'
                            and g1[0] in (0xB4, 0xB2) and g1[3] == 'I'):
                        return g1[2]
            return None
        width_f = height_f = None
        for _, _, mi in inits:
            w = const_dividend(mi['accs'], 320)
            h = const_dividend(mi['accs'], 240)
            if w and width_f is None:
                width_f = w
            if h and height_f is None:
                height_f = h
        if not width_f or not height_f or width_f == height_f:
            return None, 'нет 320/240-привязки: w=%s h=%s' % (width_f, height_f)
        if width_f == scale_f or height_f == scale_f:
            return None, '320/240 указывают на scale-поле'
        # ридеры-геттеры ()I
        def readers(field):
            return [mn for (mn, md), mi in info['methods'].items() if md == '()I'
                    and any(op == 0xB4 and nm == field and ds == 'I' and ow == owner
                            for op, ow, nm, ds in mi['accs'])]
        gs, gw, gh = readers(scale_f), readers(width_f), readers(height_f)
        if len(gs) != 1 or len(gw) != 1 or len(gh) != 1:
            return None, 'ридеров не 1:1: s=%s w=%s h=%s' % (gs, gw, gh)
        return ({'SCALE': (scale_f, gs[0]), 'WIDTH': (width_f, gw[0]),
                 'HEIGHT': (height_f, gh[0])},
                'scale=%s w=%s(320) h=%s(240)' % (scale_f, width_f, height_f))

    o, oev = analyze(oinfo, on)
    n, nev = analyze(ninfo, nn)
    if o is None or n is None:
        return None, 'old: %s; new: %s' % (oev if o is None else o[1],
                                           nev if n is None else n[1])
    return (o, n), 'old[%s] new[%s]' % (oev, nev)


def h_twins(gs_old, gs_new, mc_old, mc_new, surf_old, surf_new,
            files_old, files_new):
    """H-twins: init/close близнецы через зеркало MC/GS.

    init = близнец, зовомый из GS-internal метода вида (L...;II)V
    (как старый IliIIlIlil / новый llIIllliIl).
    close = другой близнец, зовомый извне (MC-файл, НЕ чейнинг):
    старый iilliIliiI.IIlIiilliI(Lscreen) / новый iiillIliiI.lilIllliII().
    Возвращает ({'init': (oldN, newN), 'close': (oldN, newN)}, evidence).
    """
    def vols_of(surf, gs):
        gi = surf.get(gs)
        if gi is None:
            return None
        return [(mn, md) for (mn, md) in gi['methods']
                if md == '()V' and mn not in ('<init>', '<clinit>')]

    def internal_calls(surf, gs):
        out = []
        gi = surf.get(gs)
        if gi is None:
            return out
        for (mn, md), mi in gi['methods'].items():
            for op, ow, nm, ds in mi['accs']:
                if op in (0xB6, 0xB7) and ow == gs and ds == '()V':
                    out.append((mn, md, nm))
        return out

    def display_calls(surf, files, gs, twins):
        """Методы display: деск содержит (LGS..) + зовут близнецов (по порядку)."""
        tset = set(twins)
        out = []
        seen = set()

        def scan(r, base):
            for (mn, md), mi in r['methods'].items():
                if ('L' + gs + ';') not in md:
                    continue
                seq = [nm for op, ow, nm, ds in mi['accs']
                       if op in (0xB6, 0xB7) and ow == gs and ds == '()V' and nm in tset]
                if seq:
                    out.append((base, mn, md, seq))

        gi = surf.get(gs)
        if gi is not None:
            scan(gi, '<gs>')
        for fp in files:
            try:
                r = parse_class(fp)
            except Exception:
                continue
            if r.get('broken') or r.get('name') == gs:
                continue
            scan(r, os.path.basename(fp))
        return out

    ov, nv = vols_of(surf_old, gs_old), vols_of(surf_new, gs_new)
    if ov is None or nv is None:
        return None, 'нет GS-класса'
    omap = internal_calls(surf_old, gs_old)
    nmap = internal_calls(surf_new, gs_new)

    def pick_init(calls):
        for mn, md, nm in calls:
            if re.match(r'\(L[^;]+;II\)V$', md):
                return nm, '%s%s' % (mn, md)
        return None, None

    oi, oev = pick_init(omap)
    ni, nev = pick_init(nmap)
    if not oi or not ni:
        return None, 'нет GS-internal (L;II)V-зовов'
    # close = ПЕРВЫЙ близнец, зовомый display-методом (Lscreen-путь):
    # ванильный порядок — сначала close старого экрана, потом init нового.
    # (НЕ "любой внешний зов": runTick и др. зовут update-близнецов тоже.)
    odisp = display_calls(surf_old, files_old, gs_old, [m for m, _ in ov])
    ndisp = display_calls(surf_new, files_new, gs_new, [m for m, _ in nv])

    def pick_close_disp(disp):
        for base, mn, md, seq in disp:
            if seq:
                return seq[0], '%s%s' % (mn, md)
        return None, None

    oc, ocev = pick_close_disp(odisp)
    nc, ncev = pick_close_disp(ndisp)
    if not oc or not nc:
        return None, 'display с близнецами не найден'
    if oc == oi or nc == ni:
        return None, 'противоречие: первый близнец display == init (%s/%s)' % (oc, nc)
    ev = 'init %s->%s (via %s / %s); close %s->%s (via %s / %s)' % (
        oi, ni, oev, nev, oc, nc, ocev, ncev)
    return {'init': (oi, ni), 'close': (oc, nc)}, ev


# --------------------------------------------------------------------------
# 5. Сборка карт + validation gate (включая анти-коллапс)
# --------------------------------------------------------------------------

def short(n):
    return n.split('/')[-1]


ROLE_VIAS = ('anchor-setpos-order-', 'anchor-updatecopy-', 'anchor-renderlerp-',
               'anchor-scaledres-', 'anchor-twins-', 'anchor-csp', 'anchor-decl')


def member_exists(surf, newC, kind, newN, desc, depth=0):
    """Член с учётом наследования: ищет вверх по super-цепочке.

    Прямая проверка (newN, desc) в таблице класса ложно бракует унаследованные
    члены (напр. геттеры позиций, вызываемые через subclass-refs): javac их
    резолвит, а в method table сабкласса их нет. Идём по super пока класс есть
    в поверхности; оборванная цепочка = unknown => считаем существующим
    (не валим в MISSING без доказательств).
    """
    seen = set()
    cur = newC
    while cur and cur not in seen and depth < 25:
        seen.add(cur)
        ni = surf.get(cur)
        if ni is None:
            return True
        if kind == 'method':
            if (newN, desc) in ni['methods']:
                return True
        else:
            if any(f['name'] == newN and f['desc'] == desc for f in ni['fields']):
                return True
        cur = ni.get('super')
        depth += 1
    return False


def collapse_groups(pairs):
    """Группы схлопывания: один newN из разных oldN ВНУТРИ одного oldC.

    pairs: [(oldC, kind, oldN, newC, newN, desc, via)]. Возвращает список
    {'new': [newC, newN], 'olds': [[oldC, kind, oldN, desc, via]...]} где
    olds различаются и принадлежат одному oldC. Кросс-классовые совпадения
    (наследование) — НЕ флаг.
    """
    by_new = defaultdict(list)
    for p in pairs:
        oldC, kind, oldN, newC, newN, desc, via = p
        by_new[(newC, newN)].append(p)
    out = []
    for (newC, newN), lst in by_new.items():
        by_old_c = defaultdict(list)
        for p in lst:
            by_old_c[p[0]].append(p)
        for oldC, sub in by_old_c.items():
            distinct = set((k, o, d) for _, k, o, _, _, d, _ in sub)
            if len(distinct) > 1:
                out.append({'new': [short(newC), newN],
                            'olds': [[short(o), k, n, d, v]
                                     for o, k, n, _, _, d, v in sub]})
    return out


def build_maps(pairs, cmap, surf_new):
    """pairs -> (membermap, stop, collapse).

    Gate-1 (существование): new-класс и new-член с тем же деском обязаны быть
    в поверхности. Gate-2 (анти-коллапс): группа many->one внутри oldC —
    остаются только role-anchored пары, остальные уходят в collapse-review.
    """
    mm, stop = [], []
    col = collapse_groups(pairs)
    condemned = set()
    for g in col:
        olds = g['olds']
        keep = [o for o in olds if o[4].startswith(ROLE_VIAS)]
        drop = [o for o in olds if not o[4].startswith(ROLE_VIAS)]
        if keep and drop:
            for o in drop:
                condemned.add((o[0], o[1], o[2]))
        elif not keep:
            for o in olds:
                condemned.add((o[0], o[1], o[2]))
    for oldC, kind, oldN, newC, newN, desc, via in pairs:
        if (short(oldC), kind, oldN) in condemned:
            continue
        ni = surf_new.get(newC)
        if ni is None:
            stop.append({'pair': [oldC, oldN], 'reason': 'нет new-класса %s' % newC})
            continue
        # Наследование-aware проверка: прямые таблицы ложно бракуют
        # унаследованные члены (геттеры позиций через subclass-refs резолвит
        # javac, а в таблице сабкласса их нет). member_exists идёт по super.
        if not member_exists(surf_new, newC, kind, newN, desc):
            stop.append({'pair': [oldC, oldN],
                         'reason': 'нет %s %s%s в %s (с учётом super)' % (kind, newN, desc, newC)})
            continue
        mm.append({'k': [short(oldC), kind, oldN],
                   'v': {'newC': short(newC), 'newN': newN, 'via': via, 'desc': desc}})
    return mm, stop, col


# --------------------------------------------------------------------------
# 6. Применение к исходникам (single-pass, только с --apply)
# --------------------------------------------------------------------------

def apply_maps(src_dir, classmap, membermap, log=print):
    """Двухфазное применение карт к .java. Возвращает (total, per_file, stops).

    Фаза 1 (классы): короткие имена oldC->newC глобально (шорты классов
    уникальны в дампе). Фаза 2 (члены): ТОЛЬКО обфусцированные имена
    (^[Il]+$, ваниль/JDK вроде getName/values/isEmpty стабильна и её замена
    портит чужой код) и ТОЛЬКО в файлах, ссылающихся на владеющий oldC
    (import/loadClass/каст/instanceof — шорт класса словом в тексте).
    Один oldN из разных oldC в одном файле с разным newN = коллизия:
    НЕ заменяем, пишем в stops (тихий last-wins запрещён).
    Сгенерированные файлы (FontData/AssetData/IconData) исключены всегда.
    """
    cls_rep = {}
    for o, n in classmap.items():
        so, sn = short(o), short(n)
        if so != sn:
            cls_rep[so] = sn
    # член: oldN -> [(oldCshort, newN, desc, via)]
    mem_by_old = {}
    skipped_vanilla = 0
    for e in membermap:
        oldC, kind, oldN = e['k']
        newN = e['v']['newN']
        if oldN == newN:
            continue
        if not OBF_MEMBER_RE.match(oldN):
            skipped_vanilla += 1
            continue
        mem_by_old.setdefault(oldN, []).append(
            (short(oldC), newN, e['v'].get('desc', ''), e['v'].get('via', '')))

    def word_pat(s):
        return re.compile(r'(?<![\w$])' + re.escape(s) + r'(?![\w$])')

    total, per_file, stops = 0, {}, []
    if skipped_vanilla:
        log('apply: пропущено ванильных имён членов (стабильны): %d' % skipped_vanilla)
    for dp, dn, fns in os.walk(src_dir):
        for f in fns:
            if not f.endswith('.java') or f in GENERATED_JAVA:
                continue
            fp = os.path.join(dp, f)
            t = open(fp, encoding='utf-8').read()
            # какие oldC упоминаются (до классовых замен!)
            refs = set()
            for o in classmap:
                if word_pat(short(o)).search(t):
                    refs.add(short(o))
            n_file = 0
            if cls_rep:
                names = sorted(cls_rep, key=len, reverse=True)
                pat = re.compile(r'(?<![\w$])(' + '|'.join(re.escape(x) for x in names)
                                 + r')(?![\w$])')
                t, n = pat.subn(lambda m: cls_rep[m.group(1)], t)
                n_file += n
            # члены: только привязка к упомянутым oldC
            for oldN, cands in mem_by_old.items():
                rel = [c for c in cands if c[0] in refs]
                if not rel:
                    continue
                news = set(c[1] for c in rel)
                if len(news) != 1:
                    stops.append({'file': fp, 'member': oldN,
                                  'cands': [[c[0], c[1], c[3]] for c in rel],
                                  'reason': 'коллизия: один oldN из разных oldC в одном файле'})
                    continue
                t, n = word_pat(oldN).subn(sorted(news)[0], t)
                n_file += n
            if n_file:
                open(fp, 'w', encoding='utf-8', newline='').write(t)
                per_file[fp] = n_file
                total += n_file
    log('apply: %d замен в %d файлах, коллизий: %d' % (total, len(per_file), len(stops)))
    for s in stops[:20]:
        log('  STOP %s: %s <- %s' % (s['file'], s['member'], s['cands']))
    return total, per_file, stops


def run_selftest(old_dump, new_dump, log=print):
    """Регрессия на реальных дампах. Возвращает True если всё сошлось."""
    import time
    fails = []

    def check(cond, label):
        log(('PASS ' if cond else 'FAIL ') + label)
        if not cond:
            fails.append(label)

    # --- Entity 9 ролей ---
    t0 = time.time()
    oi = _parse_single(old_dump, 'rustme/IIlIIliIiI')
    ni = _parse_single(new_dump, 'rustme/liiIiiIIiI')
    if oi is None or ni is None:
        check(False, 'entity classes parse')
        return False
    r = run_pair(oi, ni, 'rustme/IIlIIliIiI', 'rustme/liiIiiIIiI', [], deep=False)
    EXP = {'IlIiillIII': 'iIliiliilI', 'liiiIllIII': 'illiiliilI', 'lIilillIII': 'liilIIiilI',
           'IlilillIII': 'lilIiIiilI', 'IliIlIlIII': 'IiliIIiilI', 'lIiiIllIII': 'IiiiiliilI',
           'IiilillIII': 'llliIIiilI', 'lliilIlIII': 'IlIliIiilI', 'lilllIlIII': 'lIiIiliilI'}
    got = {}
    for (oc, kind, o, nc, n, desc, via) in r['pairs']:
        if kind == 'method' and desc == '()D' and o in EXP:
            got[o] = n
    for o, n in EXP.items():
        check(got.get(o) == n, 'entity %s->%s (got %s)' % (o, n, got.get(o)))
    log('entity: %.1fs' % (time.time() - t0))

    # --- scaledres 3 ---
    t0 = time.time()
    so = _parse_single(old_dump, 'rustme/liIIiIliiI')
    sn = _parse_single(new_dump, 'rustme/lililIliiI')
    EXP2 = {'illlIlIliI': 'lIliIlliII', 'IIllIlIliI': 'IIliIlliII', 'lIllIlIliI': 'liliIlliII'}
    if so is None or sn is None:
        check(False, 'sr classes parse')
    else:
        sr, _ = h_scaledres(so, sn, 'rustme/liIIiIliiI', 'rustme/lililIliiI')
        if sr is None:
            check(False, 'scaledres found')
        else:
            om, nm = sr
            for role, want_o, want_n in (('SCALE', 'illlIlIliI', 'lIliIlliII'),
                                         ('WIDTH', 'IIllIlIliI', 'IIliIlliII'),
                                         ('HEIGHT', 'lIllIlIliI', 'liliIlliII')):
                check(om[role][1] == want_o and nm[role][1] == want_n,
                      'sr %s %s->%s' % (role, want_o, want_n))
    log('scaledres: %.1fs' % (time.time() - t0))

    # --- twins ---
    t0 = time.time()
    fo = []
    for dp, _, fns in os.walk(old_dump):
        for f in fns:
            if f.endswith('.class'):
                fo.append(os.path.join(dp, f))
    fnl = []
    for dp, _, fns in os.walk(new_dump):
        for f in fns:
            if f.endswith('.class'):
                fnl.append(os.path.join(dp, f))
    so2 = {'rustme/IlIlliliiI': _parse_single(old_dump, 'rustme/IlIlliliiI')}
    sn2 = {'rustme/IllIIIliiI': _parse_single(new_dump, 'rustme/IllIIIliiI')}
    tw, ev = h_twins('rustme/IlIlliliiI', 'rustme/IllIIIliiI',
                     'rustme/iilliIliiI', 'rustme/iiillIliiI',
                     so2, sn2, fo, fnl)
    log('twins: %s // %s' % (tw, ev))
    check(tw is not None and tw.get('init') == ('iIllIlIlil', 'ilIIllliIl'),
          'twins init iIllIlIlil->ilIIllliIl')
    check(tw is not None and tw.get('close') == ('iilillIlil', 'iIlIllliIl'),
          'twins close iilillIlil->iIlIllliIl')
    log('twins: %.1fs' % (time.time() - t0))
    log('SELFTEST %s (%d fails)' % ('OK' if not fails else 'FAIL', len(fails)))
    return not fails


def _parse_single(dump_dir, real):
    for dp, _, fns in os.walk(dump_dir):
        for f in fns:
            if not f.endswith('.class'):
                continue
            fp = os.path.join(dp, f)
            try:
                r = parse_class(fp)
            except Exception:
                continue
            if not r.get('broken') and r.get('name') == real:
                return r
    return None


# --------------------------------------------------------------------------
# 7. Драйвер (продолжение: ветки apply/audit/main)
# --------------------------------------------------------------------------

def run_pair(oi, ni, on, nn, files_new, deep=False):
    """Полный poultry для одной пары классов. Возвращает dict с парами/очередями."""
    res = {'pairs': [], 'review': [], 'evidence': []}
    claimed_old, claimed_new = set(), set()

    def claim(kind, o, n, desc, via):
        if o in claimed_old or n in claimed_new:
            return False
        claimed_old.add(o)
        claimed_new.add(n)
        res['pairs'].append((on, kind, o, nn, n, desc, via))
        return True

    # --- H-setpos / H-copy / H-movegrp (ролевые, высокий приоритет) ---
    for side, info, owner in (('old', oi, on), ('new', ni, nn)):
        setattr(run_pair, '_tmp_' + side, (info, owner))
    (oinfo, oowner) = run_pair._tmp_old
    (ninfo, nowner) = run_pair._tmp_new
    triples = {}
    for side, info, owner in (('old', oinfo, oowner), ('new', ninfo, nowner)):
        tr, ev = h_setpos(info, owner)
        triples[side] = tr
        res['evidence'].append('%s setpos: %s // %s' % (side, tr, ev))
    # спаривание троек old<->new по ролям X,Y,Z
    if triples.get('old') and triples.get('new'):
        og = _trivial_getters(oinfo, oowner)
        ng = _trivial_getters(ninfo, nowner)
        # pos: геттеры ищем среди ()D-ридеров полей (тривиальных ИЛИ с декодером)
        def readers(info, owner, field):
            out = []
            for (mn, md), mi in info['methods'].items():
                if md != '()D':
                    continue
                if any(op == 0xB4 and nm == field and ds == 'D' and ow == owner
                       for op, ow, nm, ds in mi['accs']):
                    out.append(mn)
            return out
        for role, idx in (('posX', 0), ('posY', 1), ('posZ', 2)):
            of, os_ = triples['old'][idx]
            nf, ns_ = triples['new'][idx]
            orr, nrr = readers(oinfo, oowner, of), readers(ninfo, nowner, nf)
            if len(orr) == 1 and len(nrr) == 1:
                claim('method', orr[0], nrr[0], '()D', 'anchor-setpos-order-' + role)
                claim('field', of, nf, 'D', 'anchor-setpos-order-' + role)
                claim('method', os_, ns_, '(D)V', 'anchor-setpos-order-' + role)
            else:
                res['review'].append({'pair': [on, nn], 'role': role,
                                      'old_readers': orr, 'new_readers': nrr,
                                      'reason': 'ридеров поля не 1:1'})
        # H-copy: только corroboration в журнал (окно не различает prev/last)
        for side, info, owner in (('old', oinfo, oowner), ('new', ninfo, nowner)):
            pget = set()
            for idx in range(3):
                fld = triples[side][idx][0]
                pget.update(readers(info, owner, fld))
            wins = h_copy(info, owner, pget)
            res['evidence'].append('%s copy-windows(%d): %s' % (side, len(wins), wins[:4]))
        # H-ordered: клеймит prev (T1) и last (T2) по консенсусу simulation
        for side, info, owner in (('old', oinfo, oowner), ('new', ninfo, nowner)):
            (pv, pev), (lv, lev) = h_ordered_simulation(info, owner, triples[side])
            triples[side + '_prev'] = pv
            triples[side + '_last'] = lv
            res['evidence'].append('%s ordered-prev: %s // %s' % (side, pv, pev))
            res['evidence'].append('%s ordered-last: %s // %s' % (side, lv, lev))
        if triples.get('old_prev') and triples.get('new_prev'):
            for role, idx in (('prevX', 0), ('prevY', 1), ('prevZ', 2)):
                of, os_ = triples['old_prev'][idx]
                nf, ns_ = triples['new_prev'][idx]
                orr, nrr = readers(oinfo, oowner, of), readers(ninfo, nowner, nf)
                if len(orr) == 1 and len(nrr) == 1:
                    claim('method', orr[0], nrr[0], '()D', 'anchor-updatecopy-' + role)
                    claim('field', of, nf, 'D', 'anchor-updatecopy-' + role)
                    claim('method', os_, ns_, '(D)V', 'anchor-updatecopy-' + role)
                else:
                    res['review'].append({'pair': [on, nn], 'role': role,
                                          'reason': 'prev-ридеров не 1:1'})
        if triples.get('old_last') and triples.get('new_last'):
            for role, idx in (('lastX', 0), ('lastY', 1), ('lastZ', 2)):
                of, os_ = triples['old_last'][idx]
                nf, ns_ = triples['new_last'][idx]
                orr, nrr = readers(oinfo, oowner, of), readers(ninfo, nowner, nf)
                if len(orr) == 1 and len(nrr) == 1:
                    claim('method', orr[0], nrr[0], '()D', 'anchor-renderlerp-' + role)
                    claim('field', of, nf, 'D', 'anchor-renderlerp-' + role)
                    claim('method', os_, ns_, '(D)V', 'anchor-renderlerp-' + role)
                else:
                    res['review'].append({'pair': [on, nn], 'role': role,
                                          'reason': 'last-ридеров не 1:1'})
    # --- H-scaledres (SCALE/WIDTH/HEIGHT), generic для любой пары классов ---
    sr, srev = h_scaledres(oinfo, ninfo, on, nn)
    if sr is None:
        res['evidence'].append('scaledres: НЕТ // %s' % srev)
    else:
        omap, nmap = sr
        res['evidence'].append('scaledres: ДА // %s' % srev)
        for role in ('SCALE', 'WIDTH', 'HEIGHT'):
            of, og = omap[role]
            nf, ng = nmap[role]
            claim('method', og, ng, '()I', 'anchor-scaledres-' + role)
            claim('field', of, nf, 'I', 'anchor-scaledres-' + role)
    # --- общий каскад unique для остального ---
    up, amb = match_members_unique(oinfo, ninfo, on, nn)
    for kind, o, n, desc, via in up:
        claim(kind, o, n, desc, via)
    # --- CSP для неоднозначных (только deep: нужен dump-wide поиск) ---
    if deep:
        cp, crev = match_members_csp(oinfo, ninfo, on, nn, amb,
                                     getattr(run_pair, '_cmap', {}), files_new)
        for kind, o, n, desc, via in cp:
            claim(kind, o, n, desc, via)
        res['review'].extend(crev)
    else:
        for kind, desc, olist, nlist in amb:
            if kind == 'method' and any(o in claimed_old for o in olist):
                continue
            res['review'].append({'pair': [on, nn], 'kind': kind, 'desc': desc,
                                  'old_n': len(olist), 'new_n': len(nlist),
                                  'reason': 'неоднозначный деск (нужен --deep CSP)'})
    return res


def main():
    ap = argparse.ArgumentParser(description='automapper: static remapper for game builds')
    ap.add_argument('--old-dump', default=DEF_OLD)
    ap.add_argument('--new-dump', default=DEF_NEW)
    ap.add_argument('--out', default=os.path.join(REPO, 'tools', 'automap_out'))
    ap.add_argument('--seed', default=None, help='каталог с classmap.json/membermap.json прошлого прогона')
    ap.add_argument('--pair', action='append', default=[],
                    help='явная пара классов OLD:NEW (можно несколько)')
    ap.add_argument('--pairs-only', action='store_true',
                    help='только указанные --pair (быстрый smoke, без dump-wide)')
    ap.add_argument('--deep', action='store_true', help='dump-wide CSP для неоднозначных')
    ap.add_argument('--no-auto', action='store_true',
                    help='не автомэтчить классы: только явные --pair (поверхности+файлы полные)')
    ap.add_argument('--selftest', action='store_true',
                    help='регрессия: Entity 9 ролей + scaledres 3 + twins на реальных дампах')
    ap.add_argument('--roles', default=None,
                    help='GS_OLD:GS_NEW:MC_OLD:MC_NEW для H-twins')
    ap.add_argument('--apply', action='store_true')
    ap.add_argument('--maps', default=None, help='каталог карт для --apply/--audit')
    ap.add_argument('--audit', action='store_true',
                    help='аудит готовых карт: коллапсы + ревалидация существования')
    ap.add_argument('--src', default=os.path.join(REPO, 'jni', 'agent', 'src'))
    args = ap.parse_args()

    if args.selftest:
        ok = run_selftest(args.old_dump, args.new_dump)
        sys.exit(0 if ok else 1)

    if args.apply:
        mdir = args.maps or args.out
        cmap = json.load(open(os.path.join(mdir, 'classmap.json'), encoding='utf-8'))
        mm = json.load(open(os.path.join(mdir, 'membermap.json'), encoding='utf-8'))
        apply_maps(args.src, cmap, mm)
        print('ПРИМЕЧАНИЕ: после apply соберите pack_agent.py до 0 ошибок — '
              'компилятор финальный гейт.')
        return

    if args.audit:
        mdir = args.maps or args.out
        mmap = json.load(open(os.path.join(mdir, 'membermap.json'), encoding='utf-8'))
        pairs = [(e['k'][0] if '/' in e['k'][0] else 'rustme/' + e['k'][0],
                  e['k'][1], e['k'][2],
                  e['v']['newC'] if '/' in e['v']['newC'] else 'rustme/' + e['v']['newC'],
                  e['v']['newN'], e['v'].get('desc', ''), e['v'].get('via', ''))
                 for e in mmap]
        print('аудит: записей=%d' % len(pairs))
        col = collapse_groups(pairs)
        print('аудит: групп схлопывания=%d' % len(col))
        for g in col:
            print('  COLLAPSE %s.%s <- %s' % (
                g['new'][0], g['new'][1],
                [(o[0], o[2], o[4]) for o in g['olds']]))
        # ревалидация существования (только нужные new-классы — быстро)
        want = set(p[3] for p in pairs)
        surf = build_surface(args.new_dump, pairs_only=want, log=lambda *a: None)
        missing, total = [], 0
        for oldC, kind, oldN, newC, newN, desc, via in pairs:
            total += 1
            if not member_exists(surf, newC, kind, newN, desc):
                missing.append([short(oldC), oldN, 'нет %s %s%s (с учётом super)' % (kind, newN, desc)])
        print('аудит: проверено=%d битых=%d' % (total, len(missing)))
        for m in missing[:20]:
            print('  MISSING', m)
        os.makedirs(args.out, exist_ok=True)
        json.dump({'collapse': col, 'missing': missing,
                   'checked': total}, open(os.path.join(args.out, 'AUDIT.json'),
                                           'w', encoding='utf-8'),
                  ensure_ascii=False, indent=1)
        return

    os.makedirs(args.out, exist_ok=True)
    seed_cmap = {}
    if args.seed:
        try:
            raw = json.load(open(os.path.join(args.seed, 'classmap.json'),
                                 encoding='utf-8'))
            # сид-карты хранят короткие имена (без rustme/) — нормализуем
            seed_cmap = {(k if '/' in k else 'rustme/' + k):
                         (v if '/' in v else 'rustme/' + v)
                         for k, v in raw.items()}
            print('seed classmap: %d' % len(seed_cmap))
        except Exception as e:
            print('seed не загружен: %s' % e)

    explicit = {}
    for p in args.pair:
        o, n = p.split(':')
        explicit[o if '/' in o else 'rustme/' + o] = n if '/' in n else 'rustme/' + n

    print('== поверхность старого дампа: %s' % args.old_dump)
    surf_old = build_surface(args.old_dump,
                             pairs_only=set(explicit) if args.pairs_only else None)
    print('== поверхность нового дампа: %s' % args.new_dump)
    surf_new = build_surface(args.new_dump,
                             pairs_only=set(explicit.values()) if args.pairs_only else None)

    if args.pairs_only:
        cmap = dict(explicit)
        missing = [o for o in explicit if o not in surf_old]
        missing += [n for n in explicit.values() if n not in surf_new]
        if missing:
            print('НЕТ В ДАМПЕ: %s' % missing)
            sys.exit(2)
        review_cls = []
    elif args.no_auto:
        cmap = dict(explicit)
        review_cls = []
        print('no-auto: только явные пары (%d), без структурного матчинга' % len(cmap))
    else:
        print('== матчинг классов')
        cmap = dict(explicit)
        auto, review_cls = match_classes(
            {k: v for k, v in surf_old.items() if k not in cmap},
            {k: v for k, v in surf_new.items() if k not in set(cmap.values())},
            seed={k: v for k, v in seed_cmap.items() if k not in cmap})
        cmap.update(auto)
        print('классов: seed/явных=%d, авто=%d, review=%d'
              % (len(explicit) + len(seed_cmap), len(auto), len(review_cls)))

    files_new = []
    if (args.deep or args.roles) and not args.pairs_only:
        for dp, dn, fns in os.walk(args.new_dump):
            for f in fns:
                if f.endswith('.class'):
                    files_new.append(os.path.join(dp, f))
        print('файлов для dump-wide: %d' % len(files_new))
    run_pair._cmap = cmap

    print('== матчинг членов по %d парам' % len(cmap))
    all_pairs, review, evidence = [], [], []
    for i, (on, nn) in enumerate(sorted(cmap.items())):
        oi, ni = surf_old.get(on), surf_new.get(nn)
        if oi is None or ni is None:
            review.append({'pair': [on, nn], 'reason': 'класса нет в поверхности'})
            continue
        r = run_pair(oi, ni, on, nn, files_new, deep=args.deep)
        all_pairs.extend(r['pairs'])
        review.extend(r['review'])
        evidence.extend('%s|%s: %s' % (short(on), short(nn), e) for e in r['evidence'])
        if (i + 1) % 20 == 0:
            print('  пар: %d/%d' % (i + 1, len(cmap)))

    # H-twins (нужны роли GS/MC)
    if args.roles:
        gs_o, gs_n, mc_o, mc_n = [x if '/' in x else 'rustme/' + x
                                  for x in args.roles.split(':')]
        fo, fn = [], files_new
        if not fo and not args.pairs_only:
            for dp, dn, fns in os.walk(args.old_dump):
                for f in fns:
                    if f.endswith('.class'):
                        fo.append(os.path.join(dp, f))
        tw, ev = h_twins(gs_o, gs_n, mc_o, mc_n, surf_old, surf_new, fo, fn)
        print('twins: %s // %s' % (tw, ev))
        evidence.append('twins: %s // %s' % (tw, ev))
        if tw:
            for role in ('init', 'close'):
                o, n = tw[role]
                all_pairs.append((gs_o, 'method', o, gs_n, n, '()V',
                                  'anchor-twins-' + role))

    membermap, stop, collapse = build_maps(all_pairs, cmap, surf_new)
    for g in collapse:
        review.append({'collapse': g,
                       'reason': 'many->one внутри класса: оставить только role-anchored'})
    n_collapse = len(collapse)

    json.dump(cmap, open(os.path.join(args.out, 'classmap.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1, sort_keys=True)
    json.dump(membermap, open(os.path.join(args.out, 'membermap.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    json.dump(review, open(os.path.join(args.out, 'REVIEW.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    json.dump(stop, open(os.path.join(args.out, 'STOP.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    json.dump(review_cls, open(os.path.join(args.out, 'REVIEW_classes.json'), 'w',
                               encoding='utf-8'), ensure_ascii=False, indent=1)
    with open(os.path.join(args.out, 'evidence.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(evidence) + '\n')
    with open(os.path.join(args.out, 'report.txt'), 'w', encoding='utf-8') as f:
        f.write('пары классов: %d\nпар членов: %d\nREVIEW: %d\nSTOP: %d\nCOLLAPSE: %d\n' % (
            len(cmap), len(membermap), len(review), len(stop), n_collapse))
    print('ИТОГ: классов=%d членов=%d REVIEW=%d STOP=%d COLLAPSE=%d -> %s'
          % (len(cmap), len(membermap), len(review), len(stop), n_collapse, args.out))


if __name__ == '__main__':
    main()
