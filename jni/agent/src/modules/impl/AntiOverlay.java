package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import org.lwjgl.opengl.GL20;
import org.lwjglx.opengl.GL11;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Method;

/**
 * AntiOverlay — порт модуля чит-клиента (chtdump, config-блоб .rdata):
 *   antiOverlayEnabled=1, HeavyMask=1, DivingMask=1, Pumpkin=0.
 *
 * КАК РАБОТАЕТ У НИХ (дизасм): оверлеи масок = спеки iiIIlilliI в икон-реестре
 * rustme.liiIlilliI (синглтон iiliIIIiiI, public final Map<String, spec>
 * lIilIIIiiI; ключи "icon_overlay-heavy-helmet" и т.п., регистрация из
 * IIIliilliI.<clinit>). Текстуры масок В TextureManager НЕ попадают — потому
 * v2/v3 (подмена/перезапись) не работали.
 *
 * v4: удаляем спеки масок из публичной Map реестра (Map.remove по ключу).
 * Рендерер спрашивает реестр по ключу → null → (Kotlin null-safe) оверлей
 * не рисуется. Спеки СОХРАНЯЕМ и возвращаем при выключении модуля.
 * Тыква (vanilla pumpkinblur) — дополнительно перезапись через TextureManager.
 */
public final class AntiOverlay extends Module {

    private static AntiOverlay INSTANCE;

    private final Module.BoolSetting stHeavy = addBool("Heavy Mask", true);
    private final Module.BoolSetting stDiving = addBool("Diving Mask", true);
    private final Module.BoolSetting stPumpkin = addBool("Pumpkin", false);

    private boolean triedResolve;
    private boolean resolvedOk;
    private Object texManager;              // lIliliiIiI (TextureManager)
    private Object texMap;                  // lIillIiiI: Map<RL, TextureObject>
    private Method texRegister;             // lliIilliII(RL, TextureObject)Z
    private Method texIdGetter;             // TextureObject.iliiIlliil()I
    private Class rlClass;                  // rustme.lllililIiI
    private rustme.TransparentTexture texShared;
    private final java.util.HashSet<Integer> overwritten = new java.util.HashSet<Integer>();

    private Object iconRegistry;            // lIiIlilliI (синглтон iiliIIIiiI)
    private Object iconRegistryMap;         // lIilIIIiiI: Map<String, spec>
    /** key → удалённый спек (возвращаем при выключении). */
    private final java.util.HashMap<String, Object> removedSpecs =
        new java.util.HashMap<String, Object>();
    private long lastApply;
    private boolean firstApplyDone;

    private static final String[] MASK_KEYS = {
        "icon_overlay-heavy-helmet",
        "icon_overlay-diving-mask",
    };

    public AntiOverlay() {
        super("AntiOverlay", "Visuals");
        INSTANCE = this;
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (isState()) tick(event);
                } catch (Throwable t) {
                    Log.error("AntiOverlay", "tick exception", t);
                }
            }
        });
        Log.info("AntiOverlay", "registered");
    }

    private boolean resolve(GameContext ctx) {
        if (triedResolve) return resolvedOk;
        triedResolve = true;
        try {
            Class rlClassL = ctx.gameLoader.loadClass("rustme.lllililIiI");
            Class texObjClass = ctx.gameLoader.loadClass("rustme.iiIiliiIiI");
            Method getTM = ctx.gs.getClass().getMethod("IIiIiilliI");
            Object tm = getTM.invoke(ctx.gs);
            if (tm == null) return false;
            texMap = tm.getClass().getField("lIillIiiI").get(tm);
            texRegister = tm.getClass().getMethod("lliIilliII", rlClassL, texObjClass);
            texIdGetter = texObjClass.getMethod("iliiIlliil");
            texManager = tm;
            rlClass = rlClassL;
            texShared = new rustme.TransparentTexture();

            // икон-реестр масок: rustme.liiIlilliI — ХАБ (static iiliIIIiiI),
            // lIilIIIiiI (Map<String,spec>) есть НЕ у хаба, а у класса-реестра
            // lIiIlilliI<T>. Оверлей-спеки (iiIIlilliI) живут в реестре
            // liiIIIIiiI (геттер lIlIillIIl) — дизасм liiIlilliI/lIiIlilliI.
            // Прежний код брал Map у хаба → NoSuchFieldException, мод молча
            // не работал.
            Class regC = ctx.gameLoader.loadClass("rustme.liiIlilliI");
            iconRegistry = regC.getField("iiliIIIiiI").get(null);
            Method overlayReg = regC.getMethod("lIlIillIIl");
            Object overlayRegistry = overlayReg.invoke(iconRegistry);
            iconRegistryMap = overlayRegistry.getClass().getField("lIilIIIiiI")
                .get(overlayRegistry);
            resolvedOk = true;
            Log.info("AntiOverlay", "resolved (icon registry + texture manager), "
                + "overlay specs in map: " + ((java.util.Map) iconRegistryMap).size());
            return true;
        } catch (Throwable t) {
            Log.error("AntiOverlay", "resolve failed", t);
            return false;
        }
    }

    private void tick(TickEvent event) {
        GameContext ctx = GameContext.get();
        if (ctx.gs == null) return;
        if (!event.isInWorld()) return;
        if (!resolve(ctx)) return;
        long now = System.currentTimeMillis();
        if (now - lastApply < 3000L) return;
        lastApply = now;
        apply(ctx);
    }

    @Override
    protected void onEnable() {
        GameContext ctx = GameContext.get();
        if (ctx.gs != null && resolvedOk) apply(ctx);
    }

    @Override
    protected void onDisable() {
        // вернуть спеки масок на место + вернуть виджеты в OverlayListener
        GameContext ctx = GameContext.get();
        if (ctx.gs == null) return;
        ctx.runOnMainThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (iconRegistryMap != null) {
                        java.util.Map map = (java.util.Map) iconRegistryMap;
                        for (java.util.Map.Entry<String, Object> e : removedSpecs.entrySet()) {
                            map.put(e.getKey(), e.getValue());
                        }
                        removedSpecs.clear();
                    }
                } catch (Throwable ignore) {}
                try {
                    if (heavyOverlayC != null) utils.etc.Overlays.restore(ctx, heavyOverlayC);
                    if (divingOverlayC != null) utils.etc.Overlays.restore(ctx, divingOverlayC);
                } catch (Throwable ignore) {}
            }
        });
    }

    private boolean maskClassesResolved;
    private Class heavyOverlayC;   // rustme.liIiililiI (HeavyHelmetOverlay)
    private Class divingOverlayC;  // rustme.lliiililiI (DivingMaskOverlay)

    private boolean resolveMaskClasses(GameContext ctx) {
        if (maskClassesResolved) return heavyOverlayC != null;
        try {
            heavyOverlayC = ctx.gameLoader.loadClass("rustme.liIiililiI");
            divingOverlayC = ctx.gameLoader.loadClass("rustme.lliiililiI");
            maskClassesResolved = true;
            return true;
        } catch (Throwable t) {
            Log.error("AntiOverlay", "mask overlay classes resolve failed", t);
            return false;
        }
    }

    private void apply(final GameContext ctx) {
        final boolean first = !firstApplyDone;
        ctx.runOnMainThread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1) маски: удалить спеки из икон-реестра
                    if (iconRegistryMap != null) {
                        java.util.Map map = (java.util.Map) iconRegistryMap;
                        for (String key : MASK_KEYS) {
                            boolean enabled = key.endsWith("heavy-helmet")
                                ? stHeavy.get() : stDiving.get();
                            if (enabled) {
                                if (!removedSpecs.containsKey(key)) {
                                    Object spec = map.remove(key);
                                    if (spec != null) removedSpecs.put(key, spec);
                                }
                            } else if (removedSpecs.containsKey(key)) {
                                map.put(key, removedSpecs.remove(key));
                            }
                        }
                        if (first) {
                            Log.info("AntiOverlay", "icon specs removed: " + removedSpecs.size()
                                + " (registry size=" + map.size() + ")");
                        }
                    }

                    // 1b) v5 — ГЛАВНОЕ: виджеты масок (OverlayListener.overlays)
                    // КЭШИРУЮТ спеки в полях — удаление из реестра на них не влияет
                    // (лог 09-11: specs removed=2, оверлеи всё равно рисуются).
                    // Убираем сами виджеты из списка рендера (restore при выключении).
                    if (resolveMaskClasses(ctx)) {
                        if (stHeavy.get()) utils.etc.Overlays.hide(ctx, heavyOverlayC);
                        else utils.etc.Overlays.restore(ctx, heavyOverlayC);
                        if (stDiving.get()) utils.etc.Overlays.hide(ctx, divingOverlayC);
                        else utils.etc.Overlays.restore(ctx, divingOverlayC);
                    }

                    // 2) тыква (vanilla): перезапись/регистрация в TextureManager
                    if (stPumpkin.get()) {
                        for (String domain : new String[]{"minecraft", "rustme"}) {
                            Object rl = makeRl(domain, "textures/misc/pumpkinblur.png");
                            if (rl == null) continue;
                            Object tex = ((java.util.Map) texMap).get(rl);
                            if (tex == null) {
                                texRegister.invoke(texManager, rl, texShared);
                            } else if (tex != texShared) {
                                int texId = ((Integer) texIdGetter.invoke(tex)).intValue();
                                if (texId > 0 && !overwritten.contains(Integer.valueOf(texId))) {
                                    overwriteContent(texId);
                                    overwritten.add(Integer.valueOf(texId));
                                }
                            }
                        }
                    }
                    firstApplyDone = true;
                } catch (Throwable t) {
                    Log.error("AntiOverlay", "apply failed", t);
                }
            }
        });
    }

    /** Заливка 1×1 alpha=0 прямо в их GL-текстуру. */
    private void overwriteContent(int texId) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(4)
            .order(java.nio.ByteOrder.nativeOrder());
        buf.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        buf.flip();
        int prevTex = GL11.glGetInteger(0x8069);
        int prevProgram = GL11.glGetInteger(0x8B8D);
        if (prevProgram != 0) GL20.glUseProgram(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        if (prevProgram != 0) GL20.glUseProgram(prevProgram);
        Log.info("AntiOverlay", "texture overwritten: texId=" + texId);
    }

    /** RL или null. */
    private Object makeRl(String domain, String path) {
        try {
            return rlClass.getConstructor(String.class, String.class)
                .newInstance(domain, path);
        } catch (Throwable t) {
            return null;
        }
    }
}
