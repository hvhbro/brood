#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""jv.py — resolve class name via disk_name_map.json, copy under true name to
javap_tmp7, run javap. Usage: python jv.py rustme.Name [-c] [-m MethodName]
Flags: -c = disassemble (-p always on). """
import json, os, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DUMP = os.path.join(ROOT, 'dump', 'classes', 'minecraft')
MAP = os.path.join(ROOT, 'tools', 'disk_name_map.json')
TMP = os.path.join(ROOT, 'tools', 'javap_tmp7')
JAVAP = r'C:\Users\Admin\AppData\Local\Programs\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\javap.exe'

def main():
    args = sys.argv[1:]
    dis = '-c' in args
    if '-c' in args: args.remove('-c')
    filt = None
    if '-m' in args:
        i = args.index('-m'); filt = args[i+1]; del args[i:i+2]
    name = args[0].replace('\\', '/').replace('.', '/')
    pkg = '/'.join(name.split('/')[:-1])
    simple = name.split('/')[-1]
    m = json.load(open(MAP, encoding='utf-8'))
    files = m.get(name)
    if not files:
        hits = [k for k in m if k.split('/')[-1] == simple]
        print('not in map; similar:', hits[:10]); return 1
    outdir = os.path.join(TMP, pkg)
    os.makedirs(outdir, exist_ok=True)
    dst = os.path.join(outdir, simple + '.class')
    src = os.path.join(ROOT, 'dump', 'classes', 'minecraft', 'rustme', files[0].replace('/', os.sep))
    shutil.copyfile(src, dst)
    cmd = [JAVAP, '-p', '-cp', TMP] + (['-c'] if dis else []) + [name.replace('/', '.')]
    r = subprocess.run(cmd, capture_output=True, text=True, errors='replace')
    out = r.stdout + r.stderr
    if filt:
        lines = out.splitlines(); keep = []; on = True
        res = []
        for ln in lines:
            if ln and not ln.startswith(' ') and (ln[0].isalpha() or ln[0] in 'public final static abstract protected'):
                on = (filt in ln)
            if on: res.append(ln)
        out = '\n'.join(res)
    print(out)
    return 0

if __name__ == '__main__':
    sys.exit(main())
