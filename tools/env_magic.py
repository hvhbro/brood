#!/usr/bin/env python3
"""Глубокая проверка слота 69: как задаётся magic-код env ([rcx+0xB8]),
что попадает в magic-ветку 0xDEAD, и какой slot-entry соответствует
'нормальному' потоку.

1. Введём термины: wrapper проверяет dword [rcx+0xB8] == '0xDEAD'+1 (0xDEAE+?).
   add eax, 0xffff2153 == eax - 0xDEAD; cmp eax,1 -> ja => НЕ 0xDEAD/0xDEAE.
   [rdi+0x3B0] (rdi=env-0x2F8 => адрес env+0xB8) вторая проверка == 0xDEAE.
2. Мы вызывали wrapper с НАШИМ JNIEnv (полученным через GetEnv/Attach) — у него
   [env+0xB8] какой? Это поле 'active' JNI handles block или крит. секция
   (в HotSpot JNIEnv _owner? посмотрим по дампу значение).
3. Проверим у m живого env из дампа: env=0x225... — ищем в MemoryList.

Существенно: взять из дампа ЗНАЧЕНИЕ dword [env+0xB8] и [env-0x2F8+0x3B0]
(тот же адрес) у реального потока.
"""
import struct
import sys
sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme\tools")
from mdump import Minidump
from jc import meta, live_table

md = Minidump(r"C:\Users\Admin\Desktop\rustme\rustme_26116_1788379393.dmp")
BASE = meta('new')['base']

# из свежего лога: env = 0x1E1B42C13D8 (но дамп делался в другой сессии).
# Найдём JNIEnv'ы в дампе: live-таблица в .data; у HotSpot JavaThread хранит
# JNIEnv по смещению; проще: сканируем heap на указатели на live-таблицу
# (структура JNIEnv начинается с указателя на неё).
LT = live_table('new', 240)  # уже VA
lt_va = meta('new')['base'] + meta('new')['live_rva']
print("live table VA = %#x" % lt_va)

# scan all memory for qword == lt_va
hits = []
for start, size, fo in md.mem_regions:
    if size > 0x4000000:
        continue  # слишком большие пропустим для скорости? нет, читаем по кускам
    data = md.read(start, size)
    if not data:
        continue
    off = 0
    while True:
        i = data.find(struct.pack('<Q', lt_va), off)
        if i < 0:
            break
        hits.append(start + i)
        off = i + 1
print("JNIEnv-like ptrs to live table: %d" % len(hits))
for h in hits[:20]:
    env = h  # JNIEnv* сам указатель на структуру, а структура[0] = vtable => JNIEnv* = h
    b = md.read(h, 0x420)
    if not b:
        continue
    magic = struct.unpack_from('<I', b, 0xB8)[0]
    ver = struct.unpack_from('<I', b, 0x3B8 - 0x2F8)[0] if len(b) > 0x100 else -1
    print("  JNIEnv* %#x  [env+0xB8]=%#010x  [env+0xC0]=%#x" % (h, magic, struct.unpack_from('<Q', b, 0xC0)[0] if len(b) > 0xC8 else -1))
