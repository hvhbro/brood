# -*- coding: utf-8 -*-
"""Fill S2C catalog fields v3: package map rooted at dump/ru + stem substring match."""
import os
import re
import json
import subprocess

DECOMP = r'C:\Users\Admin\Desktop\rustme\tools\decomp'
RU = r'C:\Users\Admin\Desktop\rustme\dump\classes\minecraft\ru'
TMP = os.path.join(DECOMP, 'more_tmp3')

cat = json.load(open(r'C:\Users\Admin\Desktop\rustme\tools\s2c_full_catalog.json',
                     encoding='utf8'))

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


# all data classes per package rooted at dump/ru
pkg_classes = {}
for root, dirs, files in os.walk(RU):
    pkg = os.path.relpath(root, RU).replace(os.sep, '.')
    for fn in files:
        if fn.endswith('.class') and '$' not in fn:
            pkg_classes.setdefault(pkg, []).append(fn[:-6])


def try_decompile(pkg, cls, stem):
    cf = os.path.join(RU, pkg.replace('.', os.sep), cls + '.class')
    r = subprocess.run(['java', '-Xmx512m', '-jar', os.path.join(DECOMP, 'cfr.jar'),
                        '--outputdir', TMP, '--caseinsensitivefs', 'true',
                        '--silent', 'true', cf], capture_output=True)
    rel = (pkg + '.' + cls).replace('.', os.sep) + '.java'
    jf = os.path.join(TMP, rel)
    if os.path.exists(jf):
        return extract_fields(jf), pkg + '.' + cls
    # lowercase fallback
    jf = os.path.join(TMP, rel.lower())
    if os.path.exists(jf):
        return extract_fields(jf), pkg + '.' + cls
    # case-insensitive search
    base = os.path.dirname(jf)
    target = os.path.basename(rel)
    for fn in os.listdir(base) if os.path.isdir(base) else []:
        if fn.lower() == target.lower():
            return extract_fields(os.path.join(base, fn)), pkg + '.' + cls
    return None, None


filled = 0
for ch, v in cat.items():
    if v['fields']:
        continue
    pkg = v['package']
    simple = v['class'].split('.')[-1]
    stem = simple[:-len('PacketData')] if simple.endswith('PacketData') else simple
    stem2 = stem[:-len('Screen')] if stem.endswith('Screen') and stem != 'Screen' else stem
    # 1) exact
    cands = [c for c in pkg_classes.get(pkg.replace('ru.', '', 1)
             if pkg.startswith('ru.') else pkg, []) if c == simple]
    # normalize package: catalog packages are already rooted at ru.*
    if not cands and pkg.startswith('ru.'):
        cands = [c for c in pkg_classes.get(pkg[3:], []) if c == simple]
    # 2) substring
    if not cands and pkg.startswith('ru.'):
        cands = [c for c in pkg_classes.get(pkg[3:], []) if stem in c or stem2 in c]
    if not cands:
        continue
    use_pkg = pkg[3:] if pkg.startswith('ru.') else pkg
    f, real = try_decompile(use_pkg, cands[0], stem)
    if f:
        v['fields'] = f
        v['class'] = real
        filled += 1

json.dump(cat, open(r'C:\Users\Admin\Desktop\rustme\tools\s2c_full_catalog.json', 'w',
                    encoding='utf8'), ensure_ascii=False, indent=1)
still = [ch for ch, v in cat.items() if not v['fields']]
print('filled:', filled, 'still:', len(still))
for ch in still:
    print('  ', ch, cat[ch]['class'])
