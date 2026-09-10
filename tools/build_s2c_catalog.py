# -*- coding: utf-8 -*-
"""Builds S2C catalog: channel -> data class -> fields (named packages only)."""
import os
import re
import json
import subprocess

ch2pkg = {}
for pkg_dir, dirs, files in os.walk('pc_out'):
    for fn in files:
        if fn.endswith('PayloadChannels.java'):
            src = open(os.path.join(pkg_dir, fn), encoding='utf8', errors='replace').read()
            consts = re.findall(r'public static final String (\w+) = "(rust:[^"]+)"', src)
            pkg = pkg_dir.replace('pc_out' + os.sep, '').replace(os.sep, '.')
            for name, ch in consts:
                ch2pkg.setdefault(ch, pkg)
                ch2pkg[ch + '_cls'] = pkg + '.' + name + 'PacketData'


def fields_of(java_src):
    fs = []
    for m in re.finditer(
            r'(?:private|public|protected)\s+(?:final\s+)?@?(?:NotNull\s+|Nullable\s+)?(?:final\s+)?'
            r'([\w.$<>\[\], ]+?)\s+([A-Za-z_]\w*)\s*;', java_src):
        t, n = m.group(1), m.group(2)
        if t.split('.')[0] in ('kotlin', 'kotlinx', 'java', 'int', 'double', 'float', 'long',
                               'boolean', 'byte', 'short', 'char') and t not in ('int', 'double',
                                                                                 'float', 'long',
                                                                                 'boolean'):
            continue
        fs.append((n, t.strip()))
    return fs


result = {}
named_root = r'C:\Users\Admin\Desktop\rustme\tools\decomp\named_src'
for ch, pkg in list(ch2pkg.items()):
    if ch.endswith('_cls'):
        continue
    cls_guess = ch2pkg.get(ch + '_cls', '?')
    simple = cls_guess.split('.')[-1]
    jf = None
    for pkg_dir, dirs, files in os.walk('pc_out'):
        cand = os.path.join(pkg_dir, simple + '.java')
        if os.path.exists(cand):
            jf = cand
            break
    if not jf:
        cand = os.path.join(named_root, cls_guess.replace('.', os.sep) + '.java')
        if os.path.exists(cand):
            jf = cand
    fields = []
    if jf and os.path.exists(jf):
        src = open(jf, encoding='utf8', errors='replace').read()
        fields = fields_of(src)
    result[ch] = {'package': pkg, 'class': cls_guess, 'file': jf, 'fields': fields}

json.dump(result, open('../s2c_full_catalog.json', 'w', encoding='utf8'),
          ensure_ascii=False, indent=1)
for ch in sorted(result):
    r = result[ch]
    flds = ', '.join(n + ':' + t for n, t in r['fields'][:8])
    has = '+' if r['fields'] else '-'
    print('%-24s %s %s :: %s' % (ch, has, r['class'].split('.')[-1], flds[:130]))
