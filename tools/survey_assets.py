# -*- coding: utf-8 -*-
"""Survey assets/minecraft for item icon atlases."""
import os
import collections

ROOT = r"C:\Users\Admin\Desktop\rustme\assets"
c = collections.Counter()
samples = {}
for dirpath, dirnames, filenames in os.walk(ROOT):
    for fn in filenames:
        if fn.endswith(".png"):
            rel = os.path.relpath(dirpath, ROOT).replace("\\", "/")
            parts = rel.split("/")
            key = "/".join(parts[:3])
            c[key] += 1
            samples.setdefault(key, []).append(fn)

for k, v in c.most_common(20):
    print(v, k, "|", samples[k][:4])
