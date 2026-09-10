# -*- coding: utf-8 -*-
"""Fill missing fields for S2C catalog using named_src + decompile of dump files."""
import os
import re
import json
import subprocess

DECOMP = r'C:\Users\Admin\Desktop\rustme\tools\decomp'
DUMP = os.path.join(DECOMP, '..', '..', 'dump', 'classes', 'minecraft')
NAMED = os.path.join(DECOMP, 'named_src')
TMP = os.path.join(DECOMP, 'more_tmp2')
os.makedirs(TMP, exist_ok=True)

cat = json.load(open(os.path.join(DECOMP, '..', 's2c_full_catalog.json'), encoding='utf8'))
km = json.load(open(os.path.join(DECOMP, 'kotlin_name_map.json'), encoding='utf8'))

# index named_src by 'Renamed from rustme.X' -> file
idx = {}
for root, dirs, files in os.walk(NAMED):
    for fn in files:
        if not fn.endswith('.java'):
            continue
        p = os.path.join(root, fn)
        try:
            head = open(p, encoding='utf8', errors='replace').read(4000)
        except Exception:
            continue
        m = re.search(r'Renamed from (?:rustme|ru)\.([\w.]+)', head)
        if m:
            idx[m.group(1)] = p

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


filled = 0
for ch, v in cat.items():
    if v['fields']:
        continue
    cls = v['class']  # dotted FQ name (kotlin original)
    # 1) try named_src index by last segment
    last = cls.split('.')[-1]
    cands = [p for name, p in idx.items() if name.endswith('.' + last) or name == last]
    got = None
    if cands:
        got = extract_fields(cands[0])
    # 2) try direct dump path
    if not got:
        rel = cls.replace('.', os.sep) + '.class'
        p = os.path.normpath(os.path.join(DUMP, rel))
        if os.path.exists(p):
            r = subprocess.run(['java', '-Xmx512m', '-jar', os.path.join(DECOMP, 'cfr.jar'),
                                '--outputdir', TMP, '--caseinsensitivefs', 'true',
                                '--silent', 'true', p], capture_output=True)
            jf = os.path.join(TMP, cls.replace('.', os.sep) + '.java')
            if os.path.exists(jf):
                got = extract_fields(jf)
    if got:
        v['fields'] = got
        filled += 1

json.dump(cat, open(os.path.join(DECOMP, '..', 's2c_full_catalog.json'), 'w',
                    encoding='utf8'), ensure_ascii=False, indent=1)
still = [ch for ch, v in cat.items() if not v['fields']]
print('filled:', filled, 'still missing:', len(still))
for ch in still:
    print('  ', ch, cat[ch]['class'])
