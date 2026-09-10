# -*- coding: utf-8 -*-
"""Fill S2C catalog v4: correct package roots; channel->class by PayloadChannels
constant name matched against class names in the same package (substring, then
fuzzy); decompile matched class and extract fields."""
import os
import re
import json
import subprocess

DECOMP = r'C:\Users\Admin\Desktop\rustme\tools\decomp'
RU = r'C:\Users\Admin\Desktop\rustme\dump\classes\minecraft\ru'
TMP = os.path.join(DECOMP, 'more_tmp4')

cat = json.load(open(r'C:\Users\Admin\Desktop\rustme\tools\s2c_full_catalog.json',
                     encoding='utf8'))

# pkg (rooted at ru.*) -> [class names]
pkg_classes = {}
for root, dirs, files in os.walk(RU):
    pkg = os.path.relpath(root, RU).replace(os.sep, '.')
    for fn in files:
        if fn.endswith('.class') and '$' not in fn:
            pkg_classes.setdefault(pkg, []).append(fn[:-6])

# PayloadChannels constant name -> channel, from pc_out
ch2const = {}
for root, dirs, files in os.walk(os.path.join(DECOMP, 'pc_out')):
    for fn in files:
        if fn.endswith('PayloadChannels.java'):
            src = open(os.path.join(root, fn), encoding='utf8', errors='replace').read()
            consts = re.findall(r'public static final String (\w+) = "(rust:[^"]+)"', src)
            pkg = os.path.relpath(os.path.dirname(os.path.join(root, fn)),
                                  os.path.join(DECOMP, 'pc_out')).replace(os.sep, '.')
            for name, ch in consts:
                ch2const.setdefault(ch, (pkg, name))

FIELDS_RE = re.compile(
    r'(?:private|public|protected)\s+(?:final\s+)?(?:@NotNull\s+|@Nullable\s+)?(?:final\s+)?'
    r'([\w.$<>\[\], ]+?)\s+([A-Za-z_]\w*)\s*;')


def extract_fields(java_path):
    src = open(java_path, encoding='utf8', errors='replace').read()
    fs = []
    for m in FIELDS_RE.finditer(src):
        t, n = m.group(1).strip(), m.group(2)
        if 'Companion' in n or 'INSTANCE' in n or n.startswith('access$'):
            continue
        if 'SerialDescriptor' in t or 'serializ' in t.lower():
            continue
        fs.append((n, t))
    return fs


def decompile(pkg, cls):
    cf = os.path.join(RU, pkg.replace('.', os.sep), cls + '.class')
    r = subprocess.run(['java', '-Xmx512m', '-jar', os.path.join(DECOMP, 'cfr.jar'),
                        '--outputdir', TMP, '--caseinsensitivefs', 'true',
                        '--silent', 'true', cf], capture_output=True)
    # CFR writes under classpath root: package starts at 'ru.', so output is
    # TMP/ru/<pkg>.<cls>.java -- prepend 'ru' when searching.
    rel = (pkg + '.' + cls).replace('.', os.sep) + '.java'
    cands = [os.path.join(TMP, rel),
             os.path.join(TMP, 'ru', rel)]
    for p in cands:
        if os.path.exists(p):
            return p
    base = os.path.dirname(cands[-1])
    if os.path.isdir(base):
        target = os.path.basename(cands[-1])
        for fn in os.listdir(base):
            if fn.lower() == target.lower():
                return os.path.join(base, fn)
    return None


filled = 0
for ch, v in cat.items():
    if v['fields']:
        continue
    hit = ch2const.get(ch)
    if not hit:
        continue
    pkg, const_name = hit
    # candidate class names: const 'Request' -> '*Request*PacketData' in same pkg
    cls_pkg = pkg.replace('ru.meproject.', 'meproject.')
    names = pkg_classes.get(cls_pkg, [])
    exact = [c for c in names if c == const_name + 'PacketData']
    sub = [c for c in names if const_name in c and c.endswith('PacketData')]
    if not exact and not sub:
        # reverse containment: const is longer part of class name pieces
        # e.g. const 'ScreenClosed' -> 'MlrsScreenClosedPacketData'
        toks = re.findall(r'[A-Z][a-z0-9]*', const_name)
        scored = []
        for c in names:
            if not c.endswith('PacketData') or c.endswith('ChannelsPacketData'):
                continue
            ctoks = re.findall(r'[A-Z][a-z0-9]*', c[:-len('PacketData')])
            sc = sum(1 for t in toks if t in ctoks)
            if sc:
                scored.append((sc, c))
        scored.sort(reverse=True)
        best = scored[0][1] if scored and scored[0][0] >= max(
            len(toks) - 1, 1) else None
        cls = best
    else:
        cls = (exact or sub or [None])[0]
    if not cls:
        continue
    jf = decompile(cls_pkg, cls)
    if jf:
        v['fields'] = extract_fields(jf)
        v['class'] = 'ru.' + cls_pkg + '.' + cls
        filled += 1

json.dump(cat, open(r'C:\Users\Admin\Desktop\rustme\tools\s2c_full_catalog.json', 'w',
                    encoding='utf8'), ensure_ascii=False, indent=1)
still = [ch for ch, v in cat.items() if not v['fields']]
print('filled:', filled, 'still:', len(still))
for ch in still:
    print('  ', ch, cat[ch]['class'], ch2const.get(ch))
