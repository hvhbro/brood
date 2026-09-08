package rustme;

import modules.api.Module;
import modules.api.Modules;
import modules.impl.MenuModule;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.CustomFont;
import utils.render.GlassGrab;
import utils.render.GuiScale;
import utils.render.IconRender;
import utils.render.RenderUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Меню — точная реплика rock Class215 ("modern", сурсы rock/).
 *
 * МЕТРИКИ (Class215.init 1:1):
 *   W=min(488,max(360,sw-12))  H=min(318,max(235,sh-12)), окно по центру;
 *   railW=W*33/488, modW=W*101/488, topH=H*24/318, panelW=остаток.
 *   Рельса: logo 11x11 акцент сверху, чипы категорий 17x17 r4 (иконка 9),
 *   чип настроек прижат к низу (pad 8). Модули: ряды h15 r3 шаг 17,
 *   паддинг списка (top1,left5,bottom5,right4). Поиск 81x12 r3.
 *
 * ЦВЕТА (IiiiiIIIi_Class242, dark): accent #906BFF, bg #18151D,
 *   flat #1A171F, outline #3D3647, текст white.
 *   Чип: bg bg@a102 (+0.025 hover→accent), иконка white→accent по selected,
 *   alpha 0.62+0.38*sel. Ряд: bg flat@a102 → accent на
 *   0.08*enabled+0.018*selected+0.025*hover; текст white→accent 0.5*enabled,
 *   alpha 0.6+0.3*enabled+0.1*selected. Анимация enabled/selected 140мс
 *   (rock bind 140L). Поиск/бинд: bg bg@a173 → white на 0.035+0.035*hover.
 *   Разделители и рамка r12 0.5px: outline@a89 (Ii_method_9b0cd771).
 *
 * ПАНЕЛЬ: верхняя строка (поиск + ник 2 строки + аватар 12x12 r6), шапка
 *   настроек 22px (frost-банд, "Settings <имя>" 12px, бинд-поле 72x11,
 *   xmark 16x16 закрыть, описание 7px dim 0.58), контент: "No settings"
 *   9px white*0.55 по центру (нет настроек — как в rock), без выбора —
 *   RUSTME-градиент + счётчик.
 *
 * ВВОД: экран = только ввод (рендер из CheatHud.renderFrame). LMB тогглит
 *   ряд, RMB выбирает модуль, клик поиску/бинду — фокус/захват, ESC/RSHIFT
 *   закрывают (gs.iIIIIilliI — форк сам возвращает мышь в игру). Драг окна
 *   за верхнюю полосу (topH) с правилом 35% видимости (rock ii_method_f8b0e922).
 *   Драг HUD (ватермарка/ArrayList) сохранён.
 *
 * ОТКРЫТИЕ: масштаб 0.7→1.0 за 300мс ease-out cubic от центра (rock
 *   ii_method_f8b0e912: 0.7F+0.3F*ease).
 */
public class CheatMenuScreen extends IlIlliliiI {

    // ===== метрики rock =====
    private static final float WIN_W = 488f;
    private static final float WIN_H = 318f;

    // ===== тема rock (dark) =====
    private static final int ACCENT = 0xFF906BFF;
    private static final int ACCENT2 = 0xFF5A4BFF;
    private static final int BG = 0xFF18151D;      // 24,21,29
    private static final int FLAT = 0xFF1A171F;    // 26,23,31
    private static final int OUTLINE = 0xFF3D3647; // 61,54,71
    private static final int WHITE = 0xFFFFFFFF;

    private static final int CHIP_W = 17;
    private static final float ROW_H = 15f;
    private static final float ROW_GAP = 2f;
    private static final float SEARCH_W = 81f, SEARCH_H = 12f;
    private static final float BIND_W = 72f, BIND_H = 11f;
    private static final float ANIM_MS = 140f;     // rock bind 140L

    private static volatile CheatMenuScreen INSTANCE;

    // ===== состояние =====
    private int winX, winY, winW, winH;
    private long openTime;
    private boolean dragging;
    private int dragOffX, dragOffY;
    private final ArrayList categories = new ArrayList();
    private final ArrayList catModules = new ArrayList();
    private float[] chipAnim = new float[0];
    private int catIndex;
    private Module selected;
    private boolean searchFocused;
    private final StringBuilder search = new StringBuilder();
    private boolean capturing;
    private int mouseX, mouseY;
    private int hudDrag;        // 0=нет, 1=watermark, 2=arraylist
    private float hudOffX, hudOffY;
    private final float[] rectBuf = new float[4];
    /** Анимации enabled (Module → float[1] 0..1), как rock sig("enabled"). */
    private final HashMap anims = new HashMap();
    private long lastFrame;

    private static boolean nameResolved;
    private static String userName = "player";
    private static boolean scaleApplied;
    /** GLFW-коды: 256 ESC, 259 BACKSPACE. */
    private static final int KEY_ESC = 256;
    private static final int KEY_BACKSPACE = 259;

    public CheatMenuScreen() {
        super();
        INSTANCE = this;
    }

    /** Показать вводный курсор (страховка, вызывается из overlay-фазы). */
    public static void showCursor() {
        try {
            Class glfw = Class.forName("org.lwjgl.glfw.GLFW");
            Class windowHelper = Class.forName("rustme.ililiilliI");
            Method handle = windowHelper.getMethod("llilIlIIIl");
            long w = (Long) handle.invoke(null);
            Method setInput = glfw.getMethod("glfwSetInputMode", long.class, int.class, int.class);
            setInput.invoke(null, w, 0x00033001 /*GLFW_CURSOR*/, 0x00034001 /*GLFW_CURSOR_NORMAL*/);
        } catch (Throwable ignore) {}
    }

    // ===== guiScale =====

    public static void applyGameScale() {
        if (scaleApplied) return;
        try {
            GameContext ctx = GameContext.get();
            if (ctx.gs == null) return;
            Object holder = ctx.gs.getClass().getField("IiIIllil").get(ctx.gs);
            Object settings = holder.getClass().getField("lliliiIiI").get(holder);
            Object data = settings.getClass().getMethod("getData").invoke(settings);
            Object screenCat = data.getClass().getMethod("getScreenSettings").invoke(data);
            Object node = screenCat.getClass().getMethod("getVanillaGuiScale").invoke(screenCat);
            Object cur = node.getClass().getMethod("getValue").invoke(node);
            node.getClass().getMethod("setValue", Object.class).invoke(node, Float.valueOf(2.0f));
            scaleApplied = true;
            Log.info("Menu", "vanillaGuiScale: " + cur + " -> 2.0");
            rustme.liIIiIliiI res = new rustme.liIIiIliiI((rustme.iilliIliiI) ctx.gs);
            int scale = res.illlIlIliI();
            if (scale > 0) {
                GuiScale.set((float) scale);
                ctx.guiScale = (float) scale;
                ctx.fbHeight = res.lIllIlIliI() * scale;
                ctx.scaledWidth = res.IIllIlIliI();
                ctx.scaledHeight = res.lIllIlIliI();
                Log.info("Menu", "scale now=" + scale + " scaled=" + ctx.scaledWidth + "x" + ctx.scaledHeight);
            }
        } catch (Throwable t) {
            Log.error("Menu", "applyGameScale failed", t);
        }
    }

    // ===== жизненный цикл =====

    @Override
    public void iIllIlIlil() {
        computeLayout();
        this.openTime = System.currentTimeMillis();
        this.lastFrame = 0L;
        this.dragging = false;
        this.capturing = false;
        rebuildCategories();
        Log.info("Menu", "initGui: win=" + this.winW + "x" + this.winH
            + " at " + this.winX + "," + this.winY);
    }

    @Override
    public void iilillIlil() {
        MenuModule.notifyClosed();
    }

    /** Вызывается из MenuModule.onDisable (главный агентный поток). */
    public static void onClosedStatic() {
        INSTANCE = null;
    }

    private void computeLayout() {
        this.winW = (int) Math.min(WIN_W, Math.max(360f, this.liilIilil - 12f));
        this.winH = (int) Math.min(WIN_H, Math.max(235f, this.iiilIilil - 12f));
        this.winX = (int) Math.round((this.liilIilil - this.winW) / 2f);
        this.winY = (int) Math.round((this.iiilIilil - this.winH) / 2f);
    }

    private void rebuildCategories() {
        categories.clear();
        catModules.clear();
        for (int i = 0; i < Modules.all().size(); i++) {
            Module m = (Module) Modules.all().get(i);
            if ("Menu".equals(m.name)) continue;
            int idx = categories.indexOf(m.category);
            ArrayList lst;
            if (idx < 0) {
                categories.add(m.category);
                lst = new ArrayList();
                catModules.add(lst);
                idx = categories.size() - 1;
            } else {
                lst = (ArrayList) catModules.get(idx);
            }
            lst.add(m);
        }
        if (catIndex >= categories.size()) catIndex = 0;
        if (selected == null && catIndex < catModules.size()) {
            ArrayList lst = (ArrayList) catModules.get(catIndex);
            if (!lst.isEmpty()) selected = (Module) lst.get(0);
        }
        if (chipAnim.length != categories.size()) chipAnim = new float[categories.size()];
        for (int i = 0; i < chipAnim.length; i++) {
            chipAnim[i] = (i == catIndex && search.length() == 0) ? 1f : 0f;
        }
    }

    private ArrayList visibleModules() {
        String q = search.toString().trim().toLowerCase();
        ArrayList out = new ArrayList();
        if (q.length() == 0) {
            if (catIndex >= 0 && catIndex < catModules.size()) {
                out.addAll((ArrayList) catModules.get(catIndex));
            }
            return out;
        }
        for (int i = 0; i < Modules.all().size(); i++) {
            Module m = (Module) Modules.all().get(i);
            if ("Menu".equals(m.name)) continue;
            String name = m.name.toLowerCase();
            String cat = m.category == null ? "" : m.category.toLowerCase();
            if (name.contains(q) || cat.contains(q)) out.add(m);
        }
        return out;
    }

    /** Поиск: автоперевыбор первого результата (rock I_method_9b0fe91a). */
    private void afterSearchChanged() {
        String q = search.toString().trim();
        if (q.length() == 0) return;
        ArrayList list = visibleModules();
        if (list.isEmpty()) {
            selected = null;
            return;
        }
        if (selected == null || !list.contains(selected)) {
            selected = (Module) list.get(0);
        }
    }

    // ===== ввод =====

    @Override
    public void IiIIIlIlil(int mx, int my, float partialTicks) {
        this.mouseX = mx;
        this.mouseY = my;
    }

    @Override
    public void IIlillIlil(int mx, int my, int button) {
        // ДРАГ HUD (ЛКМ): ватермарка, затем ArrayList
        if (button == 0) {
            utils.render.Watermark.getRect(this.liilIilil, rectBuf);
            if (inRect(mx, my, rectBuf[0], rectBuf[1], rectBuf[2], rectBuf[3])) {
                hudDrag = 1;
                hudOffX = mx - rectBuf[0];
                hudOffY = my - rectBuf[1];
                return;
            }
            utils.render.CheatHud.getRect(rectBuf);
            if (utils.render.CheatHud.getListW() > 0
                && inRect(mx, my, rectBuf[0], rectBuf[1], rectBuf[2], Math.max(rectBuf[3], 14f))) {
                hudDrag = 2;
                hudOffX = mx - rectBuf[0];
                hudOffY = my - rectBuf[1];
                return;
            }
        }
        if (hudDrag != 0) hudDrag = 0;

        float wx = mx - curX, wy = my - curY;

        if (inRect(wx, wy, searchX() - curX, searchY() - curY, SEARCH_W, SEARCH_H)) {
            searchFocused = true;
            return;
        }
        searchFocused = false;

        if (capturing) {
            capturing = false;
            return;
        }

        if (selected != null
            && inRect(wx, wy, bindX() - curX, bindY() - curY, BIND_W, BIND_H)) {
            capturing = true;
            Log.info("Menu", "capturing keybind for " + selected.name);
            return;
        }

        if (selected != null
            && inRect(wx, wy, xmarkX() - curX, headerY() - 2f - curY, 16f, 16f)) {
            selected = null;
            return;
        }

        // чип настроек (низ рельсы) — home: сброс поиска и выбора
        if (inRect(wx, wy, chipX() - curX, setChipY() - curY, CHIP_W, CHIP_W)) {
            search.setLength(0);
            selected = null;
            return;
        }

        for (int i = 0; i < categories.size(); i++) {
            if (inRect(wx, wy, chipX() - curX, chipY(i) - curY, CHIP_W, CHIP_W)) {
                catIndex = i;
                search.setLength(0);
                ArrayList lst = (ArrayList) catModules.get(i);
                if (!lst.isEmpty()) selected = (Module) lst.get(0);
                return;
            }
        }

        ArrayList list = visibleModules();
        for (int i = 0; i < list.size(); i++) {
            if (inRect(wx, wy, rowX() - curX, rowY(i) - curY, rowW(), ROW_H)) {
                Module m = (Module) list.get(i);
                if (button == 0) {
                    m.toggle();
                } else if (button == 1) {
                    selected = m;
                }
                return;
            }
        }

        if (button == 0 && wy <= topH) {
            dragging = true;
            dragOffX = mx - curX;
            dragOffY = my - curY;
        }
    }

    @Override
    public void lIIlIlIlil(int mx, int my, int button) {
        if (hudDrag != 0) {
            hudDrag = 0;
            return;
        }
        if (dragging) {
            dragging = false;
            // rock ii_method_f8b0e922: <35% видимой площади — возврат в центр
            float visX = Math.min(curX + curW, this.liilIilil) - Math.max(curX, 0f);
            float visY = Math.min(curY + curH, this.iiilIilil) - Math.max(curY, 0f);
            if (visX * visY < curW * curH * 0.35f) {
                computeLayout();
            }
        }
    }

    @Override
    public void illiilIliI(int mx, int my, int button, long sinceLast) {
        if (button != 0) return;
        if (hudDrag == 1) {
            utils.render.Watermark.setPos(mx - hudOffX, my - hudOffY);
        } else if (hudDrag == 2) {
            utils.render.CheatHud.setPos(mx - hudOffX, my - hudOffY);
        } else if (dragging) {
            curX = mx - dragOffX;
            curY = my - dragOffY;
        }
    }

    @Override
    public void iIIliIlliI(int key, int scancode, int mods) {
        if (capturing) {
            capturing = false;
            if (selected != null) {
                if (key == KEY_ESC || key == KEY_BACKSPACE) {
                    selected.bindKey = -1;
                    Log.info("Menu", "bind " + selected.name + " cleared");
                } else if (key != MenuModule.TOGGLE_KEY) {
                    selected.bindKey = key;
                    Log.info("Menu", "bind " + selected.name + " -> " + keyName(key));
                } else {
                    Log.info("Menu", "bind refused (menu key)");
                }
            }
            return;
        }

        if (key == MenuModule.TOGGLE_KEY) {
            closeByModule();
            return;
        }
        if (key == KEY_ESC) {
            closeByModule();
            return;
        }
        if (searchFocused && key == KEY_BACKSPACE && search.length() > 0) {
            search.setLength(search.length() - 1);
            afterSearchChanged();
        }
    }

    /**
     * Закрытие по клавише меню. gs.iIIIIilliI() — форковый closeScreen
     * (grab мыши + displayGuiScreen(null)); showCursor только как страховка.
     */
    private void closeByModule() {
        MenuModule.notifyClosed();
        MenuModule.suppressUntilRelease();
        Modules.set("Menu", false);
        INSTANCE = null;
        boolean ok = false;
        try {
            if (this.iIliIlilI != null) {
                this.iIliIlilI.iIIIIilliI();
                ok = true;
            } else {
                Object gs = GameContext.get().gs;
                if (gs != null) {
                    ((iilliIliiI) gs).iIIIIilliI();
                    ok = true;
                }
            }
        } catch (Throwable t) {
            Log.error("Menu", "closeScreen failed, fallback", t);
        }
        if (!ok) showCursor();
    }

    @Override
    public void liIIIlIliI(char c) {
        if (!searchFocused) return;
        if (c >= 32 && c < 127 && search.length() < 24) {
            search.append(c);
            afterSearchChanged();
        }
    }

    // ===== рендер (overlay-фаза из CheatHud) =====

    private static int curX, curY, curW, curH;
    private static float railW, modW, topH, panelW;

    private static void layoutFor(int sw, int sh) {
        curW = (int) Math.min(WIN_W, Math.max(360f, sw - 12f));
        curH = (int) Math.min(WIN_H, Math.max(235f, sh - 12f));
        curX = (int) Math.round((sw - curW) / 2f);
        curY = (int) Math.round((sh - curH) / 2f);
        railW = curW * 33f / 488f;
        modW = curW * 101f / 488f;
        topH = curH * 24f / 318f;
        panelW = curW - railW - modW;
    }

    public static void renderOverlay(int scaledW, int scaledH) {
        CheatMenuScreen s = INSTANCE;
        if (s == null) return;
        try {
            GameContext ctx = GameContext.get();
            layoutFor(scaledW, scaledH);
            GlassGrab.grab(ctx); // блюр-снапшот (бэкбуфер ещё цел)

            // открытие: 0.7 -> 1.0, 300мс, ease-out cubic (rock Class215)
            float t = Math.min(1f, Math.max(0f, (System.currentTimeMillis() - s.openTime) / 300f));
            float ease = 1f - (1f - t) * (1f - t) * (1f - t);
            float scale = 0.7f + 0.3f * ease;
            boolean anim = scale < 0.9995f;
            if (anim) {
                RenderUtil.scaleStart(curX + curW * 0.5f, curY + curH * 0.5f, scale, scale);
            }
            s.updateAnims();
            s.drawWindow(ctx);
            s.drawRail(ctx);
            s.drawModuleColumn(ctx);
            s.drawPanel(ctx);
            if (anim) {
                RenderUtil.scaleEnd();
            }
            org.lwjglx.opengl.GL11.glColor4f(1f, 1f, 1f, 1f);
        } catch (Throwable tt) {
            Log.error("Menu", "renderOverlay failed", tt);
        }
    }

    // ===== зоны =====

    private static float panelX() { return curX + railW + modW; }
    private static float chipX() { return curX + (railW - CHIP_W) * 0.5f; }
    private static float chipY(int i) { return curY + topH + 3f + i * (CHIP_W + 3f); }
    private static float setChipY() { return curY + curH - 8f - CHIP_W; }
    private static float rowX() { return curX + railW + 5f; }
    private static float rowW() { return modW - 9f; }
    private static float rowY(int i) { return curY + topH + 1f + i * (ROW_H + ROW_GAP); }
    private static float searchX() { return panelX() + 7f; }
    private static float searchY() { return curY + (topH - SEARCH_H) * 0.5f; }
    private static float avatarX() { return curX + curW - 5f - 12f; }
    private static float headerY() { return curY + topH + 2f; }
    private static float xmarkX() { return curX + curW - 5f - 16f; }
    private static float bindY() { return headerY() + 0.5f; }

    private float bindX() {
        float titleEnd = panelX() + 7f + textW("Settings " + selName(), 12f);
        return Math.min(titleEnd + 6f, xmarkX() - BIND_W - 6f);
    }

    private String selName() { return selected != null ? selected.name : ""; }

    private static boolean inRect(float px, float py, float x, float y, float w, float h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    // ===== цвета/текст =====

    private static int withAlpha(int rgb, int a) {
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static int mix(int c1, int c2, float t) {
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        int a1 = (c1 >>> 24) & 0xFF, r1 = (c1 >>> 16) & 0xFF, g1 = (c1 >>> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >>> 24) & 0xFF, r2 = (c2 >>> 16) & 0xFF, g2 = (c2 >>> 8) & 0xFF, b2 = c2 & 0xFF;
        int a = (int) (a1 + (a2 - a1) * t);
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static float vc(float boxH, float size) {
        return (boxH - size * 1.2f) * 0.5f;
    }

    /** Заголовки/акценты — sf_semibold (rock II-шрифт). */
    private static void text(String s, float x, float y, int color, float size) {
        CustomFont.drawString(s, x, y, color, false, size, CustomFont.HUD_FONT);
    }

    /** Обычный текст — medium (rock i-шрифт). */
    private static void textM(String s, float x, float y, int color, float size) {
        CustomFont.drawString(s, x, y, color, false, size, CustomFont.WM_FONT);
    }

    private static float textW(String s, float size) {
        return CustomFont.getWidth(s, size, CustomFont.HUD_FONT);
    }

    private static float textWM(String s, float size) {
        return CustomFont.getWidth(s, size, CustomFont.WM_FONT);
    }

    private static String categoryIcon(String cat) {
        String c = cat == null ? "" : cat.trim().toLowerCase();
        if ("combat".equals(c) || "movement".equals(c) || "player".equals(c) || "visuals".equals(c)) {
            return "category/" + c;
        }
        return "category/other";
    }

    // ===== анимации (rock bind 140ms) =====

    private float[] animFor(Module m) {
        float[] a = (float[]) anims.get(m);
        if (a == null) {
            a = new float[]{m.isState() ? 1f : 0f};
            anims.put(m, a);
        }
        return a;
    }

    private void updateAnims() {
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0L ? 16f : Math.min(64f, now - lastFrame);
        lastFrame = now;
        float step = dt / ANIM_MS;
        ArrayList list = visibleModules();
        for (int i = 0; i < list.size(); i++) {
            animStep((Module) list.get(i), step);
        }
        if (selected != null) animStep(selected, step);
        for (int i = 0; i < chipAnim.length; i++) {
            float target = (i == catIndex && search.length() == 0) ? 1f : 0f;
            if (chipAnim[i] < target) chipAnim[i] = Math.min(target, chipAnim[i] + step);
            else if (chipAnim[i] > target) chipAnim[i] = Math.max(target, chipAnim[i] - step);
        }
    }

    private void animStep(Module m, float step) {
        float[] a = animFor(m);
        float target = m.isState() ? 1f : 0f;
        if (a[0] < target) a[0] = Math.min(target, a[0] + step);
        else if (a[0] > target) a[0] = Math.max(target, a[0] - step);
    }

    // ===== окно (rock I_method_c6137aff) =====

    private void drawWindow(GameContext ctx) {
        if (GlassGrab.available(ctx)) {
            GlassGrab.drawWindowGlass(ctx, curX, curY, curW, curH, 12f);
        } else {
            int bg = withAlpha(BG, 242);
            RenderUtil.drawRoundedRectShader(ctx, curX, curY, curW, curH, 12f, bg, bg, bg, bg, 0.25f);
        }
        int sep = withAlpha(OUTLINE, 89);
        RenderUtil.drawRoundedBorder(ctx, curX, curY, curW, curH, 12f, 0.5f, sep);
        RenderUtil.drawRect(ctx, curX + railW - 1f, curY + 1f, 1f, curH - 2f, sep);
        RenderUtil.drawRect(ctx, curX + railW + modW - 1f, curY + 1f, 1f, curH - 2f, sep);
        RenderUtil.drawRect(ctx, curX + railW + modW + 1f, curY + topH - 1f,
            panelW - 1f, 1f, sep);
    }

    // ===== рельса (rock I_method_894df1d5) =====

    private void drawRail(GameContext ctx) {
        IconRender.drawIcon("logo", curX + railW * 0.5f - 5.5f, curY + 11f, 11f, ACCENT);

        for (int i = 0; i < categories.size(); i++) {
            String cat = (String) categories.get(i);
            float cx = chipX(), cy = chipY(i);
            float sel = chipAnim[i];
            boolean hov = inRect(mouseX, mouseY, cx, cy, CHIP_W, CHIP_W);
            int bg = mix(withAlpha(BG, 102), ACCENT, hov ? 0.025f : 0f);
            RenderUtil.drawRoundedRectShader(ctx, cx, cy, CHIP_W, CHIP_W, 4f, bg, bg, bg, bg, 0.25f);
            int ic = mix(WHITE, ACCENT, sel);
            ic = withAlpha(ic & 0xFFFFFF, (int) (255f * (0.62f + 0.38f * sel)));
            IconRender.drawIcon(categoryIcon(cat), cx + 4f, cy + 4f, 9f, ic);
        }

        // чип настроек (низ рельсы)
        float sx = chipX(), sy = setChipY();
        boolean hov = inRect(mouseX, mouseY, sx, sy, CHIP_W, CHIP_W);
        int bg = mix(withAlpha(BG, 102), ACCENT, hov ? 0.025f : 0f);
        RenderUtil.drawRoundedRectShader(ctx, sx, sy, CHIP_W, CHIP_W, 4f, bg, bg, bg, bg, 0.25f);
        int ic = withAlpha(WHITE & 0xFFFFFF, (int) (255f * (0.58f + 0.35f * (hov ? 1f : 0f))));
        IconRender.drawIcon("setting", sx + 4f, sy + 4f, 9f, ic);
    }

    // ===== колонка модулей (rock i_method_97fcdb5 + I_method_bf3023f6) =====

    private void drawModuleColumn(GameContext ctx) {
        String title;
        if (search.length() > 0) {
            title = "Search";
        } else if (catIndex < categories.size()) {
            title = (String) categories.get(catIndex);
        } else {
            title = "";
        }
        text(title, curX + railW + 8f, curY + vc(topH, 9f), WHITE, 9f);

        ArrayList list = visibleModules();
        for (int i = 0; i < list.size(); i++) {
            Module m = (Module) list.get(i);
            float en = animFor(m)[0];
            float sel = m == selected ? 1f : 0f;
            float rx = rowX(), ry = rowY(i);
            boolean hov = inRect(mouseX, mouseY, rx, ry, rowW(), ROW_H);
            int bg = mix(withAlpha(FLAT, 102), withAlpha(ACCENT, 102),
                0.08f * en + 0.018f * sel + (hov ? 0.025f : 0f));
            RenderUtil.drawRoundedRectShader(ctx, rx, ry, rowW(), ROW_H, 3f, bg, bg, bg, bg, 0.25f);
            int tc = mix(WHITE, ACCENT, 0.5f * en);
            tc = withAlpha(tc & 0xFFFFFF, (int) (255f * (0.6f + 0.3f * en + 0.1f * sel)));
            textM(m.name, rx + 5f, ry + vc(ROW_H, 7f), tc, 7f);
        }
    }

    // ===== панель (rock II_method_18597158) =====

    private void drawPanel(GameContext ctx) {
        float fsSmall = 6f, fsRow = 7f;

        // --- верхняя строка: поиск + ник/LEEK + аватар ---
        float sx = searchX(), sy = searchY();
        boolean sHov = inRect(mouseX, mouseY, sx, sy, SEARCH_W, SEARCH_H);
        int fieldBg = mix(withAlpha(BG, 173), WHITE, 0.035f + (sHov || searchFocused ? 0.035f : 0f));
        RenderUtil.drawRoundedRectShader(ctx, sx, sy, SEARCH_W, SEARCH_H, 3f, fieldBg, fieldBg, fieldBg, fieldBg, 0.25f);
        IconRender.drawIcon("search", sx + 3.5f, sy + 3.5f, 5f, withAlpha(WHITE, 122));
        String q = search.toString();
        float textX = sx + 11f;
        int fieldTc = withAlpha(WHITE, 184);
        if (q.length() == 0 && !searchFocused) {
            textM("Search", textX, sy + vc(SEARCH_H, fsSmall), fieldTc, fsSmall);
        } else {
            textM(q, textX, sy + vc(SEARCH_H, fsSmall), fieldTc, fsSmall);
            if (searchFocused && (System.currentTimeMillis() / 400L) % 2L == 0L) {
                RenderUtil.drawRect(ctx, textX + textWM(q, fsSmall) + 1f,
                    sy + SEARCH_H * 0.25f, 1f, SEARCH_H * 0.5f, fieldTc);
            }
        }

        float avX = avatarX(), avY = curY + (topH - 12f) * 0.5f;
        RenderUtil.drawRoundedRectShader(ctx, avX, avY, 12f, 12f, 6f,
            ACCENT, ACCENT2, ACCENT, ACCENT2, 0.3f);
        String un = userName();
        float unW = textW(un, fsSmall);
        text(un, avX - 3f - unW, sy - 1f, WHITE, fsSmall);
        textM("LEEK", avX - 3f - textWM("LEEK", fsSmall), sy + 5f, withAlpha(WHITE, 133), fsSmall);

        // --- шапка настроек ---
        float hy = headerY();
        if (selected != null) {
            // frost-банд (rock i_method_f5052edf: подсветка блока настроек)
            int band = withAlpha(WHITE, 12);
            RenderUtil.drawRoundedRectShader(ctx, panelX() - 11f, hy - 10.5f,
                panelW + 22f, 22f + 10.5f + 22f, 1.5f, band, band, band, band, 0.25f);

            text("Settings " + selName(), panelX() + 7f, hy - 1f, WHITE, 12f);

            boolean kHov = inRect(mouseX, mouseY, bindX(), bindY(), BIND_W, BIND_H);
            int kbg = mix(withAlpha(BG, 173), WHITE, capturing ? 0.07f : 0.035f + (kHov ? 0.035f : 0f));
            RenderUtil.drawRoundedRectShader(ctx, bindX(), bindY(), BIND_W, BIND_H, 3f, kbg, kbg, kbg, kbg, 0.25f);
            String bl = capturing ? "Press a key..."
                : (selected.bindKey != -1 ? "Key: " + keyName(selected.bindKey) : "Set bind");
            textM(bl, bindX() + 4f, bindY() + vc(BIND_H, fsSmall), capturing ? WHITE : fieldTc, fsSmall);
            if (capturing && (System.currentTimeMillis() / 400L) % 2L == 0L) {
                RenderUtil.drawRect(ctx, bindX() + 4f + textWM(bl, fsSmall) + 1f,
                    bindY() + BIND_H * 0.25f, 1f, BIND_H * 0.5f, WHITE);
            }

            boolean xHov = inRect(mouseX, mouseY, xmarkX(), hy - 2f, 16f, 16f);
            IconRender.drawIcon("xmark", xmarkX() + 3.5f, hy - 2f + 3.5f, 9f,
                withAlpha(WHITE, (int) (255f * (0.8f + 0.2f * (xHov ? 1f : 0f)))));

            // описание (rock: marquee 7px dim 0.58) — у нас статус тоггла
            int toggleKey = selected.bindKey != -1 ? selected.bindKey : selected.toggleKey;
            textM("Toggle: " + keyName(toggleKey), panelX() + 7f, hy + 16f,
                withAlpha(WHITE, 148), fsRow);

            // контент: настроек у модулей нет — rock-плейсхолдер
            float contentY = hy + 22f + 2f;
            float contentH = curY + curH - 2f - contentY;
            String ns = "No settings";
            float nsW = textWM(ns, 9f);
            textM(ns, panelX() + (panelW - nsW) * 0.5f,
                contentY + Math.max(6f, contentH * 0.5f - 6f), withAlpha(WHITE, 140), 9f);
        } else {
            float contentY = hy + 4f;
            CustomFont.drawGradientString("RUSTME", panelX() + 7f, contentY,
                ACCENT, ACCENT2, 11f, CustomFont.HUD_FONT);
            int n = 0;
            for (int i = 0; i < Modules.all().size(); i++) {
                if (!"Menu".equals(((Module) Modules.all().get(i)).name)) n++;
            }
            textM(n + " modules", panelX() + 7f, contentY + 18f, withAlpha(WHITE, 120), fsSmall);
        }
    }

    // ===== утилиты =====

    private static String keyName(int key) {
        if (key <= 0) return "-";
        if (key == 32) return "SPACE";
        if (key == 256) return "ESC";
        if (key == 258) return "TAB";
        if (key == 259) return "BACKSPACE";
        if (key == 260) return "INSERT";
        if (key == 261) return "DELETE";
        if (key == 262) return "RIGHT";
        if (key == 263) return "LEFT";
        if (key == 264) return "DOWN";
        if (key == 265) return "UP";
        if (key == 266) return "PAGE_UP";
        if (key == 267) return "PAGE_DOWN";
        if (key == 268) return "HOME";
        if (key == 269) return "END";
        if (key == 280) return "CAPS_LOCK";
        if (key == 340) return "L_SHIFT";
        if (key == 341) return "L_CTRL";
        if (key == 342) return "L_ALT";
        if (key == 344) return "R_SHIFT";
        if (key == 345) return "R_CTRL";
        if (key == 346) return "R_ALT";
        if (key >= 290 && key <= 301) return "F" + (key - 289);
        if (key >= 48 && key <= 57) return String.valueOf((char) key);
        if (key >= 65 && key <= 90) return String.valueOf((char) key);
        return "K" + key;
    }

    private static String userName() {
        if (nameResolved) return userName;
        nameResolved = true;
        try {
            GameContext ctx = GameContext.get();
            if (ctx.wrapperClass != null && ctx.player != null) {
                Method profileGetter = ctx.wrapperClass.getMethod("IlIiIiiilI");
                Object profile = profileGetter.invoke(ctx.player);
                if (profile != null) {
                    Method nameGetter = profile.getClass().getMethod("getName");
                    Object n = nameGetter.invoke(profile);
                    if (n != null) userName = (String) n;
                }
            }
        } catch (Throwable ignore) {}
        return userName;
    }
}
