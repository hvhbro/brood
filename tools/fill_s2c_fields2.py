# -*- coding: utf-8 -*-
"""Match S2C channels to data classes via class-file constant pool strings.

Kotlin serialization data classes reference their *PayloadChannels companion
constant; simplest reliable link: scan every named data class file bytes for
the channel string (the serializer's descriptor carries it via
@Serializable(with=...) -> no. Instead: decompile by NAME pattern from the
PayloadChannels constant name, e.g. 'Request' -> '*Request*PacketData' in same
package, and verify by reading the Kotlin @Metadata d2 simple name."""
import os
import re
import json
import subprocess

DECOMP = r'C:\Users\Admin\Desktop\rustme\tools\decomp'
DUMP = r'C:\Users\Admin\Desktop\rustme\dump\classes\minecraft\ru'
TMP = os.path.join(DECOMP, 'more_tmp3')
os.makedirs(TMP, exist_ok=True)

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


# collect all data classes per package (from disk)
pkg_classes = {}
for root, dirs, files in os.walk(DUMP):
    for fn in files:
        if not fn.endswith('.class') or '$' in fn:
            continue
        pkg = os.path.relpath(root, DUMP).replace(os.sep, '.')
        pkg_classes.setdefault(pkg, []).append(fn[:-6])

filled = 0
for ch, v in cat.items():
    if v['fields']:
        continue
    pkg = v['package']
    simple = v['class'].split('.')[-1]  # e.g. RequestPacketData
    stem = simple[:-len('PacketData')] if simple.endswith('PacketData') else simple
    cands = [c for c in pkg_classes.get(pkg, []) if c == simple]
    if not cands:
        # loose match: contains stem
        cands = [c for c in pkg_classes.get(pkg, []) if stem in c]
    if not cands:
        continue
    cf = os.path.join(DUMP, pkg.replace('.', os.sep), cands[0] + '.class')
    r = subprocess.run(['java', '-Xmx512m', '-jar', os.path.join(DECOMP, 'cfr.jar'),
                        '--outputdir', TMP, '--caseinsensitivefs', 'true',
                        '--silent', 'true', cf], capture_output=True)
    # find output file (case-insensitive path)
    rel = (pkg + '.' + cands[0]).replace('.', os.sep) + '.java'
    jf = os.path.join(TMP, rel)
    if not os.path.exists(jf):
        jf_low = os.path.join(TMP, rel.lower())
        if os.path.exists(jf_low):
            jf = jf_low
    if jf and os.path.exists(jf):
        v['fields'] = extract_fields(jf)
        v['class'] = pkg + '.' + cands[0]
        filled += 1

json.dump(cat, open(r'C:\Users\Admin\Desktop\rustme\tools\s2c_full_catalog.json', 'w',
                    encoding='utf8'), ensure_ascii=False, indent=1)
still = [ch for ch, v in cat.items() if not v['fields']]
print('filled:', filled, 'still:', len(still))
for ch in still:
    print('  ', ch, cat[ch]['class'])
