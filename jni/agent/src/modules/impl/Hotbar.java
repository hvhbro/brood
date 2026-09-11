package modules.impl;

import modules.api.Module;
import rustme.liIIIIIIiI;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.CustomFont;
import utils.render.ItemIcons;
import utils.render.RenderUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Hotbar — HUD-хотбар 1:1 rockstar (IiIiIiIIi_Class170 "hud.custom_hotbar"),
 * адаптирован под Rust-форк: 6 слотов (их хотбар = первые 6 main-списка).
 *
 * МЕТРИКИ rockstar 1:1 (на 6 слотов: панель 135.5×27, паддинги 6/6 как их 200):
 *   панель: x = W/2-w/2, y = H-29, углы (TL,TR,BR,BL) = (1,0,7,8),
 *   фон client-bg (24,21,29,229.5), БЕЗ рамки;
 *   слоты: x+6 + i*21.5, предмет 16×16 на y+5.5, масштаб 1+lift вокруг центра
 *   (lift = 0.25*selAnim + 0.1*nearAnim, 300мс);
 *   пустые слоты — номер 10px (белый, alpha 0.5+1.5*lift), счётчик — 8px
 *   (правый край slotX+17, y+9, тень black@0.4 +0.5/+0.5), условие count != 1;
 *   полоска прочности 13×1.5 на (x+2, y+13), r=0.25, bg white@0.25,
 *   fill = акцент сплошным (их i_method_5dfec6e = акцент темы);
 *   точка выбора 2×2 (белый, alpha=selAnim) на (x+7.5, y+21-anim).
 *   Оставлено форк-специфичное: патроны вместо count у оружия (1:1 с виджетом
 *   мода), 6 слотов. Нет источника: кулдаун-оверлей, XP-уровень, offhand.
 *
 * ЦЕПОЧКА (javap-верифицировано 09-10, исправлено 09-11):
 *   player.iIliiIiII → InventoryPlayer (iiIIIiIIiI);
 *   main-список = lllliIiII (NonNullList<Stack>, AbstractList), selected = iliiIIiII:I
 *   (getCurrentItem lIiIIIiilI читает iliiIIiII; iIiiIIiII — мусор);
 *   stack.lIIIIililI() = getCount (ВАЖНО: lIiiIililI = getMaxStackSize по NBT
 *   max_stack/maxStack — раньше его показывали как количество: ресурсы 1000,
 *   пустые слоты 32767); stack.isEmpty() = пустота;
 *   stack.iliIIililI() = damage; Item (IillilIiiI).liillIII = maxDamage
 *   (пишется билдером liIiIiiIiI(F), 15.0f-множитель — Rust-прочность).
 *   Патроны 1:1 с виджетом мода (lIiiIiIliI.llIIiillil): gun = IliIiIIiII.
 *   IIIilIIIll(item), обойма = IlIIiIIiII.llIllIIIll(stack, iiIiiiilII.
 *   IIliiliiiiI) (Integer, null = 0).
 *
 * Иконки — родные иконки мода (ItemIcons, texId+uvRect), как в GearESP.
 * Панель рисуется НЕПРОЗРАЧНОЙ и накрывает ванильный хотбар форка (наш
 * overlay-проход идёт после super.iliIiiIliI).
 */
public final class Hotbar extends Module {

    private static Hotbar INSTANCE;

    private static final int SLOTS = 6;
    private static final float SLOT_STEP = 21.5f;
    private static final float PANEL_H = 27f;
    private static final float PANEL_BOTTOM = 29f; // y = H-29 (низ панели за 2px до края)
    private static final float LIFT_ANIM_MS = 300f;

    // rockstar client-bg (их I_method_37580a2f = 24,21,29,229.5), акцент #906BFF
    private static final int BG = 0xE518151D;
    private static final int ACCENT = 0xFF906BFF;
    private static final int WHITE_A40 = 0x66000000; // black@0.4 (тень счётчика)
    private static final int WHITE_A25 = 0x40FFFFFF; // white@0.25 (bg прочности)

    // анимации подъёма (300мс, rockstar sig)
    private final float[] selAnim = new float[SLOTS];
    private final float[] nearAnim = new float[SLOTS];
    private long lastFrame;

    // reflection-кэш
    private boolean invResolved;
    private long lastDiag;
    private Field invPlayerF;      // wrapper.iIliiIiII → InventoryPlayer (по иерархии)
    private Field invMainListF;    // InventoryPlayer.lllliIiII (main)
    private Field invAltListF;     // InventoryPlayer.iiiiIIiII (альтернативный хотбар)
    private Field invSelF;         // InventoryPlayer.iliiIIiII — ВЫБРАННЫЙ слот
    private Field invSelF2;        // InventoryPlayer.iIiiIIiII — НЕ sel (мусор 306+)
    private Method stackDamageM;   // stack.iliIIililI() → damage
    private Method stackCountM;    // stack.lIIIIililI() → getCount (НЕ lIiiIililI: то maxStack!)
    private Method stackIsEmptyM;  // stack.isEmpty()Z
    private Method stackGetItemM;  // stack.IIllIililI() → Item (ленивый резолв)
    private Method curItemM;       // InventoryPlayer.lIiIIIiilI() → текущий стек
    private Field stackItemF;      // stack.lllilIlII → Item
    private Field itemMaxDmgF;     // Item.liillIII → maxDamage
    // Патроны 1:1 с lIiiIiIliI.llIIiillil: gun = IliIiIIiII.IIIilIIIll(item);
    // обойма = IlIIiIIiII.llIllIIIll(stack, iiIiiiilII.IIliiliiiiI) (Integer).
    private Method gunCheckM;      // IliIiIIiII.IIIilIIIll(IillIiIiII)Z static
    private Method nbtAmmoIntM;    // IlIIiIIiII.llIllIIIll(iilIiIIiII,String)Integer static
    private Class itemBaseC;       // rustme.IillIiIiII (параметр gun-чека)
    private Field ammoKeyF;        // iiIiiiilII.IIliiliiiiI → String-ключ обоймы
    private String ammoKeyName;    // значение ключа (резолвится лениво, fallback "ammo")
    // выбор слота у хотбара форка (IIiiiIliiI.iilIlIil → lIiliIliiI.lIllllIliI)
    private boolean forkSelResolved;
    private Field forkStateF;
    private Method forkSelM;
    private Class hotbarOverlayC;  // rustme.lilIililiI (HotbarOverlay — виджет HUD форка)
    private long lastAmmoDiag;

    public Hotbar() {
        super("Hotbar", "Visuals");
        INSTANCE = this;
        Log.info("Hotbar", "registered");
    }

    /** Для CheatIngame: хотбар форка пропускается, пока наш включен. */
    public static boolean isActive() {
        Hotbar inst = INSTANCE;
        return inst != null && inst.isState();
    }

    private boolean resolve(GameContext ctx) {
        if (invResolved) return true;
        try {
            Class c = ctx.localSpClass;
            while (c != null && invPlayerF == null) {
                try {
                    invPlayerF = c.getDeclaredField("iIliiIiII");
                    invPlayerF.setAccessible(true);
                } catch (Throwable ig) {
                    c = c.getSuperclass();
                }
            }
            if (invPlayerF == null) {
                Log.error("Hotbar", "inventory field NOT found", null);
                return false;
            }
            Class invC = ctx.gameLoader.loadClass("rustme.iiIIIiIIiI");
            invMainListF = invC.getField("lllliIiII");
            invAltListF = invC.getField("iiiiIIiII");
            invSelF = invC.getField("iliiIIiII");
            invSelF2 = invC.getField("iIiiIIiII");
            Class stackC = ctx.gameLoader.loadClass("rustme.liIIIIIIiI");
            stackDamageM = stackC.getMethod("iliIIililI");
            stackCountM = stackC.getMethod("lIIIIililI");
            stackIsEmptyM = stackC.getMethod("isEmpty");
            stackGetItemM = stackC.getMethod("IIllIililI");
            curItemM = invC.getMethod("lIiIIIiilI");
            stackItemF = stackC.getField("lllilIlII");
            Class itemC = ctx.gameLoader.loadClass("rustme.IillilIiiI");
            itemMaxDmgF = itemC.getField("liillIII");
            // Патроны: gun-чек + Integer-хелпер мода (читает rustme_subdata сам)
            Class gunC = ctx.gameLoader.loadClass("rustme.IliIiIIiII");
            itemBaseC = ctx.gameLoader.loadClass("rustme.IillIiIiII");
            gunCheckM = gunC.getMethod("IIIilIIIll", itemBaseC);
            Class nbtHlpC = ctx.gameLoader.loadClass("rustme.IlIIiIIiII");
            Class stackIfC = ctx.gameLoader.loadClass("rustme.iilIiIIiII");
            nbtAmmoIntM = nbtHlpC.getMethod("llIllIIIll", stackIfC, String.class);
            Class ammoKeysC = ctx.gameLoader.loadClass("rustme.iiIiiiilII");
            ammoKeyF = ammoKeysC.getField("IIliiliiiiI");
            invResolved = true;
            Log.info("Hotbar", "inventory chain resolved (count=lIIIIililI, ammo=IIIilIIIll/llIllIIIll)");
            return true;
        } catch (Throwable t) {
            Log.error("Hotbar", "resolve failed", t);
            return false;
        }
    }

    /** Рендер хотбара. Вызывается из CheatHud.renderFrame (GL-поток). */
    public static void render(GameContext ctx, int scaledW, int scaledH) {
        Hotbar inst = INSTANCE;
        if (inst == null || !inst.isState()) return;
        // хотбар форка рисует ВИДЖЕТ HotbarOverlay (OverlayListener.overlays) —
        // убираем его из списка (поле iillliil тут ни при чём, см. лог 09-11)
        try {
            if (ctx.gameLoader != null) {
                if (inst.hotbarOverlayC == null) {
                    inst.hotbarOverlayC = ctx.gameLoader.loadClass("rustme.lilIililiI");
                }
                if (inst.hotbarOverlayC != null) {
                    utils.etc.Overlays.hide(ctx, inst.hotbarOverlayC);
                }
            }
        } catch (Throwable ignore) {}
        if (!ctx.inWorld || ctx.player == null || ctx.world == null) return;
        try {
            if (!inst.resolve(ctx)) return;

            Object inv = inst.invPlayerF.get(ctx.player); // iIliiIiII — иерархия SP→wrapper
            if (inv == null) return;

            // какой список — хотбар: main (lllliIiII, 30 слотов, хотбар 0..5),
            // при пустых первых слотах пробуем альтернативные (iiiiIIiII /
            // IliiIIiII) — вдруг форк держит хотбар отдельным списком
            List<?> main = (List<?>) inst.invMainListF.get(inv);
            if (main == null || main.isEmpty()) return;
            int slots = Math.min(SLOTS, main.size());
            int sel = inst.invSelF.getInt(inv);
            // форк держит выбор хотбара в своём состоянии (лог: sel=-1 при
            // живом инвентаре) — если инвентарный не валиден, берём их
            if (sel < 0 || sel >= SLOTS) sel = inst.forkSelected(ctx);
            // запасной путь: индекс текущего стека (getCurrentItem) в списке
            if (sel < 0 || sel >= SLOTS) sel = inst.currentItemIndex(inv, main, slots);
            List<?> pick = main;
            if (!inst.hasAnyItem(main, slots)) {
                try {
                    List<?> alt = (List<?>) inst.invAltListF.get(inv);
                    if (inst.hasAnyItem(alt, slots)) pick = alt;
                } catch (Throwable ignore) {}
            }

            // геометрия rockstar: контент 6 + 5*21.5 + 16, паддинг справа 6
            // (как их 200 = 6 + 8*21.5 + 16 + 6) → панель 135.5×27
            float slotStart = 6f;
            float panelW = slotStart + (slots - 1) * SLOT_STEP + 16f + 6f;
            float x = scaledW / 2f - panelW / 2f;
            float y = scaledH - PANEL_BOTTOM;
            long now = System.currentTimeMillis();
            float dt = inst.lastFrame == 0 ? 16f : Math.min(64f, now - inst.lastFrame);
            inst.lastFrame = now;
            float step = dt / LIFT_ANIM_MS;

            // панель: client-bg, углы (1,0,7,8), БЕЗ рамки (drawClientRect 1:1)
            RenderUtil.drawRoundedRectCorners(ctx, x, y, panelW, PANEL_H,
                1f, 0f, 7f, 8f, BG, BG, BG, BG, 0.25f);

            for (int i = 0; i < slots; i++) {
                boolean isSel = i == sel;
                boolean isNear = Math.abs(sel - i) <= 1;
                inst.selAnim[i] = stepTo(inst.selAnim[i], isSel ? 1f : 0f, step);
                inst.nearAnim[i] = stepTo(inst.nearAnim[i], isNear ? 1f : 0f, step);
                float lift = 0.1f * inst.nearAnim[i] + 0.25f * inst.selAnim[i];

                float slotX = x + slotStart + i * SLOT_STEP;
                float slotY = y + 5.5f - 12f * lift;

                Object stackO = pick.get(i);
                if (stackO instanceof liIIIIIIiI) {
                    liIIIIIIiI stack = (liIIIIIIiI) stackO;
                    // Пустота — только через isEmpty (count-метод lIiiIililI
                    // возвращал maxStack: 1000 у ресурсов, 32767 у пустых).
                    boolean empty = true;
                    int count = 0;
                    try {
                        empty = ((Boolean) inst.stackIsEmptyM.invoke(stack)).booleanValue();
                        count = ((Integer) inst.stackCountM.invoke(stack)).intValue();
                    } catch (Throwable ignore) {}
                    if (!empty && count > 0) {
                        // масштаб 1+lift вокруг центра слота (их matrix-scale 1:1)
                        float sc = 1f + lift;
                        float size = 16f * sc;
                        ItemIcons.drawItemIcon(ctx, stack,
                            slotX + 8f - 8f * sc, slotY + 8f - 8f * sc, size);
                        // счётчик 8px, правый край slotX+17, y+9; тень +0.5/+0.5 black@0.4
                        int ammo = inst.ammoOf(stack);
                        if (ammo >= 0) {
                            String s = String.valueOf(ammo);
                            float tw = CustomFont.getWidth(s, 8f, CustomFont.HUD_FONT);
                            float cx = slotX + 17f - tw;
                            float cy = slotY + 9f;
                            int col = ammo == 0 ? 0xFFFF5040 : 0xFFFFFFFF;
                            CustomFont.drawString(s, cx + 0.5f, cy + 0.5f, WHITE_A40, false, 8f, CustomFont.HUD_FONT);
                            CustomFont.drawString(s, cx, cy, col, false, 8f, CustomFont.HUD_FONT);
                        } else if (count != 1) {
                            String s = String.valueOf(count);
                            float tw = CustomFont.getWidth(s, 8f, CustomFont.HUD_FONT);
                            float cx = slotX + 17f - tw;
                            float cy = slotY + 9f;
                            CustomFont.drawString(s, cx + 0.5f, cy + 0.5f, WHITE_A40, false, 8f, CustomFont.HUD_FONT);
                            CustomFont.drawString(s, cx, cy, 0xFFFFFFFF, false, 8f, CustomFont.HUD_FONT);
                        }
                        float[] dur = inst.durability(stack);
                        if (dur != null) {
                            float frac = dur[1] <= 0f ? 0f : 1f - dur[0] / dur[1];
                            if (frac < 0f) frac = 0f;
                            if (frac > 1f) frac = 1f;
                            RenderUtil.drawRoundedRectShader(ctx, slotX + 2f, slotY + 13f,
                                13f, 1.5f, 0.25f,
                                WHITE_A25, WHITE_A25, WHITE_A25, WHITE_A25, 0.25f);
                            float wdt = 13f * frac;
                            if (wdt > 0.05f) {
                                RenderUtil.drawRoundedRectShader(ctx, slotX + 2f, slotY + 13f,
                                    wdt, 1.5f, 0.25f,
                                    ACCENT, ACCENT, ACCENT, ACCENT, 0.25f);
                            }
                        }
                    } else {
                        // пустой слот: номер 10px, эмуляция их matrix-scale 1+lift
                        // вокруг (slotX+8, slotY+8): текст в (x+8.5-w/2, y+4.5)
                        String s = String.valueOf(i + 1);
                        float a = Math.min(1f, 0.5f + 1.5f * lift);
                        float sc = 1f + lift;
                        float sz = 10f * sc;
                        float tw = CustomFont.getWidth(s, sz, CustomFont.HUD_FONT);
                        int alpha = (int) (255f * a);
                        CustomFont.drawString(s, slotX + 8f + 0.5f * sc - tw / 2f,
                            slotY + 8f - 3.5f * sc,
                            (alpha << 24) | 0xFFFFFF, false, sz, CustomFont.HUD_FONT);
                    }
                }
            }

            // точка выбора 2×2 (их i_method_707ede89): белый, alpha=selAnim,
            // на (x+7.5, y+21-anim) от ПОДНЯТЫХ координат слота
            for (int i = 0; i < slots; i++) {
                float a = inst.selAnim[i];
                if (a > 0.001f) {
                    float ac = Math.min(1f, a);
                    int alpha = (int) (255f * ac);
                    float dx = x + slotStart + i * SLOT_STEP;
                    float lift = 0.1f * inst.nearAnim[i] + 0.25f * a;
                    float dy = y + 5.5f - 12f * lift;
                    int col = (alpha << 24) | 0xFFFFFF;
                    RenderUtil.drawRoundedRectShader(ctx, dx + 7.5f, dy + 21f - a,
                        2f, 2f, 0.5f, col, col, col, col, 0.25f);
                }
            }

            // диагностика выбора списка (раз в 5с)
            if (System.currentTimeMillis() - inst.lastDiag > 5000L) {
                inst.lastDiag = System.currentTimeMillis();
                int vanillaSel = -999;
                try { vanillaSel = inst.invSelF.getInt(inv); } catch (Throwable ignore) {}
                int forkSel = inst.forkSelected(ctx);
                StringBuilder counts = new StringBuilder();
                StringBuilder ammos = new StringBuilder();
                for (int i = 0; i < slots; i++) {
                    Object st = pick.get(i);
                    if (st instanceof liIIIIIIiI) {
                        liIIIIIIiI sk = (liIIIIIIiI) st;
                        boolean e = true;
                        int c = -1;
                        try {
                            e = ((Boolean) inst.stackIsEmptyM.invoke(sk)).booleanValue();
                            c = ((Integer) inst.stackCountM.invoke(sk)).intValue();
                        } catch (Throwable ignore) {}
                        counts.append(e ? "E" : String.valueOf(c));
                        ammos.append(String.valueOf(inst.ammoOf(st)));
                    } else {
                        counts.append("?");
                        ammos.append("?");
                    }
                    if (i < slots - 1) { counts.append(','); ammos.append(','); }
                }
                Log.info("Hotbar", "pick=" + (pick == main ? "main" : "alt")
                    + " sel=" + sel + " vanilla=" + vanillaSel + " fork=" + forkSel
                    + " ammoKey=" + inst.ammoKeyName()
                    + " counts=[" + counts + "] ammo=[" + ammos + "]");
            }
        } catch (Throwable t) {
            Log.error("Hotbar", "render failed", t);
        }
    }

    @Override
    protected void onDisable() {
        // вернуть HotbarOverlay форка в OverlayListener
        try {
            if (hotbarOverlayC != null) {
                utils.etc.Overlays.restore(GameContext.get(), hotbarOverlayC);
            }
        } catch (Throwable ignore) {}
    }

    /** Патроны в обойме 1:1 с виджетом мода (lIiiIiIliI.llIIiillil):
     *  gun = IliIiIIiII.IIIilIIIll(item), обойма = IlIIiIIiII.llIllIIIll(
     *  stack, iiIiiiilII.IIliiliiiiI). -1 = не оружие. Оружие с null-обоймой
     *  (мод показывает 0) → 0. */
    private int ammoOf(Object stack) {
        try {
            if (gunCheckM == null || nbtAmmoIntM == null || stackGetItemM == null) return -1;
            Object item = stackGetItemM.invoke(stack);
            if (item == null) return -1;
            if (itemBaseC != null && !itemBaseC.isInstance(item)) return -1;
            boolean gun;
            try {
                gun = ((Boolean) gunCheckM.invoke(null, item)).booleanValue();
            } catch (Throwable t) {
                return -1;
            }
            if (!gun) return -1;
            Object v;
            try {
                v = nbtAmmoIntM.invoke(null, stack, ammoKeyName());
            } catch (Throwable t) {
                return -1;
            }
            if (v instanceof Integer) {
                int iv = ((Integer) v).intValue();
                if (iv < 0) iv = 0;
                return Math.min(999, iv);
            }
            return 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Значение String-ключа обоймы (iiIiiiilII.IIliiliiiiI), fallback "ammo". */
    private String ammoKeyName() {
        if (ammoKeyName != null) return ammoKeyName;
        try {
            Object v = ammoKeyF.get(null);
            if (v instanceof String && !((String) v).isEmpty()) {
                ammoKeyName = (String) v;
            } else {
                ammoKeyName = "ammo";
            }
        } catch (Throwable t) {
            ammoKeyName = "ammo";
        }
        return ammoKeyName;
    }

    private boolean hasAnyItem(List<?> list, int slots) {
        if (list == null) return false;
        int n = Math.min(slots, list.size());
        for (int i = 0; i < n; i++) {
            Object st = list.get(i);
            if (st instanceof liIIIIIIiI) {
                try {
                    if (!((Boolean) stackIsEmptyM.invoke(st)).booleanValue()) return true;
                } catch (Throwable ignore) {}
            }
        }
        return false;
    }

    /** Индекс текущего стека (getCurrentItem) в списке хотбара. -1 = нет. */
    private int currentItemIndex(Object inv, List<?> pick, int slots) {
        try {
            if (curItemM == null) return -1;
            Object cur = curItemM.invoke(inv);
            if (cur == null) return -1;
            for (int i = 0; i < slots && i < pick.size(); i++) {
                if (pick.get(i) == cur) return i;
            }
        } catch (Throwable ignore) {}
        return -1;
    }

    /** Выбранный слот из состояния хотбара форка: делегат CheatIngame
     *  (оригинальный IIiiiIliiI) → public-поле iilIlIil (lIiliIliiI) →
     *  lIllllIliI()I. -1 = нет/не резолвится. */
    private int forkSelected(GameContext ctx) {
        try {
            Object del = rustme.CheatIngame.forkHotbarDelegate();
            if (del == null) return -1;
            if (!forkSelResolved) {
                forkSelResolved = true;
                Class c = ctx.gameLoader.loadClass("rustme.IIiiiIliiI");
                forkStateF = c.getField("iilIlIil");
            }
            Object st = forkStateF.get(del);
            if (st == null) return -1;
            if (forkSelM == null) forkSelM = st.getClass().getMethod("lIllllIliI");
            int v = ((Integer) forkSelM.invoke(st)).intValue();
            return v >= 0 && v < SLOTS ? v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** {damage, maxDamage} или null (недamageable/поломка резолва). */
    private float[] durability(liIIIIIIiI stack) {
        try {
            if (stackDamageM == null || stackItemF == null || itemMaxDmgF == null) return null;
            float dmg = ((Integer) stackDamageM.invoke(stack)).intValue();
            Object item = stackItemF.get(stack);
            if (item == null) return null;
            float maxDmg = (float) itemMaxDmgF.getInt(item);
            if (maxDmg <= 0f || dmg <= 0f) return null;
            return new float[]{dmg, maxDmg};
        } catch (Throwable t) {
            return null;
        }
    }

    private static float stepTo(float cur, float target, float step) {
        if (cur < target) return Math.min(target, cur + step);
        if (cur > target) return Math.max(target, cur - step);
        return cur;
    }
}
