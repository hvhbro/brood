#!/usr/bin/env python3
"""Энкодер protected-формата классов RustMe (точная инверсия decryptor.decode_protected).

Проверка корректности: decode_protected(encode_protected(x)) == x,
и encode(decode(entry)) == entry для реального зашифрованного класса из jar.
"""
import io
import struct
import sys
from pathlib import Path

sys.path.insert(0, r"C:\Users\Admin\Desktop\rustme")
import decryptor as D
from decryptor import KEY, OPS, TAG, OPMAP, decval, Reader, Writer, decode_protected

INV_TAG = {v: k for k, v in TAG.items()}
INV_OPMAP = {v: k for k, v in OPMAP.items()}
assert len(INV_OPMAP) == len(OPMAP), "OPMAP not bijective!"

def encval(x, name):
    bits, ops = OPS[name]
    m = (1 << bits) - 1
    for op, c in reversed(ops):
        if op == 0:
            x ^= c
        elif op == 1:
            x = (x - c) & m
        elif op == 2:
            x = (x + c) & m
        else:
            raise ValueError(op)
    return x & m


class SR:  # standard (plain) class reader
    def __init__(self, b):
        self.b = b
        self.p = 0
    def take(self, n):
        x = self.b[self.p:self.p+n]
        assert len(x) == n
        self.p += n
        return x
    def u1(self): return self.take(1)[0]
    def u2(self): return int.from_bytes(self.take(2), 'big')
    def u4(self): return int.from_bytes(self.take(4), 'big')


def encode_code_bytes(code):
    """Стандартные опкоды -> закодированные. Транскрибирует структуру,
    чтобы remap'ить только байты опкодов (включая wide-субопкод)."""
    out = bytearray(code)
    p = 0
    n = len(code)
    while p < n:
        op = code[p]
        if op not in INV_OPMAP:
            raise ValueError('opcode %#x has no encoded form' % op)
        out[p] = INV_OPMAP[op]
        if op == 0xaa:  # tableswitch
            q = p + 1
            while q % 4: q += 1
            low = int.from_bytes(code[q+4:q+8], 'big', signed=True)
            hi = int.from_bytes(code[q+8:q+12], 'big', signed=True)
            cnt = hi - low + 1
            assert 0 <= cnt <= 100000
            p = q + 12 + 4*cnt
        elif op == 0xab:  # lookupswitch
            q = p + 1
            while q % 4: q += 1
            npairs = int.from_bytes(code[q+4:q+8], 'big', signed=True)
            assert 0 <= npairs <= 100000
            p = q + 8 + 8*npairs
        elif op == 0xc4:  # wide: remap sub-opcode тоже
            sub = code[p+1]
            if sub in INV_OPMAP:
                out[p+1] = INV_OPMAP[sub]
                sub = INV_OPMAP[sub]  # уже remapнули out
            valid = set(range(0x15, 0x1a)) | set(range(0x36, 0x3b)) | {0x84, 0xa9}
            assert sub in valid, 'bad wide sub %#x' % sub
            p += 6 if sub == 0x84 else 4
        else:
            lens = {0x10:2, 0x12:2, 0x11:3, 0x13:3, 0x14:3, 0x84:3, 0xa9:2,
                    0xbc:2, 0xb9:5, 0xba:5, 0xc5:4, 0xc8:5, 0xc9:5}
            if 0x15 <= op <= 0x19 or 0x36 <= op <= 0x3a:
                l = 2
            elif 0x99 <= op <= 0xa8 or 0xb2 <= op <= 0xb8 or op in (0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7):
                l = 3
            else:
                l = lens.get(op, 1)
            p += l
    return bytes(out)


def encode_attr(r, w, utf, ctx):
    if ctx == 'method':
        # encoded: len(u4) затем name(u2)!
        w.u4(encval(ln, 'method_attr_len')) if False else None
        # (см. ниже — нужно знать ln заранее: читаем стандартную структуру)
    raise RuntimeError('use structured encoder')


def encode_protected(plain, header=None):
    """plain: стандартный class-файл (CAFEBABE...). Возвращает protected-бlob.
    header: 256 байт преамбулы (decode их игнорирует; дефолт — нули)."""
    if header is None:
        header = b'\x00' * 256
    assert len(header) == 256
    r = SR(plain)
    assert r.take(4) == b'\xCA\xFE\xBA\xBE'
    minor = r.u2(); major = r.u2()
    cpc = r.u2()
    utf_names = {}  # cp index -> str (для распознавания атрибутов)
    # --- парсим CP в структуру ---
    cp = []  # записи: (tag, ...) с индексом cp[i-1]
    i = 1
    while i < cpc:
        tag = r.u1()
        if tag == 1:
            ln = r.u2(); data = r.take(ln); cp.append((1, ln, data))
            utf_names[i] = data.decode('latin1')
        elif tag in (3, 4):
            cp.append((tag, r.u4()))
        elif tag in (5, 6):
            hi = r.u4(); lo = r.u4(); cp.append((tag, hi, lo))
            i += 1  # long/double занимают 2 слота CP
        elif tag in (7, 8, 16):
            cp.append((tag, r.u2()))
        elif tag in (9, 10, 11, 12, 17, 18):
            cp.append((tag, r.u2(), r.u2()))
        elif tag == 15:
            cp.append((15, r.u1(), r.u2()))
        else:
            raise ValueError('bad std tag %d' % tag)
        i += 1

    access = r.u2(); this_i = r.u2(); super_i = r.u2()
    ic = r.u2(); interfaces = [r.u2() for _ in range(ic)]

    def read_attrs(r, utf_ctx):
        ac = r.u2()
        attrs = []
        for _ in range(ac):
            ni = r.u2(); ln = r.u4(); data = r.take(ln)
            attrs.append((ni, ln, data))
        return attrs

    fc = r.u2()
    fields = []
    for _ in range(fc):
        fa = r.u2(); fn = r.u2(); fd = r.u2()
        attrs = read_attrs(r, 'field')
        fields.append((fa, fn, fd, attrs))
    mc = r.u2()
    methods = []
    for _ in range(mc):
        ma = r.u2(); mn = r.u2(); md = r.u2()
        attrs = read_attrs(r, 'method')
        methods.append((ma, mn, md, attrs))
    class_attrs = read_attrs(r, 'class')
    assert r.p == len(plain), 'trailing bytes %d/%d' % (r.p, len(plain))

    w = Writer()
    w.put(b'\xCA\xFE\xBA\xBE')  # decode копирует magic в вывод как есть
    # --- заголовок ---
    w.u2(encval(minor, 'minor')); w.u2(encval(major, 'major')); w.u2(encval(cpc, 'cp_count'))
    # --- CP ---
    for entry in cp:
        tag = entry[0]
        w.u1(INV_TAG[tag])
        if tag == 1:
            _, ln, data = entry
            w.u2(encval(ln, 'utf_len'))
            for b in data:
                w.u1(encval(b, 'utf_byte'))
        elif tag == 3:
            w.u4(encval(entry[1], 'int'))
        elif tag == 4:
            w.u4(encval(entry[1], 'float'))
        elif tag == 5:
            w.u4(encval(entry[1], 'long_high')); w.u4(encval(entry[2], 'long_low'))
        elif tag == 6:
            w.u4(encval(entry[1], 'double_high')); w.u4(encval(entry[2], 'double_low'))
        elif tag in (7, 8, 16):
            nm = {7: 'class_idx', 8: 'string_idx', 16: 'mtype_idx'}[tag]
            w.u2(encval(entry[1], nm))
        elif tag in (9, 10, 11, 12, 18):
            nm1, nm2 = {9: ('fieldref_1', 'fieldref_2'), 10: ('methodref_1', 'methodref_2'),
                        11: ('imethodref_1', 'imethodref_2'), 12: ('nat_1', 'nat_2'),
                        18: ('indy_1', 'indy_2')}[tag]
            _, a, b = entry  # a = первый u2 plain (name/class), b = второй (desc/idx)
            # decode: a_raw=r.u2() -> decval(a_raw,'x_2') == b(plain); b_raw -> decval(b_raw,'x_1') == a(plain)
            w.u2(encval(b, nm2))
            w.u2(encval(a, nm1))
        elif tag == 15:
            w.u1(encval(entry[1], 'mh_kind')); w.u2(encval(entry[2], 'mh_idx'))
        else:
            raise ValueError(tag)
    # --- заголовок класса: encoded order icount, super, access, this ---
    w.u2(encval(ic, 'class_icount'))
    w.u2(encval(super_i, 'class_super'))
    w.u2(encval(access, 'class_access'))
    w.u2(encval(this_i, 'class_this'))
    for ix in interfaces:
        w.u2(encval(ix, 'interface_idx'))
    # --- поля ---
    w.u2(encval(fc, 'fields_count'))
    for fa, fn, fd, attrs in fields:
        # encoded order: desc, access, acount, name
        w.u2(encval(fd, 'field_desc'))
        w.u2(encval(fa, 'field_access'))
        w.u2(encval(len(attrs), 'field_acount'))
        w.u2(encval(fn, 'field_name'))
        for ni, ln, data in attrs:
            w.u2(encval(ni, 'field_attr_name'))
            w.u4(encval(ln, 'field_attr_len'))
            w.put(data)
    # --- методы ---
    w.u2(encval(mc, 'methods_count'))
    for ma, mn, md, attrs in methods:
        # encoded order: name, acount, access, desc
        w.u2(encval(mn, 'method_name'))
        w.u2(encval(len(attrs), 'method_acount'))
        w.u2(encval(ma, 'method_access'))
        w.u2(encval(md, 'method_desc'))
        for ni, ln, data in attrs:
            # method attrs: encoded = len(u4) THEN name(u2)
            w.u4(encval(ln, 'method_attr_len'))
            w.u2(encval(ni, 'method_attr_name'))
            # тело атрибута
            name = utf_names.get(ni, '')
            if name == 'Code':
                # Code: encoded = code_len(4), max_locals(2), max_stack(2), code(remapped), rest raw
                cr = SR(data)
                ms = cr.u2(); ml = cr.u2(); cl = cr.u4()
                code = cr.take(cl)
                w.u4(encval(cl, 'code_len'))
                w.u2(encval(ml, 'code_max_locals'))
                w.u2(encval(ms, 'code_max_stack'))
                w.put(encode_code_bytes(code))
                w.put(cr.take(len(data) - cr.p))  # exception table + nested attrs raw
            else:
                w.put(data)
    # --- атрибуты класса ---
    w.u2(encval(len(class_attrs), 'class_acount'))
    for ni, ln, data in class_attrs:
        w.u2(encval(ni, 'class_attr_name'))
        w.u4(encval(ln, 'class_attr_len'))
        w.put(data)

    body = bytes(w.b)
    # header передаётся В ФАЙЛОВОМ (XOR-нутом) виде и не трогается;
    # XOR для body — с фазой файла: file[256+i] = body[i] ^ KEY[(256+i) % 15]
    out = bytearray(header)
    for i, b in enumerate(body):
        out.append(b ^ KEY[(256 + i) % len(KEY)])
    return bytes(out)


def utf_name(plain, idx):
    """Достать utf8-строку по CP-индексу из plain-класса (для распознавания Code)."""
    r = SR(plain)
    r.take(4); r.u2(); cpc = r.u2()
    i = 1
    while i < cpc:
        tag = r.u1()
        if tag == 1:
            ln = r.u2(); data = r.take(ln)
            if i == idx:
                return data.decode('latin1')
        elif tag in (3, 4):
            r.take(4)
        elif tag in (5, 6):
            r.take(8); i += 1
        elif tag in (7, 8, 16):
            r.take(2)
        elif tag in (9, 10, 11, 12, 17, 18):
            r.take(4)
        elif tag == 15:
            r.take(3)
        else:
            raise ValueError(tag)
        i += 1
    return None


def roundtrip_test(plain):
    enc = encode_protected(plain)
    dec = decode_protected(enc)
    ok = (dec == plain)
    return enc, ok


if __name__ == '__main__':
    import zipfile
    # 1) Проверка на реальном зашифрованном классе из minecraft.jar
    jar_path = Path(r"C:\Users\Admin\AppData\Roaming\rustme-launcher\profiles\prod-a\minecraft.jar")
    if not jar_path.exists():
        print("jar not found:", jar_path)
        sys.exit(1)
    with zipfile.ZipFile(jar_path) as z:
        names = z.namelist()
        tested = 0
        mismatches = 0
        for nm in names:
            if not nm.endswith('.class'):
                continue
            raw = z.read(nm)
            if not D._looks_protected(raw):
                continue
            plain = D.decode_protected(raw)
            enc = encode_protected(plain)
            if enc != raw:
                mismatches += 1
                print("MISMATCH", nm, len(raw), len(enc))
                # показать первое расхождение
                for i in range(min(len(raw), len(enc))):
                    if raw[i] != enc[i]:
                        print("  first diff at %#x: %02x vs %02x" % (i, raw[i], enc[i]))
                        break
                if mismatches >= 3:
                    break
            tested += 1
            if tested >= 20:
                break
        print("round-trip real entries: tested=%d mismatches=%d" % (tested, mismatches))
