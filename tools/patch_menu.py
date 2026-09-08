import io, re, shutil, sys

p = r'C:\Users\Admin\Desktop\rustme\jni\agent\src\rustme\CheatMenuScreen.java'
s = io.open(p, encoding='utf-8').read()

def rep(old, new, tag):
    global s
    if old not in s:
        print('MISS:', tag)
        sys.exit(1)
    s = s.replace(old, new, 1)
    print('ok:', tag)

# 1) import
rep('import utils.render.GuiScale;',
    'import utils.render.GuiScale;\nimport utils.render.IconRender;', 'import')

# 2) palette
rep('''    private static final int TEXT = 0xFFE9E9F2;
    private static final int TEXT_DIM = 0x8CE9E9F2;
    private static final int HOVER = 0x12FFFFFF;
    private static final int ROW_REST = 0x07FFFFFF;
    private static final int CHIP_REST = 0x0CFFFFFF;
    private static final int BORDER = 0x2EFFFFFF;
    private static final int SEPARATOR = 0x1EFFFFFF;
    private static final int FIELD_BG = 0x14FFFFFF;''',
    '''    private static final int TEXT = 0xFFFFFFFF;      // rock slot5 = white
    private static final int TEXT_DIM = 0x85FFFFFF;  // mulAlpha 0.52
    private static final int TEXT_MED = 0xA6FFFFFF;  // mulAlpha 0.65
    private static final int HOVER = 0x403D3647;     // rgba(61,54,71,63.75)
    private static final int ROW_REST = 0x2918151D;  // panel2 mix
    private static final int CHIP_REST = 0x2918151D;
    private static final int BORDER = 0x59FFFFFF;
    private static final int SEPARATOR = 0x593D3647; // hover alpha 89.25
    private static final int FIELD_BG = 0xAD18151D;  // search alpha 173.4''', 'palette')

rep('    private static final int ACCENT_SOFT = 0x46906BFF;',
    '    private static final int ACCENT_SOFT = 0x5A906BFF;', 'accent_soft')
rep('    private static final int ACCENT_ROW_ON = 0x2E906BFF;',
    '    private static final int ACCENT_ROW_ON = 0x4D906BFF;', 'row_on')
rep('    private static final int ACCENT_ROW_SEL = 0x3A906BFF;',
    '    private static final int ACCENT_ROW_SEL = 0x66906BFF;', 'row_sel')
rep('    private static final int SEL_OFF = 0x1FFFFFFF;',
    '    private static final int SEL_OFF = 0x403D3647;', 'sel_off')
rep('    private static final int KB_CAPTURING = 0x2E906BFF;',
    '    private static final int KB_CAPTURING = 0x4D906BFF;', 'kb_cap')
rep('    private static final int KB_HOVER = 0x1CFFFFFF;',
    '    private static final int KB_HOVER = 0x473D3647;', 'kb_hov')

# 3) iconForCategory before rebuildCategories
rep('    private void rebuildCategories() {',
    '''    private static String iconForCategory(String cat) {
        String c = cat == null ? "" : cat.toLowerCase();
        if (c.startsWith("combat")) return "category/combat";
        if (c.startsWith("movement")) return "category/movement";
        if (c.startsWith("visual")) return "category/visuals";
        if (c.startsWith("player")) return "category/player";
        return "category/other";
    }

    private void rebuildCategories() {''', 'iconForCategory')

# 4) drawRail -> logo icon + category icons
rep('''        RenderUtil.drawRoundedRectShader(ctx, curX + (RAIL_W - 11f) * 0.5f, curY + 6.5f,
            11f, 11f, 3f, ACCENT, ACCENT2, ACCENT, ACCENT2, 0.3f);''',
    '''        IconRender.drawIcon("logo", curX + (RAIL_W - 11f) * 0.5f, curY + 11f, 11f, TEXT);''', 'rail_logo')

rep('''            String letter = cat.length() > 0 ? cat.substring(0, 1).toUpperCase() : "?";
            float fs = 7f;
            float tw = textW(letter, fs);
            text(letter, cx + (CHIP - tw) * 0.5f, cy + vc(CHIP, fs), sel ? WHITE : TEXT_DIM, fs);''',
    '''            int ic = (sel || hov) ? WHITE : TEXT_MED;
            IconRender.drawIcon(iconForCategory(cat), cx + 4f, cy + 4f, 9f, ic);''', 'rail_icons')

# 5) fallback bg
rep('''            RenderUtil.drawRoundedRectShader(ctx, curX, curY, curW, curH, 12f,
                0xF70C0C12, 0xF70C0C12, 0xF70C0C12, 0xF70C0C12, 0.25f);''',
    '''            RenderUtil.drawRoundedRectShader(ctx, curX, curY, curW, curH, 12f,
                0xE618151D, 0xE618151D, 0xE618151D, 0xE618151D, 0.25f);''', 'fallback_bg')

# 6) search icon
rep('''        RenderUtil.drawRoundedRectShader(ctx, sx, sy, SEARCH_W, SEARCH_H, 3f, sbg, sbg, sbg, sbg, 0.25f);
        String q = search.toString();
        if (q.length() == 0 && !searchFocused) {
            text("Search", sx + 4f, sy + vc(searchH_placeholder, fsSmall), TEXT_DIM, fsSmall);''',
    'NEVER_MATCH', 'skip_guard')

old_search = '''        RenderUtil.drawRoundedRectShader(ctx, sx, sy, SEARCH_W, SEARCH_H, 3f, sbg, sbg, sbg, sbg, 0.25f);
        String q = search.toString();
        if (q.length() == 0 && !searchFocused) {
            text("Search", sx + 4f, sy + vc(SEARCH_H, fsSmall), TEXT_DIM, fsSmall);
        } else {
            text(q, sx + 4f, sy + vc(SEARCH_H, fsSmall), TEXT, fsSmall);
            if (searchFocused && (System.currentTimeMillis() / 400L) % 2L == 0L) {
                float cx = sx + 4f + textW(q, fsSmall);
                RenderUtil.drawRect(ctx, cx, sy + SEARCH_H * 0.25f, 1f, SEARCH_H * 0.5f, TEXT);
            }
        }'''
new_search = '''        RenderUtil.drawRoundedRectShader(ctx, sx, sy, SEARCH_W, SEARCH_H, 3f, sbg, sbg, sbg, sbg, 0.25f);
        IconRender.drawIcon("search", sx + 3.5f, sy + 3.5f, 5f, 0x7AFFFFFF);
        String q = search.toString();
        float textX = sx + 11f;
        if (q.length() == 0 && !searchFocused) {
            text("Search", textX, sy + vc(SEARCH_H, fsSmall), TEXT_MED, fsSmall);
        } else {
            text(q, textX, sy + vc(SEARCH_H, fsSmall), TEXT, fsSmall);
            if (searchFocused && (System.currentTimeMillis() / 400L) % 2L == 0L) {
                float cx = textX + textW(q, fsSmall);
                RenderUtil.drawRect(ctx, cx, sy + SEARCH_H * 0.25f, 1f, SEARCH_H * 0.5f, TEXT);
            }
        }'''
rep(old_search, new_search, 'search_icon')

# 7) row text shades
rep('            int tc = m.isState() ? mix(TEXT, ACCENT, 0.55f) : TEXT;',
    '            int tc = m.isState() ? mix(TEXT, ACCENT, 0.55f) : (hov ? TEXT : TEXT_MED);', 'row_text')

# 8) hint color
rep('0x50E9E9F2, fsSmall);', '0x50FFFFFF, fsSmall);', 'hint')

# 9) searchField y: центр хедера (если было winY+5)
s = s.replace('private float searchY() { return curY + 5f; }',
              'private float searchY() { return curY + (topH - SEARCH_H) * 0.5f; }')
s = s.replace('private float searchY() { return winY + 5f; }',
              'private float searchY() { return curY + (topH - SEARCH_H) * 0.5f; }')

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('ALL PATCHES APPLIED')
