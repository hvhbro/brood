#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""analyze_c2s.py — full rust: channel map. v3: candidates = ALL classes referencing
sender helper liliiilliI (from scan_db class refs), not just classes with rust: strings."""
import json, subprocess, re

ROOT = r'C:\Users\Admin\Desktop\rustme'

def disasm(cls):
    r = subprocess.run(['python', ROOT + r'\tools\jv.py', 'rustme/' + cls, '-c'],
                       capture_output=True, text=True, errors='replace', cwd=ROOT, encoding='utf-8')
    return r.stdout + r.stderr

def main():
    db = json.load(open(ROOT + r'\tools\scan_db.json', encoding='utf-8'))
    holder = json.load(open(ROOT + r'\tools\channel_holders.json'))
    cand = [t.split('/')[-1] for t in open(ROOT + r'\tools\_sender_refs.txt').read().split()]
    cand = sorted(set(cand))
    print('candidates:', len(cand))
    result = {}
    for cls in cand:
        out = disasm(cls)
        last_str = None; last_new = None
        for ln in out.splitlines():
            m = re.search(r'ldc(?:_w)?\s+#\d+\s+// String (rust:[a-z:]+)', ln)
            if m: last_str = m.group(1)
            m = re.search(r'getstatic\s+#\d+\s+// Field (\S+):Ljava/lang/String;', ln)
            if m and m.group(1) in holder: last_str = holder[m.group(1)]
            m = re.search(r'\bnew\s+#\d+\s+// class ((?:ru|kotlin)/[\w/$]+)', ln)
            if m: last_new = m.group(1)
            if 'liliiilliI.IlIIIIIIIl:(Lrustme/liIililiiI' in ln:
                result.setdefault(cls, {'send': [], 'sub': []})['send'].append((last_str, last_new))
            elif 'liliiilliI.ilIIIIIIIl:(Lrustme/lIllIilliI' in ln:
                result.setdefault(cls, {'send': [], 'sub': []})['sub'].append((last_str, None))
    json.dump(result, open(ROOT + r'\tools\c2s_map.json', 'w'), indent=1)
    send_by_chan = {}
    for cls, d in result.items():
        for ch, pc in d['send']:
            send_by_chan.setdefault(ch, []).append((cls, pc))
    print('=== SEND by channel (%d) ===' % len(send_by_chan))
    for ch in sorted(send_by_chan, key=lambda x: (x is None, x)):
        for cls, pc in send_by_chan[ch]:
            print(' %-22s [%s] %s' % (ch or '?', cls, (pc or '?').replace('ru/meproject/rustme/vanilla/network/','mp.').replace('ru/rustme/network/','rn.')))
    subs = [(cls, ch) for cls, d in result.items() for ch, _ in d['sub']]
    print('=== SUBSCRIBE sites: %d ===' % len(subs))
    for cls, ch in sorted(subs, key=lambda x: (x[1] or '')):
        print(' %-22s [%s]' % (ch or '?', cls))

if __name__ == '__main__':
    main()
