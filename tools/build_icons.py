#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""build_icons.py — растеризация SVG-иконок rock в атлас + генерация IconData.java.

Источник: rock/src/main/resources/assets/rockstar/icons/font/{name}.svg
Растеризация: парсинг path (M,L,H,V,C,S,Q,T,Z; A не встречается), уплощение
кривых, nonzero-winding scanline fill с суперсемплингом 8x, box-downsample.
Fill = белый RGB + alpha=coverage (тонируется glColor в рантайме).

Выход:
  jni/agent/src/utils/render/IconData.java (Base64 PNG + таблица имён)
Запуск: python tools/build_icons.py
"""
import base64, io, math, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SVG_DIR = os.path.join(ROOT, 'rock', 'src', 'main', 'resources', 'assets', 'rockstar', 'icons', 'font')
OUT_JAVA = os.path.join(ROOT, 'jni', 'agent', 'src', 'utils', 'render', 'IconData.java')

CELL = 64          # размер ячейки атласа
SS = 8             # суперсемплинг
COLS = 4           # колонок в атласе

ICONS = [
    'logo', 'search', 'setting', 'xmark',
    'category/combat', 'category/movement', 'category/other', 'category/player',
    'category/visuals',
    'menu/esp', 'menu/swing', 'menu/builder', 'menu/assist', 'menu/autobuy',
    'menu/messager',
]

num_re = re.compile(r'[-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?\d+)?')


def strip_noise(svg):
    return re.sub(r'<(defs|clipPath|mask|pattern|symbol|filter)\b[\s\S]*?</\1\s*>', '', svg, flags=re.I)


def parse_transform(t):
    m = {}
    for k, v in re.findall(r'(\w+)\s*\(([^)]*)\)', t or ''):
        nums = [float(x) for x in num_re.findall(v)]
        m[k] = nums
    return m


def apply_mat(m, x, y):
    # только translate/scale/matrix (в наших иконках transform нет)
    if not m:
        return x, y
    if 'matrix' in m and len(m['matrix']) >= 6:
        a, b, c, d, e, f = m['matrix'][:6]
        return a * x + c * y + e, b * x + d * y + f
    if 'translate' in m:
        tx = m['translate'][0] if m['translate'] else 0.0
        ty = m['translate'][1] if len(m['translate']) > 1 else 0.0
        return x + tx, y + ty
    if 'scale' in m:
        sx = m['scale'][0] if m['scale'] else 1.0
        sy = m['scale'][1] if len(m['scale']) > 1 else sx
        return x * sx, y * sy
    return x, y


def flatten_path(d, transform):
    """path d -> список полигонов [[(x,y)...], ...] (уплощённые кривые)."""
    tokens = re.findall(r'([MmLlHhVvCcSsQqTtAaZz])|([-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?\d+)?)', d)
    seq = []
    for cmd, num in tokens:
        if cmd:
            seq.append(cmd)
        else:
            seq.append(float(num))
    polys = []
    cur = []
    cx = cy = 0.0
    sx = sy = 0.0
    i = 0
    cmd = None
    prev_c2 = None
    prev_q = None

    def take():
        nonlocal i
        v = seq[i]
        i += 1
        return v

    def need_pair():
        nonlocal i
        x = seq[i]; y = seq[i + 1]; i += 2
        return x, y

    def pt(x, y):
        return apply_mat(transform, x, y)

    while i < len(seq):
        if isinstance(seq[i], str):
            cmd = seq[i]; i += 1
        rel = cmd.islower()
        c = cmd.upper()
        if c == 'M':
            x, y = need_pair()
            if rel: x += cx; y += cy
            if cur: polys.append(cur)
            cx, cy = pt(x, y)
            sx, sy = cx, cy
            cur = [(cx, cy)]
            cmd = 'l'
        elif c == 'L':
            x, y = need_pair()
            if rel: x += cx; y += cy
            cx, cy = pt(x, y)
            cur.append((cx, cy))
        elif c == 'H':
            x = take()
            if rel: x += cx
            cx, cy = pt(x, cy)
            cur.append((cx, cy))
        elif c == 'V':
            y = take()
            if rel: y += cy
            cx, cy = pt(cx, y)
            cur.append((cx, cy))
        elif c == 'C':
            x1, y1 = need_pair(); x2, y2 = need_pair(); x, y = need_pair()
            if rel: x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy
            p0 = (cx, cy)
            p1 = pt(x1, y1); p2 = pt(x2, y2); p3 = pt(x, y)
            steps = 12
            for k in range(1, steps + 1):
                t = k / steps
                mt = 1 - t
                bx = mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0]
                by = mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]
                cur.append((bx, by))
            cx, cy = p3
            prev_c2 = p2
        elif c == 'S':
            x2, y2 = need_pair(); x, y = need_pair()
            if rel: x2 += cx; y2 += cy; x += cx; y += cy
            if prev_c2 is None:
                x1, y1 = cx, cy
            else:
                x1, y1 = 2 * cx - prev_c2[0], 2 * cy - prev_c2[1]
            p0 = (cx, cy); p1 = pt(x1, y1); p2 = pt(x2, y2); p3 = pt(x, y)
            for k in range(1, 13):
                t = k / 12.0
                mt = 1 - t
                bx = mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0]
                by = mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]
                cur.append((bx, by))
            cx, cy = p3
            prev_c2 = p2
        elif c == 'Q':
            x1, y1 = need_pair(); x, y = need_pair()
            if rel: x1 += cx; y1 += cy; x += cx; y += cy
            p0 = (cx, cy); p1 = pt(x1, y1); p2 = pt(x, y)
            for k in range(1, 11):
                t = k / 10.0
                mt = 1 - t
                bx = mt**2 * p0[0] + 2 * mt * t * p1[0] + t**2 * p2[0]
                by = mt**2 * p0[1] + 2 * mt * t * p1[1] + t**2 * p2[1]
                cur.append((bx, by))
            cx, cy = p2
            prev_q = p1
        elif c == 'T':
            x, y = need_pair()
            if rel: x += cx; y += cy
            if prev_q is None:
                x1, y1 = cx, cy
            else:
                x1, y1 = 2 * cx - prev_q[0], 2 * cy - prev_q[1]
            p0 = (cx, cy); p1 = pt(x1, y1); p2 = pt(x, y)
            for k in range(1, 11):
                t = k / 10.0
                mt = 1 - t
                bx = mt**2 * p0[0] + 2 * mt * t * p1[0] + t**2 * p2[0]
                by = mt**2 * p0[1] + 2 * mt * t * p1[1] + t**2 * p2[1]
                cur.append((bx, by))
            cx, cy = p2
            prev_q = p1
        elif c == 'A':
            raise RuntimeError('arc command not supported (not expected in these icons)')
        elif c == 'Z':
            if cur:
                cur.append((sx, sy))
                polys.append(cur)
                cur = []
            cx, cy = sx, sy
        else:
            raise RuntimeError('unsupported cmd ' + cmd)
    if cur:
        polys.append(cur)
    return polys


def rasterize(polys, vb_w, vb_h):
    """nonzero-winding fill, возврат coverage alpha (CELL x CELL, float 0..1)."""
    n = CELL * SS
    # масштаб: viewBox -> n, с небольшим паддингом 4px (в CELL-масштабе)
    pad = 4 * SS
    scale = (n - 2 * pad) / max(vb_w, vb_h)
    ox = (n - vb_w * scale) / 2.0
    oy = (n - vb_h * scale) / 2.0
    edges = []  # (x0,y0,x1,y1) в grid-координатах, y вниз
    for poly in polys:
        pts = [(ox + x * scale, oy + y * scale) for x, y in poly]
        for k in range(len(pts)):
            x0, y0 = pts[k]
            x1, y1 = pts[(k + 1) % len(pts)]
            if y0 != y1:
                edges.append((x0, y0, x1, y1))
    cov = bytearray(n * n)
    for j in range(n):
        yc = j + 0.5
        xs = []
        for (x0, y0, x1, y1) in edges:
            if (y0 <= yc < y1) or (y1 <= yc < y0):
                t = (yc - y0) / (y1 - y0)
                xs.append((x0 + t * (x1 - x0), 1 if y1 > y0 else -1))
        if not xs:
            continue
        xs.sort()
        for k in range(0, len(xs) - 1, 2):
            xa = xs[k][0]; xb = xs[k + 1][0]
            if xb <= xa:
                continue
            x0i = max(0, int(math.ceil(xa)))
            x1i = min(n, int(math.ceil(xb)))
            for x in range(x0i, x1i):
                cov[j * n + x] = 255
    # box downsample SS x SS
    out = [[0.0] * CELL for _ in range(CELL)]
    area = SS * SS
    for py in range(CELL):
        for px in range(CELL):
            s = 0
            base = (py * SS) * n + px * SS
            for jj in range(SS):
                row = base + jj * n
                for ii in range(SS):
                    s += cov[row + ii]
            out[py][px] = s / float(area)
    return out


def main():
    import struct, zlib
    try:
        from PIL import Image  # noqa
        HAVE_PIL = True
    except Exception:
        HAVE_PIL = False

    icons = {}
    for name in ICONS:
        path = os.path.join(SVG_DIR, name + '.svg')
        if not os.path.isfile(path):
            print('MISSING', name)
            continue
        svg = strip_noise(open(path, encoding='utf-8').read())
        vb = re.search(r'viewBox="([\d\.\s\-]+)"', svg)
        if vb:
            parts = [float(x) for x in vb.group(1).split()]
            vb_x, vb_y, vb_w, vb_h = parts[0], parts[1], parts[2], parts[3]
        else:
            wm = re.search(r'width="([\d\.]+)"', svg)
            hm = re.search(r'height="([\d\.]+)"', svg)
            vb_x = vb_y = 0.0
            vb_w = float(wm.group(1)) if wm else 8.0
            vb_h = float(hm.group(1)) if hm else 8.0
        if vb_w <= 0 or vb_h <= 0:
            raise RuntimeError('bad viewBox for ' + name)
        polys = []
        for pm in re.finditer(r'<path[^>]*\bd="([^"]+)"', svg):
            polys.extend(flatten_path(pm.group(1), {}))
        # <g transform> не встречается в целевых иконках; рекурсивный обход g:
        for gm in re.finditer(r'<g([^>]*)>([\s\S]*?)</g>', svg):
            attrs, inner = gm.group(1), gm.group(2)
            tr = re.search(r'transform="([^"]*)"', attrs)
            tf = parse_transform(tr.group(1)) if tr else {}
            if tf:
                for pm in re.finditer(r'<path[^>]*\bd="([^"]+)"', inner):
                    polys.extend(flatten_path(pm.group(1), tf))
        # нормализуем к (0,0): вычитаем офсет viewBox
        if vb_x or vb_y:
            polys = [[(x - vb_x, y - vb_y) for x, y in poly] for poly in polys]
        cov = rasterize(polys, vb_w, vb_h)
        icons[name] = cov
        print('ok', name, 'paths:', len(polys))

    rows = (len(icons) + COLS - 1) // COLS
    W, H = CELL * COLS, CELL * rows
    # PNG (RGBA, без PIL — ручной энкодер)
    raw = bytearray()
    names = []
    for idx, (name, cov) in enumerate(icons.items()):
        names.append(name)
        cx, cy = (idx % COLS) * CELL, (idx // COLS) * CELL
    # соберём пиксели атласа
    atlas = [[(0, 0, 0, 0)] * W for _ in range(H)]
    for idx, (name, cov) in enumerate(icons.items()):
        cx, cy = (idx % COLS) * CELL, (idx // COLS) * CELL
        for py in range(CELL):
            for px in range(CELL):
                a = cov[py][px]
                ai = int(round(a * 255))
                if ai < 0: ai = 0
                if ai > 255: ai = 255
                atlas[cy + py][cx + px] = (255, 255, 255, ai)
    for y in range(H):
        raw.append(0)
        for x in range(W):
            r, g, b, a = atlas[y][x]
            raw.extend((r, g, b, a))

    def chunk(tag, data):
        c = tag + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xFFFFFFFF)

    ihdr = struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b'')

    b64 = base64.b64encode(png).decode('ascii')
    chunks = [b64[i:i + 60000] for i in range(0, len(b64), 60000)]
    names_java = ',\n            '.join('"%s"' % n for n in names)
    arr = 'new String[] {\n            ' + ',\n            '.join('"%s"' % c for c in chunks) + '\n    }'
    java = (
        'package utils.render;\n\n'
        '// Auto-generated by tools/build_icons.py — SVG-иконки rock в атласе.\n'
        '// Fill белый, alpha = coverage (тонируется glColor4ub).\n'
        'public final class IconData {\n'
        '    private IconData() {}\n\n'
        '    public static final int ATLAS_W = %d;\n'
        '    public static final int ATLAS_H = %d;\n'
        '    public static final int CELL = %d;\n'
        '    public static final int COLS = %d;\n'
        '    public static final String[] NAMES = new String[] {\n            %s\n    };\n'
        '    public static final String[] ATLAS_PNG = %s;\n\n'
        '    public static byte[] getAtlas() {\n'
        '        StringBuilder sb = new StringBuilder();\n'
        '        for (int i = 0; i < ATLAS_PNG.length; i++) sb.append(ATLAS_PNG[i]);\n'
        '        try {\n'
        '            return java.util.Base64.getDecoder().decode(sb.toString());\n'
        '        } catch (Throwable t) {\n'
        '            return null;\n'
        '        }\n'
        '    }\n'
        '}\n'
    ) % (W, H, CELL, COLS, names_java, arr)
    os.makedirs(os.path.dirname(OUT_JAVA), exist_ok=True)
    open(OUT_JAVA, 'w', encoding='utf-8').write(java)
    # сохранить и PNG для просмотра
    open(os.path.join(ROOT, 'tools', 'icons_atlas.png'), 'wb').write(png)
    print('W,H =', W, H, 'icons:', len(names))


if __name__ == '__main__':
    main()
