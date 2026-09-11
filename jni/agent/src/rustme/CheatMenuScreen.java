package rustme;

import modules.api.Module;
import modules.api.Modules;
import modules.impl.MenuModule;
import utils.etc.ConfigManager;
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

    // ===== тема rock (dark, палитра Class242 — точные значения) =====
    private static final int ACCENT = 0xFF906BFF;   // 144,107,255
    private static final int ACCENT2 = 0xFF4D00FF;  // 77,0,255 (второй градиент палитры)
    private static final int BG = 0xFF18151D;       // 24,21,29 (окно @a90 → 229)
    private static final int FLAT = 0xFF1A171F;     // 26,23,31 (ряды/чипы, альфа отдельно)
    private static final int OUTLINE = 0xFF3D3647;  // 61,54,71 (@a25 → 63.75)
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK_SOFT = 0xFF050407; // 5,4,7 (самый тёмный, палитра[7])

    private static final int CHIP_W = 17;
    private static final float ROW_H = 15f;
    private static final float ROW_GAP = 2f;
    private static final float SEARCH_W = 81f, SEARCH_H = 12f;
    private static final float BIND_W = 72f, BIND_H = 11f;
    private static final float ANIM_MS = 140f;     // rock bind 140L
    private static final float WIN_R = 12f;        // радиус окна (rock drawClientRect 12)
    private static final float CHIP_R = 4f;        // радиус чипа
    private static final float ROW_R = 3f;         // радиус ряда

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

    // ===== вкладка CONFIG (чип настроек внизу рельсы) =====
    private boolean configMode;
    private final StringBuilder cfgName = new StringBuilder();
    private boolean cfgNameFocused;
    private final ArrayList cfgList = new ArrayList(); // имена файлов без .rm
    private int cfgSel = -1;
    private String cfgMsg;
    private boolean cfgMsgOk;
    private long cfgMsgAt;

    // ===== вкладка Friends (поле ввода + список, см. drawFriendsPanel) =====
    private final StringBuilder friendName = new StringBuilder();
    private boolean friendFocused;
    private String friendMsg;
    private boolean friendMsgOk;
    private long friendMsgAt;
    private int friendSel = -1;

    // ===== ColorPicker (rockstar iIii_Class12, 143x136) =====
    private static final int PICKER_W = 143;
    private static final int PICKER_H = 136;
    private boolean pickerOpen;
    private Module.ColorSetting pickerTarget;
    private String pickerTitle = "";
    private float pickerX, pickerY;
    private boolean pickerDrag;
    private float pickerDragOffX, pickerDragOffY;
    private boolean svDrag, hueDrag;
    private float pHue, pSat, pBright; // HSV; s растёт вправо, v убывает вниз
    private final ArrayList pickerPresets = new ArrayList(); // Integer(argb)
    private boolean presetsLoaded;
    private boolean capturing;
    private int mouseX, mouseY;
    private int hudDrag;        // 0=нет, 1=watermark, 2=arraylist
    private float hudOffX, hudOffY;
    private final float[] rectBuf = new float[4];
    /** Активный слайдер (Module.FloatSetting) при драге. */
    private Object sliderDrag;
    /** Анимации enabled (Module → float[1] 0..1), как rock sig("enabled"). */
    private final HashMap anims = new HashMap();
    /** Анимации элементов настроек (ключ → float[]): pill/слайдер/чипы. */
    private final HashMap settingAnims = new HashMap();
    private long lastFrame;
    private float lastAnimDt = 16f;

    // ===== скролл настроек (rock scroll-контейнер) =====
    private float scrollTarget, scrollCur;
    // ===== скролл списка модулей (вкладки переполняются — Visuals и др.) =====
    private float modScrollTarget, modScrollCur;
    private int modScrollCat = -2;    private float settingsContentH;
    private long lastScrollFrame;
    private Module selectedAtLayout;
    /** Кэш раскладки настроек последнего кадра (для хит-теста кликов). */
    private final ArrayList settingRows = new ArrayList();
    private boolean rowsValid;

    /** Строка настроек: элемент + геометрия (content-space, без скролла). */
    private static final class SRow {
        final Module.Setting s;
        final int kind;      // 0=section 1=bool 2=slider 3=mode 4=multi 5=color
        final float y;
        float h;
        final float chipH;
        final ArrayList cx = new ArrayList(); // чипы/свотчи: x
        final ArrayList cy = new ArrayList(); // y (content-space)
        final ArrayList cwd = new ArrayList(); // w
        final ArrayList cdata = new ArrayList(); // Integer(индекс) / Integer(argb)
        SRow(Module.Setting s, int kind, float y, float h, float chipH) {
            this.s = s; this.kind = kind; this.y = y; this.h = h; this.chipH = chipH;
        }
    }

    private static boolean nameResolved;
    private static String userName = "player";
    private static boolean scaleApplied;
    /** Память меню между открытиями: вкладка, выбранный модуль, скролл настроек. */
    private static int lastCatIndex = -1;
    private static String lastSelectedName;
    private static final HashMap<String, float[]> scrollStore = new HashMap<String, float[]>();
    /** GLFW-коды: 256 ESC, 257 ENTER, 259 BACKSPACE. */
    private static final int KEY_ESC = 256;
    private static final int KEY_ENTER = 257;
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
        // Любое закрытие экрана (ESC/RSHIFT/замена другим GUI/инвентарь) —
        // приводим state модуля Menu в соответствие экрану. Иначе Menu
        // остаётся ON и дальнейшие тогглы рассинхронизированы.
        MenuModule.onScreenClosed();
        // пикер цвета — попап экрана, закрываем вместе с меню
        pickerOpen = false;
        pickerTarget = null;
        svDrag = false;
        hueDrag = false;
        pickerDrag = false;
        // память меню: вкладка + модуль + скролл настроек
        lastCatIndex = catIndex;
        lastSelectedName = selected != null ? selected.name : null;
        if (selected != null) {
            scrollStore.put(selected.name, new float[]{scrollTarget, scrollCur});
        }
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
        if (lastCatIndex >= 0 && lastCatIndex < categories.size()) {
            catIndex = lastCatIndex;   // восстановление последней вкладки
        } else {
            catIndex = 0;
        }
        if (selected == null && lastSelectedName != null) {
            for (int i = 0; i < Modules.all().size(); i++) {
                Module m = (Module) Modules.all().get(i);
                if (m.name.equals(lastSelectedName)) {
                    selected = m;
                    break;
                }
            }
        }
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
        modScrollTarget = 0f;
        modScrollCur = 0f;
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
        if (pickerOpen && (svDrag || hueDrag || pickerDrag)) {
            if (svDrag) {
                updateSv(mx - pickerX, my - pickerY);
            } else if (hueDrag) {
                updateHue(my - pickerY);
            } else {
                pickerX = mx - pickerDragOffX;
                pickerY = my - pickerDragOffY;
                clampPicker();
            }
            return;
        }
        if (sliderDrag != null) {
            Module.FloatSetting fs = (Module.FloatSetting) sliderDrag;
            float trW = panelW - 14f;
            float trX = panelX() + 7f;
            // mx/my экранные → локальные панели
            applySlider(fs, mx - curX - (trX - curX), trW);
        }
    }

    private void applySlider(Module.FloatSetting fs, float localX, float trW) {
        float t = localX / trW;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        float v = fs.min + (fs.max - fs.min) * t;
        v = Math.round(v / fs.step) * fs.step;
        fs.value = Math.max(fs.min, Math.min(fs.max, v));
    }

    /** Клик по элементу настройки (wx/wy — локальные окна; ay — экранный y строки).
     *  true = клик поглощён. */
    private boolean handleSettingClick(SRow r, float wx, float wy, float ay) {
        if (r.kind == 1) {
            Module.BoolSetting bs = (Module.BoolSetting) r.s;
            bs.set(!bs.get());
            Log.info("Menu", bs.name + " -> " + (bs.get() ? "ON" : "OFF"));
            return true;
        }
        if (r.kind == 6) {
            openPicker((Module.ColorSetting) r.s);
            return true;
        }
        if (r.kind == 2) {
            float trX = panelX() + 7f;
            float trW = panelW - 14f;
            sliderDrag = r.s;
            applySlider((Module.FloatSetting) r.s, wx - (trX - curX), trW);
            return true;
        }
        if (r.kind == 3 || r.kind == 4 || r.kind == 5) {
            for (int j = 0; j < r.cdata.size(); j++) {
                float cxv = ((Float) r.cx.get(j)).floatValue();
                float cyv = ((Float) r.cy.get(j)).floatValue() - scrollCur;
                float cwv = ((Float) r.cwd.get(j)).floatValue();
                if (!inRect(wx, wy, cxv - curX, cyv - curY, cwv, r.chipH)) continue;
                if (r.kind == 3) {
                    Module.ModeSetting ms = (Module.ModeSetting) r.s;
                    int idx = ((Integer) r.cdata.get(j)).intValue();
                    if (ms.index() != idx) {
                        ms.value = idx;
                        Log.info("Menu", ms.name + " -> " + ms.get());
                    }
                } else if (r.kind == 4) {
                    Module.MultiSetting ms = (Module.MultiSetting) r.s;
                    int idx = ((Integer) r.cdata.get(j)).intValue();
                    ms.toggle(idx);
                    Log.info("Menu", ms.name + ": " + ms.options[idx]
                        + (ms.isSelected(idx) ? " ON" : " OFF"));
                } else {
                    Module.ColorSetting cs = (Module.ColorSetting) r.s;
                    cs.argb = ((Integer) r.cdata.get(j)).intValue();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void IIlillIlil(int mx, int my, int button) {
        // ЗАХВАТ МЫШИ-БИНДА (MOUSE1..MOUSE8) — до всей остальной обработки
        if (capturing) {
            capturing = false;
            if (selected != null && button >= 0 && button <= 7) {
                selected.bindKey = button;
                Log.info("Menu", "bind " + selected.name + " -> " + keyName(button));
            }
            return;
        }

        // ===== ColorPicker: попап выше всего меню =====
        if (pickerOpen) {
            float px = mx - pickerX;
            float py = my - pickerY;
            boolean inside = px >= 0f && py >= 0f && px <= PICKER_W && py <= PICKER_H;
            if (button == 1) { // RMB: удалить пресет, иначе — ничего
                int hit = presetHit(px, py);
                if (hit >= 0 && hit < pickerPresets.size()) {
                    pickerPresets.remove(hit);
                    savePickerPresets();
                }
                return;
            }
            if (!inside) { // клик мимо окна — закрыть (как у rock)
                closePicker();
                return;
            }
            if (inRect(px, py, PICKER_W - 15f, 5f, 10f, 10f)) { // xmark
                closePicker();
                return;
            }
            if (inRect(px, py, 6f, 20f, 114f, 70f)) { // SV-бокс
                svDrag = true;
                updateSv(px, py);
                return;
            }
            if (inRect(px, py, PICKER_W - 18f, 20f, 12f, 70f)) { // hue-бар
                hueDrag = true;
                updateHue(py);
                return;
            }
            int hit = presetHit(px, py);
            if (hit >= 0) {
                if (hit < pickerPresets.size()) { // применить пресет
                    float[] hsv = rgbToHsv(((Integer) pickerPresets.get(hit)).intValue());
                    pHue = hsv[0];
                    pSat = hsv[1];
                    pBright = hsv[2];
                    applyPicker();
                } else if (pickerPresets.size() < 10) { // кнопка "+"
                    pickerPresets.add(Integer.valueOf(hsvToRgb(pHue, pSat, pBright)));
                    savePickerPresets();
                }
                return;
            }
            // тянуть окно за любую свободную точку (rock: draggable)
            pickerDrag = true;
            pickerDragOffX = mx - pickerX;
            pickerDragOffY = my - pickerY;
            return;
        }
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
            utils.render.KeybindsWidget.getRect(rectBuf);
            if (inRect(mx, my, rectBuf[0], rectBuf[1], rectBuf[2], Math.max(rectBuf[3], 14f))) {
                hudDrag = 3;
                hudOffX = mx - rectBuf[0];
                hudOffY = my - rectBuf[1];
                return;
            }
        }
        if (hudDrag != 0) hudDrag = 0;

        float wx = mx - curX, wy = my - curY;

        if (inRect(wx, wy, searchX() - curX, searchY() - curY, SEARCH_W, SEARCH_H)) {
            searchFocused = true;
            cfgNameFocused = false;
            friendFocused = false;
            configMode = false;
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

        // ===== вкладка CONFIG: клики по элементам панели =====
        if (configMode && selected == null && wy > topH && wx >= railW + modW) {
            if (button != 0) return;
            float csw = panelW - 14f;
            float csx = panelX() + 7f;
            if (inRect(wx, wy, csx - curX, cfgFieldY() - curY, csw, 12f)) {
                cfgNameFocused = true;
                searchFocused = false;
                friendFocused = false;
                return;
            }
            cfgNameFocused = false;
            float w3 = (csw - 8f) / 3f;
            for (int b = 0; b < 3; b++) {
                if (inRect(wx, wy, csx + b * (w3 + 4f) - curX, cfgBtnY() - curY, w3, 13f)) {
                    cfgAct(b); // 0=Save 1=Load 2=Delete
                    return;
                }
            }
            float w2 = (csw - 4f) / 2f;
            for (int b = 0; b < 2; b++) {
                if (inRect(wx, wy, csx + b * (w2 + 4f) - curX, cfgBtn2Y() - curY, w2, 13f)) {
                    cfgAct(3 + b); // 3=Reload 4=Open dir
                    return;
                }
            }
            float viewBot = curY + curH - 6f;
            for (int i = 0; i < cfgList.size(); i++) {
                float ry = cfgListY() + i * 15f - scrollCur;
                if (ry + 13f <= cfgListY() || ry >= viewBot) continue;
                if (inRect(wx, wy, csx - curX, ry - curY, csw, 13f)) {
                    cfgSel = i;
                    cfgName.setLength(0);
                    cfgName.append((String) cfgList.get(i));
                    Log.info("Config", "selected: " + cfgList.get(i));
                    return;
                }
            }
            return;
        }

        // вкладка Friends: поле ввода + список (свой хит-тест; мимо — дальше)
        if (selected != null && "Friends".equals(selected.name) && button == 0) {
            if (friendsClick(wx, wy)) return;
        }

        // элементы настроек выбранного модуля (кэш раскладки последнего кадра)
        if (selected != null && button == 0 && rowsValid) {
            float viewTop = headerY() + 22f + 4f;
            float viewBot = curY + curH - 4f;
            for (int i = 0; i < settingRows.size(); i++) {
                SRow r = (SRow) settingRows.get(i);
                if (!r.s.isVisible()) continue;
                float ay = r.y - scrollCur;
                if (ay + r.h <= viewTop || ay >= viewBot) continue; // за клипом скролла
                if (!inRect(wx, wy, panelX() + 7f - curX, ay - curY, panelW - 14f, r.h)) continue;
                if (handleSettingClick(r, wx, wy, ay)) return;
            }
        }

        if (selected != null
            && inRect(wx, wy, xmarkX() - curX, headerY() - 2f - curY, 16f, 16f)) {
            selected = null;
            return;
        }

        // чип настроек (низ рельсы) — вкладка CONFIG; повторный клик — выход
        if (inRect(wx, wy, chipX() - curX, setChipY() - curY, CHIP_W, CHIP_W)) {
            search.setLength(0);
            selected = null;
            if (configMode) {
                configMode = false;
                scrollTarget = 0f;
                scrollCur = 0f;
            } else {
                enterConfigMode();
            }
            return;
        }

        for (int i = 0; i < categories.size(); i++) {
            if (inRect(wx, wy, chipX() - curX, chipY(i) - curY, CHIP_W, CHIP_W)) {
                configMode = false;
                catIndex = i;
                search.setLength(0);
                ArrayList lst = (ArrayList) catModules.get(i);
                if (!lst.isEmpty()) selected = (Module) lst.get(0);
                return;
            }
        }

        ArrayList list = visibleModules();
        float modTop = curY + topH + 1f;
        float modBot = curY + curH - 4f;
        for (int i = 0; i < list.size(); i++) {
            float ry = rowY(i) - modScrollCur;
            if (ry + ROW_H <= modTop || ry >= modBot) continue; // вне клипа скролла
            if (inRect(wx, wy, rowX() - curX, ry - curY, rowW(), ROW_H)) {
                Module m = (Module) list.get(i);
                if (button == 0) {
                    m.toggle();
                } else if (button == 1) {
                    selected = m;
                    configMode = false;
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
        if (pickerOpen) {
            svDrag = false;
            hueDrag = false;
            pickerDrag = false;
        }
        if (sliderDrag != null) {
            sliderDrag = null;
            return;
        }
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
        } else if (hudDrag == 3) {
            utils.render.KeybindsWidget.setPos(mx - hudOffX, my - hudOffY);
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
        if (cfgNameFocused && key == KEY_BACKSPACE && cfgName.length() > 0) {
            cfgName.setLength(cfgName.length() - 1);
        }
        if (friendFocused && key == KEY_BACKSPACE && friendName.length() > 0) {
            friendName.setLength(friendName.length() - 1);
        }
        if (friendFocused && key == KEY_ENTER) {
            friendAddFromInput();
        }
        // Friends: Ctrl+C — скопировать выбранного, Ctrl+V — вставить из буфера
        if (selected != null && "Friends".equals(selected.name)) {
            boolean ctrl = ctrlDown();
            if (ctrl && key == 67) { // C
                friendCopySelected();
                return;
            }
            if (ctrl && key == 86) { // V
                friendPaste();
                return;
            }
        }
    }

    /**
     * handleMouseInput форка: вызывается на КАЖДОЕ событие очереди мыши
     * (while(Mouse.next())). super разбирает клики/драг; колесо он игнорирует —
     * читаем getEventDWheel напрямую из шима (rustme.IiiliilliI.lillilIIIl —
     * event-очередь, та же семантика, что у iliiIlIIIl()=getEventButton,
     * запись подтверждена дизасмом IlllilIIIl(int,bool,int)).
     */
    @Override
    public void lIIillIlil() {
        try {
            super.lIIillIlil();
        } catch (java.io.IOException ignore) {
            return;
        }
        try {
            int wheel = rustme.IiiliilliI.lillilIIIl();
            if (wheel != 0) {
                // колонка модулей — свой скролл
                if (mouseX >= rowX() && mouseX <= rowX() + rowW()
                    && mouseY >= curY && mouseY <= curY + curH) {
                    modScrollTarget -= wheel * 26f;
                } else if ((selected != null || (configMode && selected == null))
                    && mouseX >= panelX() && mouseX <= curX + curW
                    && mouseY >= curY && mouseY <= curY + curH) {
                    scrollTarget -= wheel * 26f;
                }
            }
        } catch (Throwable ignore) {
            // шим мыши недоступен — скролл просто не работает
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
        if (cfgNameFocused) {
            if (c >= 32 && c < 127 && cfgName.length() < 24) {
                if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.') {
                    cfgName.append(c);
                }
            }
            return;
        }
        if (friendFocused) {
            if (ctrlDown()) return; // Ctrl+C/V обрабатываются в iIIliIlliI
            if (friendName.length() < 16) {
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
                if (ok) friendName.append(c);
            }
            return;
        }
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
            s.drawPicker(ctx); // попап поверх всего (без scale-анимации)
            org.lwjglx.opengl.GL11.glColor4f(1f, 1f, 1f, 1f);
        } catch (Throwable tt) {
            Log.error("Menu", "renderOverlay failed", tt);
        }
    }

    // ===== зоны (геометрия Class215 1:1) =====

    private static float panelX() { return curX + railW + modW; }
    // рельса: колонка паддинг 3 (I_method_70a38517(3.0F)), чип по центру
    private static float chipX() { return curX + (railW - CHIP_W) * 0.5f; }
    // чипы: колонка категорий идёт ПОСЛЕ logo-блока с gap 9 (rock var8 gap 9)
    private static float chipY(int i) { return curY + topH + 9f + i * (CHIP_W + 3f); }
    // settings-чип: gap 9.0 от последнего чипа НЕ используется — rock прижимает
    // его низом окна с паддингом колонки (bottom 8): y = окно_низ - 8 - 17
    private static float setChipY() { return curY + curH - 8f - CHIP_W; }
    // мод-колонка: title pad(5,8,1,8), список pad(1,5,5,4) gap 2 → ряд x=rail+5
    private static float rowX() { return curX + railW + 5f; }
    private static float rowW() { return modW - 9f; }
    private static float rowY(int i) { return curY + topH + 1f + i * (ROW_H + ROW_GAP); }
    // панель: поиск в верхней строке topH, отступ слева 7
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

    /** Центрирование текста по вертикали: rock использует высоту клетки шрифта
     *  (fontHeight), не «size*1.2» — иначе текст визуально уползает вниз/вверх. */
    private static float vc(float boxH, float size) {
        return (boxH - CustomFont.cellHeight(size, CustomFont.HUD_FONT)) * 0.5f;
    }

    /** То же для medium-шрифта. */
    private static float vcM(float boxH, float size) {
        return (boxH - CustomFont.cellHeight(size, CustomFont.WM_FONT)) * 0.5f;
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
        lastAnimDt = dt;
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
            int bg = withAlpha(BG, 229);
            RenderUtil.drawRoundedRectShader(ctx, curX, curY, curW, curH, 12f, bg, bg, bg, bg, 0.25f);
        }
        int sep = withAlpha(OUTLINE, 89);
        RenderUtil.drawRoundedBorder(ctx, curX, curY, curW, curH, 12f, 0.5f, sep);
        RenderUtil.drawRect(ctx, curX + railW - 1f, curY + 1f, 1f, curH - 2f, sep);
        RenderUtil.drawRect(ctx, curX + railW + modW - 1f, curY + 1f, 1f, curH - 2f, sep);
        RenderUtil.drawRect(ctx, curX + railW + modW, curY + topH - 1f,
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
            int bg = mix(withAlpha(FLAT, 102), ACCENT, hov ? 0.025f : 0f);
            RenderUtil.drawRoundedRectShader(ctx, cx, cy, CHIP_W, CHIP_W, 4f, bg, bg, bg, bg, 0.25f);
            int ic = mix(WHITE, ACCENT, sel);
            ic = withAlpha(ic & 0xFFFFFF, (int) (255f * (0.62f + 0.38f * sel)));
            IconRender.drawIcon(categoryIcon(cat), cx + 4f, cy + 4f, 9f, ic);
        }

        // чип настроек (низ рельсы) — вкладка CONFIG
        float sx = chipX(), sy = setChipY();
        boolean hov = inRect(mouseX, mouseY, sx, sy, CHIP_W, CHIP_W);
        int bg = mix(withAlpha(FLAT, 102), ACCENT, (hov || configMode) ? 0.025f : 0f);
        RenderUtil.drawRoundedRectShader(ctx, sx, sy, CHIP_W, CHIP_W, 4f, bg, bg, bg, bg, 0.25f);
        int ic = configMode
            ? withAlpha(ACCENT & 0xFFFFFF, 255)
            : withAlpha(WHITE & 0xFFFFFF, (int) (255f * (0.58f + 0.35f * (hov ? 1f : 0f))));
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
        // title: pad(top5,left8,bottom1,right8), текст центрируется в оставшейся полосе
        float tBoxY = curY + 5f, tBoxH = topH - 5f - 1f;
        text(title, curX + railW + 8f, tBoxY + (tBoxH - CustomFont.cellHeight(9f, CustomFont.HUD_FONT)) * 0.5f, WHITE, 9f);

        ArrayList list = visibleModules();
        // сброс скролла списка при смене вкладки
        if (modScrollCat != catIndex) {
            modScrollCat = catIndex;
            modScrollTarget = 0f;
            modScrollCur = 0f;
        }
        float modTop = curY + topH + 1f;
        float modBot = curY + curH - 4f;
        float modViewH = Math.max(20f, modBot - modTop);
        float modMaxScroll = Math.max(0f, list.size() * (ROW_H + ROW_GAP) - modViewH);
        if (modScrollTarget > modMaxScroll) modScrollTarget = modMaxScroll;
        if (modScrollTarget < 0f) modScrollTarget = 0f;
        long modNow = System.currentTimeMillis();
        float modDt = lastScrollFrame == 0L ? 16f : Math.min(64f, modNow - lastScrollFrame);
        modScrollCur += (modScrollTarget - modScrollCur) * (1f - (float) Math.exp(-modDt / 90f));
        if (Math.abs(modScrollTarget - modScrollCur) < 0.05f) modScrollCur = modScrollTarget;
        scissorOn(ctx, rowX(), modTop - 2f, rowW(), modViewH + 4f);
        for (int i = 0; i < list.size(); i++) {
            Module m = (Module) list.get(i);
            float en = animFor(m)[0];
            float sel = m == selected ? 1f : 0f;
            float rx = rowX(), ry = rowY(i) - modScrollCur;
            if (ry + ROW_H < modTop - 24f || ry > modBot + 24f) continue;
            boolean hov = inRect(mouseX, mouseY, rx, ry, rowW(), ROW_H);
            int bg = mix(withAlpha(FLAT, 102), withAlpha(ACCENT, 102),
                0.08f * en + 0.018f * sel + (hov ? 0.025f : 0f));
            RenderUtil.drawRoundedRectShader(ctx, rx, ry, rowW(), ROW_H, ROW_R, bg, bg, bg, bg, 0.25f);
            int tc = mix(WHITE, ACCENT, 0.5f * en);
            tc = withAlpha(tc & 0xFFFFFF, (int) (255f * (0.6f + 0.3f * en + 0.1f * sel)));
            textM(m.name, rx + 5f, ry + vcM(ROW_H, 7f), tc, 7f);
        }
        scissorOff();
    }

    // ===== панель (rock II_method_18597158) =====

    private void drawPanel(GameContext ctx) {
        float fsSmall = 6f, fsRow = 7f;

        // --- верхняя строка: поиск + ник/LEEK + аватар ---
        float sx = searchX(), sy = searchY();
        boolean sHov = inRect(mouseX, mouseY, sx, sy, SEARCH_W, SEARCH_H);
        int fieldBg = mix(withAlpha(FLAT, 173), WHITE, 0.035f + (sHov || searchFocused ? 0.035f : 0f));
        RenderUtil.drawRoundedRectShader(ctx, sx, sy, SEARCH_W, SEARCH_H, 3f, fieldBg, fieldBg, fieldBg, fieldBg, 0.25f);
        IconRender.drawIcon("search", sx + 3.5f, sy + 3.5f, 5f, withAlpha(WHITE, 122));
        String q = search.toString();
        float textX = sx + 11f;
        int fieldTc = withAlpha(WHITE, 184);
        if (q.length() == 0 && !searchFocused) {
            textM("Search", textX, sy + vcM(SEARCH_H, fsSmall), fieldTc, fsSmall);
        } else {
            textM(q, textX, sy + vcM(SEARCH_H, fsSmall), fieldTc, fsSmall);
            if (searchFocused && (System.currentTimeMillis() / 400L) % 2L == 0L) {
                RenderUtil.drawRect(ctx, textX + textWM(q, fsSmall) + 1f,
                    sy + SEARCH_H * 0.25f, 1f, SEARCH_H * 0.5f, fieldTc);
            }
        }

        float avX = avatarX(), avY = curY + (topH - 12f) * 0.5f;
        // rock: круглый БЕЛЫЙ аватар-заглушка (drawRoundedTexture, radius=w/2,
        // цвет палитры[5]=white); рисуем белую кружку с мягким краем
        int avc = withAlpha(WHITE & 0xFFFFFF, 216);
        RenderUtil.drawRoundedRectShader(ctx, avX, avY, 12f, 12f, 6f,
            avc, avc, avc, avc, 0.3f);
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
            int kbg = mix(withAlpha(FLAT, 173), WHITE, capturing ? 0.07f : 0.035f + (kHov ? 0.035f : 0f));
            RenderUtil.drawRoundedRectShader(ctx, bindX(), bindY(), BIND_W, BIND_H, 3f, kbg, kbg, kbg, kbg, 0.25f);
            String bl = capturing ? "Press a key..."
                : (selected.bindKey != -1 ? "Key: " + keyName(selected.bindKey) : "Set bind");
            textM(bl, bindX() + 4f, bindY() + vcM(BIND_H, fsSmall), capturing ? WHITE : fieldTc, fsSmall);
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

            // контент: элементы настроек модуля + плавный скролл
            // (rock scroll-контейнер: клип scissor'ом, ease ~90мс к target)
            boolean friendsTab = "Friends".equals(selected.name);
            float contentY = hy + 22f + 2f;
            java.util.List sts = selected.settings();
            if (selected != selectedAtLayout) {
                // скролл настроек сохраняем по модулю и восстанавливаем
                if (selectedAtLayout != null) {
                    scrollStore.put(selectedAtLayout.name,
                        new float[]{scrollTarget, scrollCur});
                }
                selectedAtLayout = selected;
                float[] mem = selected != null
                    ? (float[]) scrollStore.get(selected.name) : null;
                if (mem != null) {
                    scrollTarget = mem[0];
                    scrollCur = mem[1];
                } else {
                    scrollTarget = 0f;
                    scrollCur = 0f;
                }
            }
            float viewTop = contentY + 2f;
            float viewBot = curY + curH - 4f;
            if (friendsTab) {
                // свой контент вкладки (скролл/клип внутри, rowsValid гасим —
                // иначе кэш чужих настроек ловил бы фантомные клики)
                settingRows.clear();
                rowsValid = false;
                drawFriendsPanel(ctx, hy, viewTop, viewBot);
            } else {
            float viewH = Math.max(20f, viewBot - viewTop);
            settingRows.clear();
            settingsContentH = layoutSettings(sts, settingRows, viewTop);
            rowsValid = true;
            float maxScroll = Math.max(0f, settingsContentH - viewH);
            if (scrollTarget > maxScroll) scrollTarget = maxScroll;
            if (scrollTarget < 0f) scrollTarget = 0f;
            long nowMs = System.currentTimeMillis();
            float sdt = lastScrollFrame == 0L ? 16f : Math.min(64f, nowMs - lastScrollFrame);
            lastScrollFrame = nowMs;
            scrollCur += (scrollTarget - scrollCur) * (1f - (float) Math.exp(-sdt / 90f));
            if (Math.abs(scrollTarget - scrollCur) < 0.05f) scrollCur = scrollTarget;

            boolean anyVisible = false;
            for (int i = 0; i < settingRows.size(); i++) {
                if (((SRow) settingRows.get(i)).s.isVisible()) { anyVisible = true; break; }
            }
            if (!anyVisible) {
                float contentH = viewBot - contentY;
                String ns = "No settings";
                float nsW = textWM(ns, 9f);
                textM(ns, panelX() + (panelW - nsW) * 0.5f,
                    contentY + Math.max(6f, contentH * 0.5f - 6f), withAlpha(WHITE, 140), 9f);
            } else {
                scissorOn(ctx, panelX() + 1f, viewTop - 2f, panelW - 2f, viewH + 4f);
                for (int i = 0; i < settingRows.size(); i++) {
                    SRow r = (SRow) settingRows.get(i);
                    if (!r.s.isVisible()) continue;
                    float ay = r.y - scrollCur;
                    if (ay + r.h < viewTop - 24f || ay > viewBot + 24f) continue;
                    drawSetting(ctx, r, ay, lastAnimDt);
                }
                scissorOff();
                }
            }
        } else if (configMode) {
            drawConfigPanel(ctx);
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

    // ===== вкладка CONFIG (конфиг-система, utils.etc.ConfigManager) =====

    // геометрия панели конфигов (единственный источник — и для рендера, и для кликов)
    private static float cfgFieldY() { return headerY() + 30f; }
    private static float cfgBtnY()  { return cfgFieldY() + 18f; }
    private static float cfgBtn2Y() { return cfgBtnY() + 18f; }
    private static float cfgListY() { return cfgBtn2Y() + 19f; }

    private void enterConfigMode() {
        configMode = true;
        search.setLength(0);
        searchFocused = false;
        selected = null;
        selectedAtLayout = null; // конфиг-скролл не должен перезаписать память настроек модуля
        cfgNameFocused = false;
        refreshConfigs();
    }

    private void refreshConfigs() {
        cfgList.clear();
        cfgSel = -1;
        ConfigManager.ensureDir();
        String[] names = ConfigManager.list();
        for (int i = 0; i < names.length; i++) cfgList.add(names[i]);
        scrollTarget = 0f;
        scrollCur = 0f;
    }

    private void cfgMsg(String s, boolean ok) {
        cfgMsg = s;
        cfgMsgOk = ok;
        cfgMsgAt = System.currentTimeMillis();
    }

    private void cfgSelectByName(String name) {
        cfgSel = -1;
        for (int i = 0; i < cfgList.size(); i++) {
            if (name.equals(cfgList.get(i))) {
                cfgSel = i;
                return;
            }
        }
    }

    /** Кнопки: 0=Save 1=Load 2=Delete 3=Reload 4=Open dir. GUI-поток —
     *  setState модулей безопасен (как обычный клик по ряду). */
    private void cfgAct(int id) {
        try {
            if (id == 3) { // Reload — перечитать папку
                refreshConfigs();
                cfgMsg("found " + cfgList.size() + " config(s)", true);
                Log.info("Config", "reload: " + cfgList.size() + " file(s)");
                return;
            }
            if (id == 4) { // Open dir
                ConfigManager.openDir();
                cfgMsg("folder opened", true);
                return;
            }
            String name = cfgName.toString().trim();
            if (name.length() == 0 && cfgSel >= 0 && cfgSel < cfgList.size()) {
                name = (String) cfgList.get(cfgSel);
            }
            name = ConfigManager.sanitize(name);
            if (id == 0) { // Save
                ConfigManager.save(name);
                refreshConfigs();
                cfgSelectByName(name);
                cfgName.setLength(0);
                cfgName.append(name);
                cfgMsg("saved: " + name, true);
            } else if (id == 1) { // Load
                int n = ConfigManager.load(name);
                refreshConfigs();
                cfgSelectByName(name);
                cfgName.setLength(0);
                cfgName.append(name);
                cfgMsg("loaded: " + name + " (" + n + " modules)", true);
            } else if (id == 2) { // Delete
                boolean ok = ConfigManager.delete(name);
                refreshConfigs();
                cfgMsg(ok ? "deleted: " + name : "not found: " + name, ok);
            }
        } catch (Throwable t) {
            Log.error("Config", "cfgAct " + id + " failed", t);
            String m = t.toString();
            if (m.length() > 44) m = m.substring(0, 44);
            cfgMsg(m, false);
        }
    }

    private void drawBtn(GameContext ctx, String label, float x, float y, float w, float h) {
        boolean hov = inRect(mouseX, mouseY, x, y, w, h);
        int bg = mix(withAlpha(FLAT, 173), ACCENT, hov ? 0.10f : 0f);
        RenderUtil.drawRoundedRectShader(ctx, x, y, w, h, 3f, bg, bg, bg, bg, 0.25f);
        float tw = textWM(label, 6f);
        textM(label, x + (w - tw) * 0.5f, y + vcM(h, 6f), withAlpha(WHITE, hov ? 255 : 190), 6f);
    }

    private void drawConfigPanel(GameContext ctx) {
        float fsSmall = 6f;
        float sx = panelX() + 7f;
        float sw = panelW - 14f;
        float cy = headerY() + 4f;
        long now = System.currentTimeMillis();

        CustomFont.drawGradientString("CONFIG", sx, cy, ACCENT, ACCENT2, 11f, CustomFont.HUD_FONT);

        // статус: свежее сообщение операции либо счётчик файлов
        if (cfgMsg != null && now - cfgMsgAt < 4000L) {
            int mc = cfgMsgOk ? 0xFF4DFF6E : 0xFFFF5040;
            textM(cfgMsg, sx, cy + 17f, withAlpha(mc & 0xFFFFFF, 235), fsSmall);
        } else {
            textM(cfgList.size() + " configs in folder", sx, cy + 17f,
                withAlpha(WHITE, 120), fsSmall);
        }

        // поле имени конфига
        float fy = cfgFieldY();
        boolean fHov = inRect(mouseX, mouseY, sx, fy, sw, 12f);
        int fieldBg = mix(withAlpha(FLAT, 173), WHITE, 0.035f + (fHov || cfgNameFocused ? 0.035f : 0f));
        RenderUtil.drawRoundedRectShader(ctx, sx, fy, sw, 12f, 3f, fieldBg, fieldBg, fieldBg, fieldBg, 0.25f);
        String nm = cfgName.toString();
        int fieldTc = withAlpha(WHITE, 184);
        if (nm.length() == 0 && !cfgNameFocused) {
            textM("Config name", sx + 4f, fy + vcM(12f, fsSmall), withAlpha(WHITE, 110), fsSmall);
        } else {
            textM(nm, sx + 4f, fy + vcM(12f, fsSmall), fieldTc, fsSmall);
            if (cfgNameFocused && (now / 400L) % 2L == 0L) {
                RenderUtil.drawRect(ctx, sx + 4f + textWM(nm, fsSmall) + 1f,
                    fy + 3f, 1f, 6f, fieldTc);
            }
        }

        // кнопки: ряд 1 Save/Load/Delete, ряд 2 Reload/Open dir
        float w3 = (sw - 8f) / 3f;
        drawBtn(ctx, "Save", sx, cfgBtnY(), w3, 13f);
        drawBtn(ctx, "Load", sx + w3 + 4f, cfgBtnY(), w3, 13f);
        drawBtn(ctx, "Delete", sx + 2f * (w3 + 4f), cfgBtnY(), w3, 13f);
        float w2 = (sw - 4f) / 2f;
        drawBtn(ctx, "Reload", sx, cfgBtn2Y(), w2, 13f);
        drawBtn(ctx, "Open dir", sx + w2 + 4f, cfgBtn2Y(), w2, 13f);

        // список конфигов (скролл через scrollTarget/scrollCur — они свободны)
        float ly = cfgListY();
        float viewBot = curY + curH - 6f;
        float maxScroll = Math.max(0f, cfgList.size() * 15f - (viewBot - ly));
        if (scrollTarget > maxScroll) scrollTarget = maxScroll;
        if (scrollTarget < 0f) scrollTarget = 0f;
        float sdt = lastScrollFrame == 0L ? 16f : Math.min(64f, now - lastScrollFrame);
        lastScrollFrame = now;
        scrollCur += (scrollTarget - scrollCur) * (1f - (float) Math.exp(-sdt / 90f));
        if (Math.abs(scrollTarget - scrollCur) < 0.05f) scrollCur = scrollTarget;

        if (cfgList.isEmpty()) {
            String ns = "No configs";
            float nsW = textWM(ns, 9f);
            textM(ns, panelX() + (panelW - nsW) * 0.5f,
                ly + Math.max(6f, (viewBot - ly) * 0.5f - 6f), withAlpha(WHITE, 140), 9f);
            return;
        }
        scissorOn(ctx, panelX() + 1f, ly - 2f, panelW - 2f, (viewBot - ly) + 4f);
        for (int i = 0; i < cfgList.size(); i++) {
            float ry = ly + i * 15f - scrollCur;
            if (ry + 13f < ly - 20f || ry > viewBot + 20f) continue;
            String n = (String) cfgList.get(i);
            boolean hov = inRect(mouseX, mouseY, sx, ry, sw, 13f);
            boolean sel = i == cfgSel;
            int bg = sel ? mix(withAlpha(ACCENT, 150), withAlpha(BG, 102), 0.2f)
                : mix(withAlpha(FLAT, 102), withAlpha(ACCENT, 102), hov ? 0.15f : 0f);
            RenderUtil.drawRoundedRectShader(ctx, sx, ry, sw, 13f, 3f, bg, bg, bg, bg, 0.25f);
            textM(n, sx + 5f, ry + vcM(13f, 7f),
                sel ? WHITE : withAlpha(WHITE, hov ? 210 : 150), 7f);
            if (sel) {
                RenderUtil.drawRect(ctx, sx, ry, 1.5f, 13f, ACCENT);
            }
        }
        scissorOff();
    }

    // ===== вкладка Friends (список друзей: ввод + добавление/удаление) =====

    private static float frFieldY(float contentY) { return contentY; }
    private static float frListY(float contentY) { return contentY + 34f; }

    /** Зажат ли Ctrl (L=341/R=345) — для copy/paste хоткеев. */
    private static boolean ctrlDown() {
        try {
            GameContext ctx = GameContext.get();
            return ctx.isKeyDown(341) || ctx.isKeyDown(345);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private void friendAddFromInput() {
        String n = friendName.toString().trim();
        if (utils.etc.Friends.add(n)) {
            friendMsg = "Added " + n.trim();
            friendMsgOk = true;
            friendName.setLength(0);
        } else {
            friendMsg = "Bad nick / already added";
            friendMsgOk = false;
        }
        friendMsgAt = System.currentTimeMillis();
    }

    /** Ctrl+C: ник выбранного друга в системный буфер обмена. */
    private void friendCopySelected() {
        try {
            String[] names = utils.etc.Friends.list();
            if (friendSel < 0 || friendSel >= names.length) {
                friendMsg = "Nothing selected";
                friendMsgOk = false;
                friendMsgAt = System.currentTimeMillis();
                return;
            }
            java.awt.datatransfer.StringSelection ss =
                new java.awt.datatransfer.StringSelection(names[friendSel]);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(ss, null);
            friendMsg = "Copied " + names[friendSel];
            friendMsgOk = true;
            friendMsgAt = System.currentTimeMillis();
            Log.info("Menu", "friend copied: " + names[friendSel]);
        } catch (Throwable t) {
            friendMsg = "Copy failed";
            friendMsgOk = false;
            friendMsgAt = System.currentTimeMillis();
        }
    }

    /** Ctrl+V: текст из буфера в поле ввода (только ник-символы, max 16). */
    private void friendPaste() {
        try {
            java.awt.datatransfer.Clipboard clip =
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            Object data = clip.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (!(data instanceof String)) {
                friendMsg = "Clipboard empty";
                friendMsgOk = false;
                friendMsgAt = System.currentTimeMillis();
                return;
            }
            String s = (String) data;
            int added = 0;
            for (int i = 0; i < s.length() && friendName.length() < 16; i++) {
                char c = s.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
                if (ok) {
                    friendName.append(c);
                    added++;
                }
            }
            friendFocused = true;
            searchFocused = false;
            cfgNameFocused = false;
            if (added == 0) {
                friendMsg = "Clipboard empty";
                friendMsgOk = false;
            } else {
                friendMsg = null;
            }
            friendMsgAt = System.currentTimeMillis();
        } catch (Throwable t) {
            friendMsg = "Paste failed";
            friendMsgOk = false;
            friendMsgAt = System.currentTimeMillis();
        }
    }

    /** Хит-тест панели друзей. true = клик обработан. */
    private boolean friendsClick(float wx, float wy) {
        float sx = panelX() + 7f;
        float sw = panelW - 14f;
        float contentY = headerY() + 22f + 2f + 2f;
        float fy = frFieldY(contentY);
        float fw = sw - 50f;
        if (inRect(wx, wy, sx - curX, fy - curY, fw, 13f)) {
            friendFocused = true;
            searchFocused = false;
            cfgNameFocused = false;
            return true;
        }
        if (inRect(wx, wy, sx + sw - 46f - curX, fy - curY, 46f, 13f)) {
            friendFocused = true;
            friendAddFromInput();
            return true;
        }
        String[] names = utils.etc.Friends.list();
        float ly = frListY(contentY);
        float viewBot = curY + curH - 4f;
        for (int i = 0; i < names.length; i++) {
            float ry = ly + i * 15f - scrollCur;
            if (ry + 13f < ly - 20f || ry > viewBot + 20f) continue;
            // крестик удаления
            if (inRect(wx, wy, sx + sw - 13f - curX, ry - curY, 13f, 13f)) {
                if (utils.etc.Friends.remove(names[i])) {
                    friendMsg = "Removed " + names[i];
                    friendMsgOk = true;
                    friendMsgAt = System.currentTimeMillis();
                    if (friendSel == i) friendSel = -1;
                    else if (friendSel > i) friendSel--;
                }
                return true;
            }
            if (inRect(wx, wy, sx - curX, ry - curY, sw, 13f)) {
                friendSel = i; // выбор для Ctrl+C
                return true;
            }
        }
        return false;
    }

    /** Контент вкладки: поле + Add + счётчик + скроллящийся список. */
    private void drawFriendsPanel(GameContext ctx, float hy, float viewTop, float viewBot) {
        float fsSmall = 6f;
        float sx = panelX() + 7f;
        float sw = panelW - 14f;
        float contentY = hy + 22f + 2f + 2f;
        long now = System.currentTimeMillis();

        // поле ввода
        float fy = frFieldY(contentY);
        float fw = sw - 50f;
        boolean fHov = inRect(mouseX, mouseY, sx, fy, fw, 13f);
        int fieldBg = mix(withAlpha(FLAT, 173), WHITE, 0.035f + (fHov || friendFocused ? 0.035f : 0f));
        RenderUtil.drawRoundedRectShader(ctx, sx, fy, fw, 13f, 3f, fieldBg, fieldBg, fieldBg, fieldBg, 0.25f);
        String nm = friendName.toString();
        int fieldTc = withAlpha(WHITE, 184);
        if (nm.length() == 0 && !friendFocused) {
            textM("Player nickname", sx + 4f, fy + vcM(13f, fsSmall), withAlpha(WHITE, 110), fsSmall);
        } else {
            textM(nm, sx + 4f, fy + vcM(13f, fsSmall), fieldTc, fsSmall);
            if (friendFocused && (now / 400L) % 2L == 0L) {
                RenderUtil.drawRect(ctx, sx + 4f + textWM(nm, fsSmall) + 1f,
                    fy + 3.5f, 1f, 6f, fieldTc);
            }
        }
        drawBtn(ctx, "Add", sx + sw - 46f, fy, 46f, 13f);

        // статус: свежее сообщение либо счётчик
        if (friendMsg != null && now - friendMsgAt < 4000L) {
            int mc = friendMsgOk ? 0xFF4DFF6E : 0xFFFF5040;
            textM(friendMsg, sx, fy + 16f, withAlpha(mc & 0xFFFFFF, 235), fsSmall);
        } else {
            int n = utils.etc.Friends.count();
            textM(n + (n == 1 ? " friend" : " friends"),
                sx, fy + 16f, withAlpha(WHITE, 120), fsSmall);
        }

        // список (скролл через общие scrollTarget/scrollCur)
        String[] names = utils.etc.Friends.list();
        float ly = frListY(contentY);
        float maxScroll = Math.max(0f, names.length * 15f - (viewBot - ly));
        if (scrollTarget > maxScroll) scrollTarget = maxScroll;
        if (scrollTarget < 0f) scrollTarget = 0f;
        float sdt = lastScrollFrame == 0L ? 16f : Math.min(64f, now - lastScrollFrame);
        lastScrollFrame = now;
        scrollCur += (scrollTarget - scrollCur) * (1f - (float) Math.exp(-sdt / 90f));
        if (Math.abs(scrollTarget - scrollCur) < 0.05f) scrollCur = scrollTarget;

        if (names.length == 0) {
            String ns = "No friends";
            float nsW = textWM(ns, 9f);
            textM(ns, panelX() + (panelW - nsW) * 0.5f,
                ly + Math.max(6f, (viewBot - ly) * 0.5f - 6f), withAlpha(WHITE, 140), 9f);
            return;
        }
        scissorOn(ctx, panelX() + 1f, ly - 2f, panelW - 2f, (viewBot - ly) + 4f);
        for (int i = 0; i < names.length; i++) {
            float ry = ly + i * 15f - scrollCur;
            if (ry + 13f < ly - 20f || ry > viewBot + 20f) continue;
            boolean hov = inRect(mouseX, mouseY, sx, ry, sw, 13f);
            boolean sel = i == friendSel;
            int bg = sel ? mix(withAlpha(ACCENT, 150), withAlpha(BG, 102), 0.2f)
                : mix(withAlpha(FLAT, 102), withAlpha(ACCENT, 102), hov ? 0.15f : 0f);
            RenderUtil.drawRoundedRectShader(ctx, sx, ry, sw, 13f, 3f, bg, bg, bg, bg, 0.25f);
            textM(names[i], sx + 5f, ry + vcM(13f, 7f),
                sel ? WHITE : withAlpha(WHITE, hov ? 210 : 150), 7f);
            if (sel) {
                RenderUtil.drawRect(ctx, sx, ry, 1.5f, 13f, ACCENT);
            }
            float xx = sx + sw - 13f;
            boolean xhov = inRect(mouseX, mouseY, xx, ry, 13f, 13f);
            textM("x", xx + 4f, ry + vcM(13f, 7f),
                xhov ? 0xFFFF5040 : withAlpha(WHITE, 130), 7f);
        }
        scissorOff();
    }

    // ===== ColorPicker (rockstar iIii_Class12, 143x136, 1:1) =====

    private static float[] rgbToHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h = 0f;
        if (d > 1e-6f) {
            if (max == r) h = ((g - b) / d) % 6f;
            else if (max == g) h = (b - r) / d + 2f;
            else h = (r - g) / d + 4f;
            h /= 6f;
            if (h < 0f) h += 1f;
        }
        float s = max <= 1e-6f ? 0f : d / max;
        return new float[]{h, s, max};
    }

    private static int hsvToRgb(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        int i = (int) (h * 6f);
        float f = h * 6f - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        float r, g, b;
        int k = i % 6;
        if (k == 0) { r = v; g = t; b = p; }
        else if (k == 1) { r = q; g = v; b = p; }
        else if (k == 2) { r = p; g = v; b = t; }
        else if (k == 3) { r = p; g = q; b = v; }
        else if (k == 4) { r = t; g = p; b = v; }
        else { r = v; g = p; b = q; }
        return 0xFF000000 | (((int) (r * 255f + 0.5f)) << 16)
            | (((int) (g * 255f + 0.5f)) << 8) | (int) (b * 255f + 0.5f);
    }

    private void openPicker(Module.ColorSetting cs) {
        pickerTarget = cs;
        pickerTitle = cs.name;
        float[] hsv = rgbToHsv(cs.argb);
        pHue = hsv[0];
        pSat = hsv[1];
        pBright = hsv[2];
        pickerX = mouseX - PICKER_W * 0.5f;
        pickerY = mouseY - 30f;
        clampPicker();
        svDrag = false;
        hueDrag = false;
        pickerDrag = false;
        ensurePresets();
        pickerOpen = true;
        Log.info("Menu", "color picker open: " + cs.name);
    }

    private void closePicker() {
        pickerOpen = false;
        pickerTarget = null;
        svDrag = false;
        hueDrag = false;
        pickerDrag = false;
    }

    /** Живая запись выбранного цвета в настройку (как consumer у rock). */
    private void applyPicker() {
        if (pickerTarget != null) {
            pickerTarget.argb = hsvToRgb(pHue, pSat, pBright);
        }
    }

    private void clampPicker() {
        if (pickerX + PICKER_W + 5f > liilIilil) pickerX = liilIilil - PICKER_W - 5f;
        if (pickerY + PICKER_H + 5f > iiilIilil) pickerY = iiilIilil - PICKER_H - 5f;
        if (pickerX < 5f) pickerX = 5f;
        if (pickerY < 5f) pickerY = 5f;
    }

    private void ensurePresets() {
        if (presetsLoaded) return;
        presetsLoaded = true;
        pickerPresets.clear();
        int[] saved = ConfigManager.loadPresets();
        if (saved != null && saved.length > 0) {
            for (int i = 0; i < saved.length; i++) {
                pickerPresets.add(Integer.valueOf(saved[i]));
            }
        } else {
            // дефолты rockstar (Class149.I_field_7865b31)
            pickerPresets.add(Integer.valueOf(0xFF007AFF));
            pickerPresets.add(Integer.valueOf(0xFF34C759));
            pickerPresets.add(Integer.valueOf(0xFFFFCC00));
            pickerPresets.add(Integer.valueOf(0xFFFF3B30));
            pickerPresets.add(Integer.valueOf(0xFF9747FF));
        }
    }

    private void savePickerPresets() {
        int[] arr = new int[pickerPresets.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = ((Integer) pickerPresets.get(i)).intValue();
        }
        ConfigManager.savePresets(arr);
    }

    private void updateSv(float px, float py) {
        float tX = (px - 6f) / 114f;
        float tY = (py - 20f) / 70f;
        if (tX < 0f) tX = 0f;
        if (tX > 1f) tX = 1f;
        if (tY < 0f) tY = 0f;
        if (tY > 1f) tY = 1f;
        pSat = tX;
        pBright = 1f - tY;
        applyPicker();
    }

    private void updateHue(float py) {
        float t = (py - 22f) / 66f; // как у rock: маркер 22+64*hue, drag 66px
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        pHue = t;
        applyPicker();
    }

    /** Хит-тест пресетов/«+»: -1 мимо, <size пресет, size = плюс. */
    private int presetHit(float px, float py) {
        ensurePresets();
        float f = 0f, f2 = 0f;
        for (int i = 0; i < pickerPresets.size(); i++) {
            if (inRect(px, py, 45f + f, PICKER_H - 36f + f2, 11f, 11f)) return i;
            f += 20f;
            if (45f + f > PICKER_W) { f = 0f; f2 += 18f; }
        }
        if (pickerPresets.size() < 10
            && inRect(px, py, 45f + f, PICKER_H - 36f + f2, 11f, 11f)) {
            return pickerPresets.size();
        }
        return -1;
    }

    private void drawPicker(GameContext ctx) {
        if (!pickerOpen) return;
        float x = pickerX, y = pickerY;

        // окно r7 (тень у нас нет — рамка как у окна меню)
        int wbg = withAlpha(BG, 229);
        RenderUtil.drawRoundedRectShader(ctx, x, y, PICKER_W, PICKER_H, 7f,
            wbg, wbg, wbg, wbg, 0.25f);
        RenderUtil.drawRoundedBorder(ctx, x, y, PICKER_W, PICKER_H, 7f, 0.5f,
            withAlpha(OUTLINE, 89));

        float tw = textW(pickerTitle, 7f);
        text(pickerTitle, x + (PICKER_W - tw) * 0.5f, y + 7f, WHITE, 7f);

        // xmark (10x10 r5)
        boolean xHov = inRect(mouseX, mouseY, x + PICKER_W - 15f, y + 5f, 10f, 10f);
        int xb = withAlpha(FLAT, xHov ? 220 : 173);
        RenderUtil.drawRoundedRectShader(ctx, x + PICKER_W - 15f, y + 5f, 10f, 10f, 5f,
            xb, xb, xb, xb, 0.25f);
        IconRender.drawIcon("xmark", x + PICKER_W - 15f, y + 5f, 10f, WHITE);

        // SV-бокс 114x70 r4: TL=white(s0,v1) TR=hue(s1,v1) BL/BR=black(v0)
        int hueFull = hsvToRgb(pHue, 1f, 1f);
        RenderUtil.drawRoundedRectShader(ctx, x + 6f, y + 20f, 114f, 70f, 4f,
            0xFFFFFFFF, hueFull, 0xFF000000, 0xFF000000, 0.25f);
        int cur = hsvToRgb(pHue, pSat, pBright);
        float cxs = x + 6f + 114f * pSat;
        float cys = y + 20f + 70f * (1f - pBright);
        RenderUtil.drawRoundedRectShader(ctx, cxs - 3.5f, cys - 3.5f, 7f, 7f, 2.5f,
            WHITE, WHITE, WHITE, WHITE, 0.25f);
        RenderUtil.drawRoundedRectShader(ctx, cxs - 2.5f, cys - 2.5f, 5f, 5f, 1.5f,
            cur, cur, cur, cur, 0.25f);

        // hue-бар 12x70 справа: 6 сегментов радуги + рамка r4 + маркер 8x2
        float hx = x + PICKER_W - 18f, hy = y + 20f;
        float seg = 70f / 6f;
        for (int i = 0; i < 6; i++) {
            RenderUtil.drawRect(ctx, hx, hy + seg * i, 12f, seg + 0.5f,
                hsvToRgb(i / 6f, 1f, 1f));
        }
        RenderUtil.drawRoundedBorder(ctx, hx, hy, 12f, 70f, 4f, 0.5f, withAlpha(OUTLINE, 89));
        RenderUtil.drawRect(ctx, hx + 2f, y + 22f + 64f * pHue, 8f, 2f, WHITE);

        // текущий цвет 29x29 r5 (низ-лево)
        RenderUtil.drawRoundedRectShader(ctx, x + 6f, y + PICKER_H - 36f, 29f, 29f, 5f,
            cur, cur, cur, cur, 0.25f);

        // пресеты 11x11 r4.5 шаг 20 (перенос), затем "+"
        ensurePresets();
        float f = 0f, f2 = 0f;
        int curRgb = cur & 0xFFFFFF;
        for (int i = 0; i < pickerPresets.size(); i++) {
            int c = ((Integer) pickerPresets.get(i)).intValue();
            float sxp = x + 45f + f, syp = y + PICKER_H - 36f + f2;
            boolean hov = inRect(mouseX, mouseY, sxp, syp, 11f, 11f);
            RenderUtil.drawRoundedRectShader(ctx, sxp, syp, 11f, 11f, 4.5f,
                c, c, c, c, 0.25f);
            boolean sel = (c & 0xFFFFFF) == curRgb;
            if (sel || hov) {
                RenderUtil.drawRoundedBorder(ctx, sxp - 1f, syp - 1f, 13f, 13f, 5.5f, 0.5f,
                    sel ? WHITE : withAlpha(WHITE, 150));
            }
            f += 20f;
            if (45f + f > PICKER_W) { f = 0f; f2 += 18f; }
        }
        if (pickerPresets.size() < 10) {
            float sxp = x + 45f + f, syp = y + PICKER_H - 36f + f2;
            int pb = withAlpha(FLAT, 173);
            RenderUtil.drawRoundedRectShader(ctx, sxp, syp, 11f, 11f, 4.5f, pb, pb, pb, pb, 0.25f);
            RenderUtil.drawRect(ctx, sxp + 3.5f, syp + 5f, 4f, 1f, WHITE);
            RenderUtil.drawRect(ctx, sxp + 5f, syp + 3.5f, 1f, 4f, WHITE);
        }
    }

    // ===== элементы настроек (rock createComponent 1:1) =====

    /** Раскладка списка настроек; возвращает высоту контента.
     *  Скрытые (visibleWhen) НЕ занимают места — список «расхлопывается». */
    private float layoutSettings(java.util.List sts, ArrayList out, float top) {
        float y = top;
        float sx = panelX() + 7f;
        float sw = panelW - 14f;
        float chipH = CustomFont.cellHeight(7f, CustomFont.WM_FONT) + 6f;
        for (int i = 0; i < sts.size(); i++) {
            Object o = sts.get(i);
            if (o instanceof Module.Setting && !((Module.Setting) o).isVisible()) continue;
            if (o instanceof Module.SectionSetting) {
                float h = 10f + CustomFont.cellHeight(9f, CustomFont.HUD_FONT) + 5f;
                out.add(new SRow((Module.Setting) o, 0, y, h, 0f));
                y += h;
            } else if (o instanceof Module.BoolSetting) {
                out.add(new SRow((Module.Setting) o, 1, y, 18f, 0f));
                y += 18f;
            } else if (o instanceof Module.ModeSetting) {
                Module.ModeSetting ms = (Module.ModeSetting) o;
                SRow r = new SRow(ms, 3, y, 0f, chipH);
                float bottom = wrapChips(r, ms.modes, null, sx, sw, y + 15f, chipH, 2f);
                r.h = bottom + 5f - y;
                out.add(r);
                y += r.h;
            } else if (o instanceof Module.MultiSetting) {
                Module.MultiSetting ms = (Module.MultiSetting) o;
                SRow r = new SRow(ms, 4, y, 0f, chipH);
                float bottom = wrapChips(r, ms.options, null, sx, sw, y + 15f, chipH, 2f);
                r.h = bottom + 5f - y;
                out.add(r);
                y += r.h;
            } else if (o instanceof Module.ColorSetting) {
                Module.ColorSetting cs = (Module.ColorSetting) o;
                if (cs.picker) {
                    // ColorPicker-строка (rock ColorSetting): label + чип 10x10
                    out.add(new SRow(cs, 6, y, 18f, 0f));
                    y += 18f;
                } else {
                    SRow r = new SRow(cs, 5, y, 0f, chipH);
                    float bottom = wrapChips(r, null, Module.COLOR_SWATCHES, sx, sw, y + 15f, chipH, 2f);
                    r.h = bottom + 5f - y;
                    out.add(r);
                    y += r.h;
                }
            } else if (o instanceof Module.FloatSetting) {
                out.add(new SRow((Module.Setting) o, 2, y, 32f, 0f));
                y += 32f;
            }
        }
        return y - top;
    }

    /** Чипы с переносом по ширине колонки; возвращает нижний край (content-space). */
    private float wrapChips(SRow r, String[] names, int[] argbs,
                            float sx, float sw, float y0, float chipH, float gap) {
        float x = sx, y = y0;
        int n = names != null ? names.length : argbs.length;
        for (int j = 0; j < n; j++) {
            float w = names != null ? textWM(names[j], 7f) + 6f : chipH;
            if (x > sx && x + w > sx + sw + 0.01f) {
                x = sx;
                y += chipH + gap;
            }
            r.cx.add(Float.valueOf(x));
            r.cy.add(Float.valueOf(y));
            r.cwd.add(Float.valueOf(w));
            r.cdata.add(Integer.valueOf(names != null ? j : argbs[j]));
            x += w + gap;
        }
        return y + chipH;
    }

    private void drawSetting(GameContext ctx, SRow r, float ay, float dt) {
        float sx = panelX() + 7f;
        float sw = panelW - 14f;
        if (r.kind == 0) {
            // SectionSetting: pad(10,?,5), текст 9px sf_semibold white a0.9
            text(r.s.name, sx + 6f, ay + 10f, withAlpha(WHITE, 230), 9f);
            return;
        }
        if (r.kind == 1) {
            // чекбокс: ряд h18, текст 8px (alpha 0.75+0.25 hover), pill 13x8 r3.5,
            // knob 5x5 inset 1.5, ON=акцент OFF=bg@a102, анимация 300мс
            Module.BoolSetting bs = (Module.BoolSetting) r.s;
            boolean hov = inRect(mouseX, mouseY, sx, ay, sw, 18f);
            float[] a = animOf(r.s);
            stepLin(a, 0, bs.get() ? 1f : 0f, dt / 300f);
            text(r.s.name, sx + 5f, ay + vc(18f, 8f), withAlpha(WHITE, hov ? 255 : 191), 8f);
            float px = sx + sw - 5f - 13f;
            float py = ay + 5f;
            int fill = mix(withAlpha(BG, 102), ACCENT, a[0]);
            RenderUtil.drawRoundedRectShader(ctx, px, py, 13f, 8f, 3.5f, fill, fill, fill, fill, 0.25f);
            float kx = px + 1.5f + 5f * a[0];
            RenderUtil.drawRoundedRectShader(ctx, kx, py + 1.5f, 5f, 5f, 2f,
                WHITE, WHITE, WHITE, WHITE, 0.25f);
            return;
        }
        if (r.kind == 2) {
            // слайдер: label 8px + значение справа 7px, track h3, knob r3
            // (кольцо акцент 1.5px + тёмный центр), value-анимация 300мс
            Module.FloatSetting fs = (Module.FloatSetting) r.s;
            boolean hov = inRect(mouseX, mouseY, sx, ay, sw, r.h);
            float[] a = animOf(r.s);
            a[0] += (fs.value - a[0]) * clamp01(dt / 300f);
            text(r.s.name, sx + 6f, ay + 3f, withAlpha(WHITE, 190), 8f);
            String vs = fmtVal(fs);
            textM(vs, sx + sw - 6f - textWM(vs, 7f), ay + 4f, withAlpha(WHITE, 210), 7f);
            float ty = ay + 20f;
            float t = clamp01(fs.max > fs.min ? (a[0] - fs.min) / (fs.max - fs.min) : 0f);
            int track = withAlpha(BG, 102);
            RenderUtil.drawRoundedRectShader(ctx, sx, ty, sw, 3f, 1.5f, track, track, track, track, 0.25f);
            if (t > 0.003f) {
                int fa = withAlpha(ACCENT & 0xFFFFFF, hov ? 191 : 255);
                RenderUtil.drawRoundedRectShader(ctx, sx, ty, Math.max(3f, sw * t), 3f, 1.5f,
                    fa, fa, fa, fa, 0.25f);
            }
            float kx = sx + sw * t;
            float ky = ty + 1.5f;
            RenderUtil.drawRoundedBorder(ctx, kx - 3f, ky - 3f, 6f, 6f, 3f, 1.5f, ACCENT);
            RenderUtil.drawRoundedRectShader(ctx, kx - 1.5f, ky - 1.5f, 3f, 3f, 1.5f,
                track, track, track, track, 0.25f);
            return;
        }
        if (r.kind == 3 || r.kind == 4) {
            // чипы режимов/мультибокса: текст 7px, pad 3, r2.5, gap 2,
            // bg: dark→акцент 0.2*hover; выбран: акцент→dark 0.2*hover; анимация 220мс
            Module.ModeSetting mode = r.kind == 3 ? (Module.ModeSetting) r.s : null;
            Module.MultiSetting multi = r.kind == 4 ? (Module.MultiSetting) r.s : null;
            if (multi != null) {
                String cnt = multi.count() + " of " + multi.options.length;
                textM(cnt, sx + sw - 6f - textWM(cnt, 7f), ay + 3f, withAlpha(WHITE, 133), 7f);
            }
            text(r.s.name, sx + 6f, ay + 2f, withAlpha(WHITE, 190), 8f);
            float[] aa = animArr(r.s, r.cdata.size());
            int dark = withAlpha(BG, 102);
            for (int j = 0; j < r.cdata.size(); j++) {
                float cxv = ((Float) r.cx.get(j)).floatValue();
                float cyv = ((Float) r.cy.get(j)).floatValue() - scrollCur;
                float cwv = ((Float) r.cwd.get(j)).floatValue();
                boolean sel = mode != null ? mode.index() == j : multi.isSelected(j);
                boolean hov = inRect(mouseX, mouseY, cxv, cyv, cwv, r.chipH);
                stepLin(aa, j, sel ? 1f : 0f, dt / 220f);
                int base = mix(dark, ACCENT, 0.2f * (hov ? 1f : 0f));
                int bg = base;
                if (sel) {
                    int selBg = mix(ACCENT, dark, 0.2f * (hov ? 1f : 0f));
                    bg = mix(base, selBg, aa[j]);
                }
                RenderUtil.drawRoundedRectShader(ctx, cxv, cyv, cwv, r.chipH, 2.5f,
                    bg, bg, bg, bg, 0.25f);
                String label = mode != null ? mode.modes[j] : multi.options[j];
                int ta = (int) (255f * (0.75f + 0.25f * aa[j]));
                textM(label, cxv + 3f, cyv + 3f, withAlpha(WHITE, ta), 7f);
            }
            return;
        }
        if (r.kind == 6) {
            // ColorPicker-строка (rock ColorSetting): текст 8px + чип 10x10 r3
            Module.ColorSetting cs = (Module.ColorSetting) r.s;
            boolean hov = inRect(mouseX, mouseY, sx, ay, sw, 18f);
            text(r.s.name, sx + 5f, ay + vc(18f, 8f), withAlpha(WHITE, hov ? 255 : 191), 8f);
            RenderUtil.drawRoundedRectShader(ctx, sx + sw - 5f - 10f, ay + 4f, 10f, 10f, 3f,
                cs.argb, cs.argb, cs.argb, cs.argb, 0.25f);
            return;
        }
        if (r.kind == 5) {
            // цвет: ряд свотчей 11x11 r3, выбранный — кольцо акцента
            text(r.s.name, sx + 6f, ay + 2f, withAlpha(WHITE, 190), 8f);
            Module.ColorSetting cs = (Module.ColorSetting) r.s;
            for (int j = 0; j < r.cdata.size(); j++) {
                int argb = ((Integer) r.cdata.get(j)).intValue();
                float cxv = ((Float) r.cx.get(j)).floatValue() + 3f;
                float cyv = ((Float) r.cy.get(j)).floatValue() + 3f - scrollCur;
                boolean sel = cs.argb == argb;
                boolean hov = inRect(mouseX, mouseY, cxv - 3f, cyv - 3f, r.chipH, r.chipH);
                RenderUtil.drawRoundedRectShader(ctx, cxv, cyv, 11f, 11f, 3f,
                    argb, argb, argb, argb, 0.25f);
                if (sel) {
                    RenderUtil.drawRoundedBorder(ctx, cxv - 1.5f, cyv - 1.5f, 14f, 14f,
                        4.5f, 1f, hov ? WHITE : ACCENT);
                }
            }
        }
    }

    private float[] animOf(Object key) {
        float[] a = (float[]) settingAnims.get(key);
        if (a == null) {
            a = new float[1];
            settingAnims.put(key, a);
        }
        return a;
    }

    private float[] animArr(Object key, int n) {
        float[] a = (float[]) settingAnims.get(key);
        if (a == null || a.length != n) {
            a = new float[n];
            settingAnims.put(key, a);
        }
        return a;
    }

    /** Линейный шаг 0..1 за фиксированную длительность (rock sig-анимации). */
    private static void stepLin(float[] a, int idx, float target, float k) {
        if (k > 1f) k = 1f;
        if (k < 0f) k = 0f;
        if (a[idx] < target) a[idx] = Math.min(target, a[idx] + k);
        else if (a[idx] > target) a[idx] = Math.max(target, a[idx] - k);
    }

    private static float clamp01(float f) {
        return f < 0f ? 0f : (f > 1f ? 1f : f);
    }

    private static String fmtVal(Module.FloatSetting fs) {
        if (fs.step >= 1f) return String.valueOf(Math.round(fs.value));
        if (fs.step >= 0.1f) return String.format("%.1f", Float.valueOf(fs.value));
        return String.format("%.2f", Float.valueOf(fs.value));
    }

    /** Scissor в физических пикселях (RenderUtil.scissorStart считает scale
     *  грубо — тут guiScale точный). */
    private static void scissorOn(GameContext ctx, float x, float y, float w, float h) {
        int scale = Math.max(1, Math.round(GuiScale.get(ctx)));
        int fbH = ctx.fbHeight > 0 ? ctx.fbHeight : (int) (ctx.scaledHeight * scale);
        org.lwjglx.opengl.GL11.glEnable(org.lwjglx.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjglx.opengl.GL11.glScissor(Math.round(x * scale),
            Math.round(fbH - (y + h) * scale),
            Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
    }

    private static void scissorOff() {
        org.lwjglx.opengl.GL11.glDisable(org.lwjglx.opengl.GL11.GL_SCISSOR_TEST);
    }

    // ===== утилиты =====

    private static String keyName(int key) {
        if (key < 0) return "-";
        if (key == 0) return "MOUSE1";
        if (key == 1) return "MOUSE2";
        if (key == 2) return "MOUSE3";
        if (key == 3) return "MOUSE4";
        if (key == 4) return "MOUSE5";
        if (key == 5) return "MOUSE6";
        if (key == 6) return "MOUSE7";
        if (key == 7) return "MOUSE8";
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
