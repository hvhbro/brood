package modules.impl;

import org.lwjglx.opengl.GL11;

import modules.api.Module;
import rustme.IIlIIliIiI;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.CustomFont;
import utils.render.RenderUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * ItemEsp — текст на предметах, лежащих на полу (EntityItem).
 *
 * ЦЕПОЧКА (верифицирована javap 09-10):
 *   World (IIlllIlIiI).IiIiiiiil : public final List<IIlIIliIiI> — общий
 *   список загруженных сущностей; фильтр instanceof rustme.liIilliIiI
 *   (EntityItem, extends Entity root);
 *   item.IllIIIlIlI() → ItemStack (liIIIIIIiI);
 *   stack.iIllIililI() → display name (учитывает NBT custom-name, как ванильный
 *     getDisplayName; фолбэк — IlIlIililI() = item → имя для стека);
 *   stack.lIiiIililI() → count (vanilla getCount: isEmpty ? 0 : count).
 *
 * РЕНДЕР: overlay-фаза (CheatHud.renderFrame → render). Позиция интерполируется
 * prev-геттерами корня Entity (как Esp, телепорт-порог 4 бл), проекция —
 * Esp.projectToScreen (та же камера/PROJ, что у боксов/трейсеров; updateCamera
 * в начале — урок аудита 09-09: без него cameraReady=false навсегда).
 * Стиль метки — как SoundEsp: тёмная плашка r2 + текст 7px, имя слева,
 * количество/дистанция серым в той же строке.
 *
 * КЛАВИША: только бинд из меню (хардкод-бинды в модулях запрещены).
 */
public final class ItemEsp extends Module {

    private static ItemEsp INSTANCE;

    private final Module.FloatSetting stDist = addSetting("Дистанция", 8f, 128f, 1f, 48f);

    private static final int MAX_LABELS = 128;      // анти-фриз при лут-свалке
    private static final float[] PROJ = new float[2];

    // reflection-кэш (ленивый, резолв в render-потоке)
    private boolean triedResolve;
    private Field entityListF;       // World.IiIiiiiil : List<IIlIIliIiI>
    private Field entityListF2;      // World.Iiliiiiil
    private Field entityListF3;      // World.IIliiiiil
    private Class itemClass;         // rustme.liIilliIiI (EntityItem)
    private Method stackOfM;         // item.IllIIIlIlI() → liIIIIIIiI
    private Method nameM;            // stack.iIllIililI() → String (custom-name aware)
    private Method baseNameM;        // stack.IlIlIililI() → String (от item)
    private Method countM;           // stack.lIiiIililI() → int
    private boolean chainLogged;
    private long lastDiag;

    public ItemEsp() {
        super("ItemEsp", "Visuals");
        INSTANCE = this;
        Log.info("ItemEsp", "registered");
    }

    private boolean resolve(GameContext ctx) {
        if (triedResolve) return entityListF != null;
        triedResolve = true;
        try {
            entityListF = ctx.worldClass.getField("IiIiiiiil");
            entityListF2 = ctx.worldClass.getField("Iiliiiiil");
            entityListF3 = ctx.worldClass.getField("IIliiiiil");
            itemClass = ctx.gameLoader.loadClass("rustme.liIilliIiI");
            Class stackC = ctx.gameLoader.loadClass("rustme.liIIIIIIiI");
            stackOfM = itemClass.getMethod("IllIIIlIlI");
            nameM = stackC.getMethod("iIllIililI");
            baseNameM = stackC.getMethod("IlIlIililI");
            countM = stackC.getMethod("lIiiIililI");
            if (!chainLogged) {
                chainLogged = true;
                Log.info("ItemEsp", "chain resolved (World.IiIiiiiil → liIilliIiI → stack)");
            }
            return true;
        } catch (Throwable t) {
            Log.error("ItemEsp", "resolve failed", t);
            return false;
        }
    }

    /** Рендер меток предметов. Вызывается из CheatHud.renderFrame (GL-поток). */
    public static void render(GameContext ctx, float partialTicks, int scaledW, int scaledH) {
        ItemEsp inst = INSTANCE;
        if (inst == null || !inst.isState()) return;
        if (!ctx.inWorld || ctx.world == null || ctx.player == null) return;
        try {
            if (!inst.resolve(ctx)) return;

            Esp.updateCamera(ctx, partialTicks);
            if (!Esp.cameraReady()) return;

            IIlIIliIiI me = (IIlIIliIiI) ctx.player;
            double mx = me.IlIiillIII(), my = me.liiiIllIII() + me.iliilIiilI(), mz = me.lIilillIII();
            float maxDist = inst.stDist.value;
            float maxDistSq = maxDist * maxDist;

            // три List<IIlIIliIiI>-поля World (загруженные/выгруженные/прочие) —
            // предметы могут лежать в любом; дедуп по identity
            java.util.HashSet<Object> seen = new java.util.HashSet<Object>();
            Field[] lists = {inst.entityListF, inst.entityListF2, inst.entityListF3};
            int totalAll = 0;
            Object[] arr = new Object[0];
            {
                java.util.ArrayList<Object> all = new java.util.ArrayList<Object>();
                for (Field lf : lists) {
                    if (lf == null) continue;
                    try {
                        java.util.List<?> l = (java.util.List<?>) lf.get(ctx.world);
                        if (l != null && !l.isEmpty()) {
                            totalAll += l.size();
                            all.addAll(l);
                        }
                    } catch (Throwable ignore) {}
                }
                // дедуп (одна сущность может быть в нескольких списках)
                java.util.ArrayList<Object> uniq = new java.util.ArrayList<Object>(all.size());
                for (Object o : all) {
                    if (seen.add(o)) uniq.add(o);
                }
                arr = uniq.toArray(new Object[0]);
            }
            if (arr.length == 0) {
                if (System.currentTimeMillis() - inst.lastDiag > 5000L) {
                    inst.lastDiag = System.currentTimeMillis();
                    Log.info("ItemEsp", "diag: all entity lists EMPTY");
                }
                return;
            }

            long now = System.currentTimeMillis();
            int drawn = 0;
            int itemHits = 0;
            int nonItem = 0;
            StringBuilder nonItemClasses = new StringBuilder();
            for (int i = 0; i < arr.length && drawn < MAX_LABELS; i++) {
                Object o = arr[i];
                if (o == null) continue;
                if (!inst.itemClass.isInstance(o)) {
                    // диагностика: какие классы ходят в списке сущностей
                    if (nonItem < 12 && !(o instanceof IIlIIliIiI)) {
                        // не сущности корня — пропускаем молча
                    } else if (nonItem < 12) {
                        if (nonItemClasses.length() > 0) nonItemClasses.append(',');
                        nonItemClasses.append(o.getClass().getSimpleName());
                    }
                    nonItem++;
                    continue;
                }
                itemHits++;
                IIlIIliIiI e = (IIlIIliIiI) o;

                // интерполяция позиции (prev→pos, телепорт-порог 4 бл)
                double x = e.IlIiillIII(), y = e.liiiIllIII(), z = e.lIilillIII();
                double px = e.IiilillIII(), py = e.lliilIlIII(), pz = e.lilllIlIII();
                if (Math.abs(x - px) > 4.0 || Math.abs(y - py) > 4.0 || Math.abs(z - pz) > 4.0) {
                    px = x; py = y; pz = z;
                }
                double ix = px + (x - px) * partialTicks;
                double iy = py + (y - py) * partialTicks;
                double iz = pz + (z - pz) * partialTicks;

                double dx = ix - mx, dy = iy - my, dz = iz - mz;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > maxDistSq) continue;

                double cw = Esp.projectToScreen(ix, iy + 0.35, iz, scaledW, scaledH, PROJ);
                if (cw <= 0.2) continue; // за камерой

                Object stack = inst.stackOfM.invoke(o);
                if (stack == null) continue;
                String base = null;
                try {
                    base = (String) inst.baseNameM.invoke(stack);
                } catch (Throwable ignore) {}
                String name = null;
                try {
                    name = (String) inst.nameM.invoke(stack);
                } catch (Throwable ignore) {}
                if (name == null || name.isEmpty()) name = base;
                if (name == null || name.isEmpty()) continue;
                int count = ((Integer) inst.countM.invoke(stack)).intValue();

                String text = name;
                if (count > 1) text += " x" + count;
                text += "  " + Math.round(Math.sqrt(distSq)) + "м";

                int labelColor = colorFor(base != null ? base : name);

                float textW = CustomFont.getWidth(text, 7f);
                float textH = CustomFont.cellHeight(7f);
                float padX = 3f, padY = 2f;
                float bw = textW + padX * 2f;
                float bh = textH + padY * 2f;
                float bx = PROJ[0] - bw / 2f;
                float by = PROJ[1] - bh;

                RenderUtil.drawRoundedRect(ctx, bx, by, bw, bh, 2f, 0xA80C0C12);
                CustomFont.drawString(text, bx + padX, by + padY, labelColor, false, 7f);
                drawn++;
            }

            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glEnable(GL11.GL_TEXTURE_2D);

            if (System.currentTimeMillis() - inst.lastDiag > 5000L) {
                inst.lastDiag = System.currentTimeMillis();
                Log.info("ItemEsp", "diag: total=" + arr.length + "/" + totalAll
                    + " itemHits=" + itemHits + " drawn=" + drawn
                    + " others=[" + nonItemClasses + "]");
            }
        } catch (Throwable t) {
            Log.error("ItemEsp", "render failed", t);
        }
    }

    /** Цвет подписи по категории предмета (по unlocalized-строке). */
    private static int colorFor(String ws) {
        String s = ws == null ? "" : ws.toLowerCase();
        if (containsAny(s, "rifle", "pistol", "smg", "mp5", "m4", "lr-", "lr300", "ak-",
            "ak47", "l96", "m249", "m39", "revolver", "python", "thompson", "eoka",
            "nailgun", "bow", "crossbow", "shotgun", "rocket", "launcher", "spear",
            "machete", "katana", "sword", "cleaver", "mace", "grenade", "satchel",
            "c4", "explosive")) {
            return 0xFFFF4545; // оружие/взрывчатка — красный
        }
        if (containsAny(s, "ammo", "bullet", "556", "9mm", "hv_", "arrow", "shell")) {
            return 0xFFFFB84D; // патроны — оранжевый
        }
        if (containsAny(s, "med", "syringe", "bandage", "antib", "heal", "drug")) {
            return 0xFF7CE8FF; // меды — голубой
        }
        if (containsAny(s, "berry", "meat", "water", "jug", "food", "cook", "apple",
            "grain", "seed", "corn", "pumpkin_seed", "potato")) {
            return 0xFF7DFF7D; // еда — зелёный
        }
        if (containsAny(s, "pick", "axe", "hatchet", "hammer", "drill", "tool")) {
            return 0xFFFFE14D; // инструменты — жёлтый
        }
        return 0xFFFFFFFF;
    }

    private static boolean containsAny(String s, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            if (s.contains(keys[i])) return true;
        }
        return false;
    }
}
