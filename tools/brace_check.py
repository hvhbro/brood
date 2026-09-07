#!/usr/bin/env python3
"""Exact brace parser for C++ file (handles strings/comments/escapes)."""
import sys

src = open(r'C:\Users\Admin\Desktop\rustme\jni\dll\src\dllmain.cpp', encoding='utf-8').read()
depth = 0
i = 0
state = 'n'
line = 1
while i < len(src):
    c = src[i]
    if c == '\n':
        line += 1
    if state == 'n':
        if c == '"':
            state = 's'
        elif c == "'":
            state = 'c'
        elif c == '/' and i + 1 < len(src) and src[i + 1] == '/':
            while i < len(src) and src[i] != '\n':
                i += 1
            continue
        elif c == '/' and i + 1 < len(src) and src[i + 1] == '*':
            i += 2
            while i + 1 < len(src) and not (src[i] == '*' and src[i + 1] == '/'):
                if src[i] == '\n':
                    line += 1
                i += 1
            i += 1
            continue
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth < 0:
                print(f'UNBALANCED close at line {line}')
                break
    elif state == 's':
        if c == '\\':
            i += 1
        elif c == '"':
            state = 'n'
    elif state == 'c':
        if c == '\\':
            i += 1
        elif c == "'":
            state = 'n'
    i += 1
print('final depth:', depth)
