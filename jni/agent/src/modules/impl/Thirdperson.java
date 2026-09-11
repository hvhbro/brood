package modules.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import modules.api.Module;
import utils.etc.GameContext;
import utils.etc.Log;

/**
 * Thirdperson — возврат вырезанного F5 (вид от третьего лица).
 *
 * Реверс (javap -c, GameSettings.ilIiiilliI(Set), ветка getKeyTogglePerspective):
 *   holder = gs.IiIIllil (rustme.llIIiIiIiI)
 *   view   = holder.IliIIiIiI:I (0 = первое лицо, 1 = третье сзади, 2 = третье спереди)
 *   цикл ваниллы: view = (view + 1) % 3 (сброс при >2 в 0);
 *   сайд-эффекты ваниллы: view==0 -> mc.lliIllIiII(gs.IlIiIilliI()),
 *   view==1 -> mc.lliIllIiII(null), всегда -> renderGlobal(gs.Iillllil).lliIIIliII().
 * Читатель — RenderGlobal (читает IliIIiIiI каждый кадр), поэтому поле
 * event-driven: пишем ТОЛЬКО по нажатию (timing-гонки нет, как pressed у AutoSprint).
 * Родной KeyBindNode keyTogglePerspective, видимо, не забинден — циклим своим биндом.
 *
 * Бинд — стандартный bindKey модуля (меню: "Key:"/"Toggle:", ConfigManager
 * сохраняет M;Thirdperson;state;bind). Дефолт F5 (GLFW 294).
 * Нажатие циклит 0 -> 1 -> 2 -> 0. Выключение модуля возвращает первое лицо.
 *
 * Сайд-эффекты трогают GL (шейдеры/рендереры) — только на главном потоке
 * через runOnMainThread (у агентного потока нет GL-контекста); запись
 * int-поля — сразу (обычная память).
 */
public final class Thirdperson extends Module {
    /** GLFW_KEY_F5 (keyName меню: 290..301 -> F1..F12). */
    public static final int DEFAULT_KEY = 294;

    private static Thirdperson INSTANCE;
    /** Последний применённый вид (для камеры ESP и др. без рефлексии). */
    private static volatile int lastView = 0;

    /**
     * Текущий вид 0/1/2. Читает живое поле (перечитывание покрывает и
     * родной райтер, если его бинд вдруг активен); fallback — lastView.
     */
    public static int currentView() {
        try {
            Thirdperson inst = INSTANCE;
            if (inst != null && inst.holder != null && inst.viewField != null) {
                int v = inst.viewField.getInt(inst.holder);
                if (v >= 0 && v <= 2) return v;
            }
        } catch (Throwable ignore) {}
        return lastView;
    }

    private boolean keyWasDown;

    private boolean resolved;
    private long lastResolveAttempt;
    private Object holder;            // rustme.llIIiIiIiI (gs.IiIIllil)
    private Field viewField;          // IliIIiIiI:I (0/1/2)
    private Method mcShaderMethod;    // lIliIiiIiI.lliIllIiII(IIlIIliIiI)
    private Method viewEntityMethod;  // iilliIliiI.IlIiIilliI() -> Entity
    private Field rgField;            // gs.Iillllil (holder RenderGlobal)

    public Thirdperson() {
        super("Thirdperson", "Visuals", DEFAULT_KEY);
        INSTANCE = this;
        bindKey = DEFAULT_KEY;
        setState(true);
        Log.info("Thirdperson", "registered (cycle 0/1/2, default bind F5)");
    }

    /**
     * Вместо базового тоггла — цикл вида. Вызывается из главного цикла
     * агента для всех модулей (состояние не проверяется вызывающим).
     */
    @Override
    public void tickBind(GameContext ctx, boolean menuOpen) {
        int key = bindKey;
        if (key < 0) {
            keyWasDown = false;
            return;
        }
        boolean down;
        try {
            down = key <= 7 ? ctx.isMouseButtonDown(key) : ctx.isKeyDown(key);
        } catch (Throwable ignore) {
            return;
        }
        if (menuOpen) {
            keyWasDown = down;
            return;
        }
        if (down && !keyWasDown && isState()) {
            onPress(ctx);
        }
        keyWasDown = down;
    }

    @Override
    protected void onDisable() {
        try {
            lastView = 0;
            if (resolved && holder != null && viewField != null) {
                applyView(0);
                Log.info("Thirdperson", "disabled -> first person");
            }
        } catch (Throwable ignore) {}
    }

    private void onPress(GameContext ctx) {
        try {
            if (!ctx.inWorld) return;
            if (!resolve(ctx)) return;
            int cur;
            try {
                cur = viewField.getInt(holder);
            } catch (Throwable t) {
                Log.error("Thirdperson", "read failed", t);
                return;
            }
            int next = (cur + 1) % 3;
            if (next < 0) next = 0;
            applyView(next);
            Log.info("Thirdperson", "F5 -> " + next + " (" + viewName(next) + ")");
        } catch (Throwable t) {
            Log.error("Thirdperson", "press failed", t);
        }
    }

    /** Запись поля + сайд-эффекты ваниллы (GL-часть — на главном потоке). */
    private void applyView(final int v) {
        try {
            viewField.setInt(holder, v);
            lastView = v;
        } catch (Throwable t) {
            Log.error("Thirdperson", "write failed", t);
            return;
        }
        try {
            GameContext.get().runOnMainThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        GameContext ctx = GameContext.get();
                        if (v == 0) {
                            Object ent = viewEntityMethod.invoke(ctx.gs);
                            mcShaderMethod.invoke(ctx.mc, new Object[] { ent });
                        } else if (v == 1) {
                            mcShaderMethod.invoke(ctx.mc, new Object[] { null });
                        }
                        // RenderGlobal читаем ЗАНОВО: при включённом CustomSky/
                        // Ambience стоит наш прокси — дёргать надо ТЕКУЩИЙ
                        // инстанс, иначе split-brain чанков (кэш ломал рендер).
                        Object rg = rgField.get(ctx.gs);
                        if (rg != null) {
                            rg.getClass().getMethod("lliIIIliII").invoke(rg);
                        }
                    } catch (Throwable t) {
                        Log.error("Thirdperson", "side effects failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.error("Thirdperson", "queue failed", t);
        }
    }

    private static String viewName(int v) {
        if (v == 1) return "behind";
        if (v == 2) return "front";
        return "first";
    }

    private boolean resolve(GameContext ctx) {
        if (resolved) return holder != null && viewField != null;
        long now = System.currentTimeMillis();
        if (now - lastResolveAttempt < 2000L) return false;
        lastResolveAttempt = now;
        try {
            if (ctx.gs == null || ctx.mc == null || ctx.gameLoader == null) return false;
            Field holderF = getField(ctx.gs.getClass(), "IiIIllil");
            Object h = holderF.get(ctx.gs);
            if (h == null) {
                Log.info("Thirdperson", "resolve: holder null");
                return false;
            }
            holder = h;
            viewField = getField(holder.getClass(), "IliIIiIiI");
            Class entityClass = ctx.gameLoader.loadClass("rustme.IIlIIliIiI");
            mcShaderMethod = ctx.mc.getClass().getMethod("lliIllIiII", entityClass);
            viewEntityMethod = ctx.gs.getClass().getMethod("IlIiIilliI");
            rgField = getField(ctx.gs.getClass(), "Iillllil");
            Object rg = rgField.get(ctx.gs);
            if (rg == null) {
                Log.info("Thirdperson", "resolve: renderGlobal null");
                return false;
            }
            resolved = true;
            int cur = viewField.getInt(holder);
            Log.info("Thirdperson", "resolved: view=" + cur + " (" + viewName(cur) + ")");
            diagTheirBind(ctx);
            return true;
        } catch (Throwable t) {
            Log.error("Thirdperson", "resolve failed", t);
            return false;
        }
    }

    /** Диагностика: забинден ли родной togglePerspective (риск двойного цикла). */
    private void diagTheirBind(GameContext ctx) {
        try {
            if (ctx.nodeGetValue == null) ctx.resolveSprintKey();
            Object kb = ctx.keyBindingFor("getKeyTogglePerspective");
            if (kb == null) {
                Log.info("Thirdperson", "their togglePerspective node: none");
                return;
            }
            // keyCode через getKeyCode (если есть), иначе -2
            int code = -2;
            try {
                Method getCode = kb.getClass().getMethod("getKeyCode");
                Object v = getCode.invoke(kb);
                if (v instanceof Number) code = ((Number) v).intValue();
            } catch (Throwable ignore) {}
            Log.info("Thirdperson", "their togglePerspective: node present, keyCode=" + code);
        } catch (Throwable t) {
            Log.info("Thirdperson", "their togglePerspective diag failed: " + t);
        }
    }

    private static Field getField(Class c, String name) throws Throwable {
        try {
            Field f = c.getField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
    }
}
