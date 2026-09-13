#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""derive_facts.py — СВОДНЫЙ вывод всех dump-фактов для 5 модулей.

Каждое имя — из нового дампа (newdump/minecraft), строгие case-sensitive
сравнения (Python ==; PowerShell запрещён). Ручное перепечатывание I/l-имён
запрещено: клиент правится апплаером из derive_facts.json.

Проверки (любой провал = exit 2 + STOP-запись, клиент НЕ правится):
  1. playerClass: класс, объявляющий inventory(iiilIiIiII)+profile(IIIliiIIlI)
     +capabilities(IiIlIiIiII); iilIiiIIiI обязан быть шире (EntityLivingBase$1,
     дети-волки/арморстенды).
  2. sound: loc/x/y/z геттеры базы lIiliIliiI (fload-порядок ctor ililiIliiI).
  3. overlay: реестр IIllIlIliI, mutable-лист (new ArrayList в clinit),
     hotbar-фабрика индекс 6, виджет liIIlliliI.
  4. worldtime: прямое поле WorldInfo в World (читают celestial-методы),
     сеттер пишет то же поле; save-hop IliIillIiI обязан быть без полей.
  5. entity-роли на НОВОМ дампе: H-setpos/H-ordered тройки == геттерам Esp;
     AABB ctor min/max == полям Esp.
Выход: tools/derive_facts.json {facts:{...}, evidence:[...], stop:[...]}.
"""
import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from automapper import parse_class, h_setpos, h_ordered_simulation

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NEW_RUSTME = os.path.join(REPO, 'newdump', 'minecraft', 'rustme')
NEW_ALL = os.path.join(REPO, 'newdump', 'minecraft')
OUT = os.path.join(REPO, 'tools', 'derive_facts.json')

facts, evidence, stop = {}, [], []


def fail(msg):
    stop.append(msg)
    print('STOP: ' + msg)


def build_surface(dump_dir):
    surf = {}
    for dp, _, fns in os.walk(dump_dir):
        for f in fns:
            if not f.endswith('.class'):
                continue
            r = parse_class(os.path.join(dp, f))
            if not r.get('broken') and r['name'] not in surf:
                surf[r['name']] = r
    return surf


def children(surf, sup):
    return sorted(k for k, v in surf.items() if v.get('super') == sup)


def declares_field(info, fn):
    return any(f['name'] == fn for f in info['fields'])


def declares_method(info, mn):
    return any(m == mn for (m, _) in info['methods'])


def readers(info, owner, field, desc):
    return [mn for (mn, md), mi in info['methods'].items() if md == desc
            and any(op in (0xB4, 0xB2) and nm == field and ds == desc and ow == owner
                    for op, ow, nm, ds in mi['accs'])]


print('== поверхность: %s' % NEW_ALL)
surf = build_surface(NEW_ALL)
print('   классов: %d' % len(surf))

# --- 1. playerClass ---
print('== 1. playerClass')
inv_holders = [c for c, i in surf.items() if declares_field(i, 'iiilIiIiII')]
prof_holders = [c for c, i in surf.items() if declares_method(i, 'IIIliiIIlI')]
cap_holders = [c for c, i in surf.items()
               if any(f['name'] == 'IiIlIiIiII' and f['desc'] == 'Lrustme/ilIiiIIIiI;'
                      for f in i['fields'])]
both = sorted(set(inv_holders) & set(prof_holders) & set(cap_holders))
ev = 'inv=%s prof=%s cap=%s both=%s' % (inv_holders, prof_holders, cap_holders, both)
evidence.append('player: ' + ev)
print('   ' + ev)
if len(both) != 1:
    fail('playerClass неоднозначен: %s' % both)
else:
    pc = both[0]
    facts['playerClass'] = pc.split('/')[-1]
    # ширина iilIiiIIiI: EntityLivingBase-маркер + не-игроки в детях
    base = surf.get('rustme/iilIiiIIiI')
    marked = base is not None and any('EntityLivingBase' in s for s in base['strings'])
    kids = children(surf, 'rustme/iilIiiIIiI')
    wolf_branch = any(k in ('rustme/IlIIiiIIiI', 'rustme/lliliiIIiI') for k in kids)
    ev2 = 'base=%s markedLivingBase=%s kids=%s' % ('rustme/iilIiiIIiI', marked, kids)
    evidence.append('player: ' + ev2)
    print('   ' + ev2)
    if not (marked and wolf_branch):
        fail('iilIiiIIiI не подтверждён как широкий living-base')
    # дети playerClass — только игроки (SP/other/MP-wrapper)
    sub = children(surf, pc)
    evidence.append('player: children(%s)=%s' % (pc, sub))
    print('   children(%s)=%s' % (pc, sub))

# --- 2. sound ---
print('== 2. sound getters')
base = surf.get('rustme/lIiliIliiI')
pos = surf.get('rustme/ililiIliiI')
if base is None or pos is None:
    fail('нет звуковых классов lIiliIliiI/ililiIliiI')
else:
    loc = readers(base, 'rustme/lIiliIliiI', 'iillIliIiI', '()Lrustme/liiiIIlIiI;')
    gx = readers(base, 'rustme/lIiliIliiI', 'llIlIliIiI', '()F')
    gy = readers(base, 'rustme/lIiliIliiI', 'IIllIliIiI', '()F')
    gz = readers(base, 'rustme/lIiliIliiI', 'iiiilliIiI', '()F')
    # порядок fload 8/9/10 -> llIl/IIll/iiii (байткод ctor, проверено audit18)
    ctor = pos['methods'].get(('<init>',
        '(Lrustme/liiiIIlIiI;Lrustme/IliiIIlIiI;FFZILrustme/iIlIiIliiI;FFF)V'))
    order_ok = False
    if ctor is not None:
        puts = [nm for op, ow, nm, ds in ctor['accs']
                if op == 0xB5 and ow == 'rustme/ililiIliiI' and ds == 'F']
        order_ok = (puts[2:5] == ['llIlIliIiI', 'IIllIliIiI', 'iiiilliIiI'])
    ev = 'loc=%s x=%s y=%s z=%s ctorFputs=%s' % (loc, gx, gy, gz, order_ok)
    evidence.append('sound: ' + ev)
    print('   ' + ev)
    if len(loc) == 1 and len(gx) == 1 and len(gy) == 1 and len(gz) == 1 and order_ok:
        facts['soundLoc'] = loc[0]
        facts['soundX'] = gx[0]
        facts['soundY'] = gy[0]
        facts['soundZ'] = gz[0]
    else:
        fail('sound-геттеры неоднозначны: %s' % ev)

# --- 3. overlay registry ---
print('== 3. overlay registry')
reg = surf.get('rustme/IIllIlIliI')
if reg is None:
    fail('нет реестра IIllIlIliI')
else:
    clinit = reg['methods'].get(('<clinit>', '()V'), {'accs': []})
    seq = [nm for op, ow, nm, ds in clinit['accs']
           if op == 0xB2 and ow.startswith('rustme/') and ds.startswith('Lrustme/')]
    ev = 'factoryOrder=%s' % seq
    evidence.append('overlay: ' + ev)
    print('   ' + ev)
    idx = seq.index('rustme/liIlIlIliI') if 'rustme/liIlIlIliI' in seq else -1
    # mutable-лист: putstatic после new ArrayList; immutable: после listOf
    accs = clinit['accs']
    mutable, immut = None, None
    for i, a in enumerate(accs):
        if a[0] == 0xBB and a[2] == 'java/util/ArrayList':
            for b in accs[i + 1:i + 4]:
                if b[0] == 0xB3 and b[1] == 'rustme/IIllIlIliI':
                    mutable = b[2]
        if a[0] == 0xB8 and a[2] == 'listOf':
            for b in accs[i + 1:i + 6]:
                if b[0] == 0xB3 and b[1] == 'rustme/IIllIlIliI':
                    immut = b[2]
    ev2 = 'hotbarFactoryIdx=%d mutable=%s immutable=%s' % (idx, mutable, immut)
    evidence.append('overlay: ' + ev2)
    print('   ' + ev2)
    w = surf.get('rustme/liIIlliliI')
    wok = w is not None and w.get('super') == 'rustme/lliiiiIliI'
    if idx == 6 and mutable and wok:
        facts['overlayRegistry'] = 'IIllIlIliI'
        facts['overlayWidgets'] = mutable
        facts['overlayFactories'] = immut
        facts['hotbarWidget'] = 'liIIlliliI'
    else:
        fail('overlay-цепочка не сошлась: %s widgetOk=%s' % (ev2, wok))

# --- 4. worldtime ---
print('== 4. worldtime live field')
world = surf.get('rustme/IllIIllIiI')
winfo = surf.get('rustme/lIIIIllIiI')
save = surf.get('rustme/IliIillIiI')
if world is None or winfo is None or save is None:
    fail('нет World/WorldInfo/save-классов')
else:
    live = [f['name'] for f in world['fields'] if f['desc'] == 'Lrustme/lIIIIllIiI;']
    cel = {}
    for (mn, md), mi in world['methods'].items():
        if md in ('()F', '(F)F'):
            for op, ow, nm, ds in mi['accs']:
                if ow == 'rustme/lIIIIllIiI' and ds == '()J':
                    cel.setdefault(nm, []).append(mn)
    # сеттер пишет то же поле, что читает ilIIIIiIil
    setter = winfo['methods'].get(('iIIIIIiIil', '(J)V'), {'accs': []})
    swrite = [nm for op, ow, nm, ds in setter['accs']
              if op == 0xB5 and ow == 'rustme/lIIIIllIiI' and ds == 'J']
    getread = [nm for op, ow, nm, ds in winfo['methods'].get(('ilIIIIiIil', '()J'),
               {'accs': []})['accs']
               if op == 0xB4 and ow == 'rustme/lIIIIllIiI' and ds == 'J']
    # кто зовёт save-hop? (кроме нас — нас в дампе нет)
    savecallers = []
    for cn, info in surf.items():
        for (mn, md), mi in info['methods'].items():
            for op, ow, nm, ds in mi['accs']:
                if ow == 'rustme/IliIillIiI' and nm == 'IllliIiIil':
                    savecallers.append(cn.split('/')[-1] + '.' + mn)
                    break
    savefields = len(save['fields'])
    ev = 'live=%s celestialReads=%s setterWrites=%s getterReads=%s saveFields=%d saveHopCallers=%s' % (
        live, cel, swrite, getread, savefields, savecallers)
    evidence.append('time: ' + ev)
    print('   ' + ev)
    if (len(live) == 1 and swrite == getread and swrite
            and 'ilIIIIiIil' in cel and savefields == 0 and not savecallers):
        facts['worldInfoField'] = live[0]
        facts['setWorldTime'] = 'iIIIIIiIil'
        facts['getWorldTime'] = 'ilIIIIiIil'
    else:
        fail('worldtime-цепочка не сошлась: %s' % ev)

# --- 5. entity roles (NEW dump) + AABB ---
print('== 5. entity roles (new dump)')
root = surf.get('rustme/liiIiiIIiI')
if root is None:
    fail('нет Entity root liiIiiIIiI')
else:
    tr, trev = h_setpos(root, 'rustme/liiIiiIIiI')
    evidence.append('entity setpos: %s // %s' % (tr, trev))
    print('   setpos: %s // %s' % (tr, trev))

    def rds(field):
        return [mn for (mn, md), mi in root['methods'].items() if md == '()D'
                and any(op == 0xB4 and nm == field and ds == 'D' and ow == 'rustme/liiIiiIIiI'
                        for op, ow, nm, ds in mi['accs'])]

    if tr:
        (pv, pev), (lv, lev) = h_ordered_simulation(root, 'rustme/liiIiiIIiI', tr)
        evidence.append('entity prev: %s // %s' % (pv, pev))
        evidence.append('entity last: %s // %s' % (lv, lev))
        print('   prev: %s // %s' % (pv, pev))
        print('   last: %s // %s' % (lv, lev))
        exp_pos = ['iIliiliilI', 'illiiliilI', 'liilIIiilI']
        exp_prev = ['llliIIiilI', 'IlIliIiilI', 'lIiIiliilI']
        exp_last = ['lilIiIiilI', 'IiiiiliilI', 'IiliIIiilI']
        got_pos = [rds(f)[0] if len(rds(f)) == 1 else '?' for f, _ in tr] if tr else []
        got_prev = [rds(f)[0] if len(rds(f)) == 1 else '?' for f, _ in pv] if pv else []
        got_last = [rds(f)[0] if len(rds(f)) == 1 else '?' for f, _ in lv] if lv else []
        ev = 'pos=%s prev=%s last=%s' % (got_pos, got_prev, got_last)
        evidence.append('entity roles: ' + ev)
        print('   ' + ev)
        facts['entityRolesOk'] = (got_pos == exp_pos and got_prev == exp_prev
                                  and got_last == exp_last)
        if not facts['entityRolesOk']:
            fail('роли координат НЕ совпали с Esp: %s' % ev)
    else:
        fail('H-setpos не нашёлся на новом Entity root: %s' % trev)

print('== 5b. AABB ctor')
aabb = surf.get('rustme/iilIiIlIiI')
if aabb is None:
    fail('нет AABB iilIiIlIiI')
else:
    # ctor (DDDDDD): Math.min/max привязки — ищем по вызовам Math.min/max
    # перед putfield: порядок putов minX,minY,minZ,maxX,maxY,maxZ.
    # Упрощённо-строго: у каждого из 6 D-полей ровно один put в ctor;
    # порядок putов обязан быть [ilIl, IiIl, IIIl, liIl, lIIl, iIIl]
    # (как в Esp.collectBox) — иначе STOP.
    for (mn, md), mi in aabb['methods'].items():
        if mn == '<init>' and md == '(DDDDDD)V':
            puts = [nm for op, ow, nm, ds in mi['accs']
                    if op == 0xB5 and ow == 'rustme/iilIiIlIiI' and ds == 'D']
            ev = 'ctorPuts=%s' % puts
            evidence.append('aabb: ' + ev)
            print('   ' + ev)
            exp = ['ilIlIIlIII', 'IiIlIIlIII', 'IIIlIIlIII',
                   'liIlIIlIII', 'lIIlIIlIII', 'iIIlIIlIII']
            facts['aabbOk'] = (puts == exp)
            if not facts['aabbOk']:
                fail('AABB-поля НЕ совпали с Esp: %s' % ev)
            break
    else:
        fail('нет AABB-ctor (DDDDDD)V')

json.dump({'facts': facts, 'evidence': evidence, 'stop': stop},
          open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('== ИТОГ: facts=%d stop=%d -> %s' % (len(facts), len(stop), OUT))
for s in stop:
    print('STOP: ' + s)
sys.exit(2 if stop else 0)
