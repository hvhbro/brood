package rustme;

import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Прокси RenderGlobal (подмена объекта мирового рендера, паттерн CheatIngame/
 * ViewModelRenderer): живёт в gs.Iillllil (Object-поле GameSettings, доказано
 * дизасмом mc.llilllIiII — каждый кадр грузит RenderGlobal именно оттуда).
 *
 * RenderGlobal stateful (чанк-листы 69696 cap и ~80 полей состояния), поэтому
 * новый инстанс НЕЖИЗНЕСПОСОБЕН: при свопе копируем ВСЕ non-static поля
 * оригинала в прокси (ссылки шарятся — состояние сохраняется). Статики общие
 * через класс, их копировать не надо. Ctor (gs) побочек не имеет (только
 * коллекции, проверено дизасмом) — создаётся один раз и переиспользуется;
 * при смене мира (игра положила новый оригинал) своп повторяется.
 *
 * Точки замены (верифицированы дизасмом mc.llilllIiII, порядок вызовов):
 *   @360  IilIIIliII(FI)V — небо, ПЕРВЫМ проходом. Здесь же (до всех чтений
 *         рендера) CustomTime.writeNow() фиксирует время кадра — иначе кадр
 *         успевает увидеть серверное время между пакетом и агентским тиком
 *         (моргание). При включённом CustomSky рисуем процедурное звёздное
 *         небо ВМЕСТО ванильного (super не зовём); террейн идёт после и сам
 *         перекрывает небо где нужно.
 *   @679/756/808 IIliIIliII(layer,D,I,Entity)I — 3 слоя террейна (до сущностей
 *         @1276, руки и HUD). При включённом Ambience после super-террейна
 *         рисуем multiply-тинт с маской по глубине (GREATER: проходит только
 *         записанная террейном глубина <1.0; небо=1.0 мимо) — сущности, рука,
 *         облака и небо остаются чистыми БЕЗ пиксельных эвристик.
 *
 * Своп/возврат — раз в кадр из CheatIngame (главный поток): sync() ставит
 * прокси когда нужен хоть один модуль, возвращает оригинал когда оба выкл.
 */
public class CheatRenderGlobal extends llIlIiiIiI {

    private static volatile Object originalGlobal; // llIlIiiIiI
    private static volatile CheatRenderGlobal proxy;
    private static volatile Field holderField;    // gs.Iillllil
    private static volatile boolean holderResolved;
    private static volatile long lastErr;

    public CheatRenderGlobal(iilliIliiI gs) {
        super(gs);
    }

    @Override
    public void IilIIIliII(float partialTicks, int pass) {
        try {
            // время кадра — ДО любых чтений рендера (фикс моргания CustomTime)
            modules.impl.CustomTime.writeNow();
        } catch (Throwable t) {
            errThrottled("time write failed", t);
        }
        try {
            if (modules.impl.CustomSky.drawSky()) return;
        } catch (Throwable t) {
            errThrottled("sky override failed", t);
        }
        super.IilIIIliII(partialTicks, pass);
    }

    @Override
    public int IIliIIliII(iIlIiilIiI layer, double partialTicks, int p, IIlIIliIiI e) {
        int n = super.IIliIIliII(layer, partialTicks, p, e);
        try {
            modules.impl.Ambience.tintTerrain();
        } catch (Throwable t) {
            errThrottled("terrain tint failed", t);
        }
        return n;
    }

    /** Раз в кадр из CheatIngame: своп прокси / возврат оригинала. */
    public static void sync(GameContext ctx) {
        try {
            if (ctx == null || ctx.gs == null || !ctx.inWorld) return;
            if (!holderResolved) {
                holderResolved = true;
                try {
                    holderField = ctx.gs.getClass().getField("Iillllil");
                    holderField.setAccessible(true);
                } catch (Throwable noName) {
                    holderField = null;
                }
                if (holderField == null) {
                    // запасной путь: поле по типу инстанса
                    try {
                        Class rgC = ctx.gameLoader.loadClass("rustme.llIlIiiIiI");
                        for (Field f : ctx.gs.getClass().getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers())) continue;
                            try {
                                f.setAccessible(true);
                                Object v = f.get(ctx.gs);
                                if (v != null && rgC.isInstance(v)) {
                                    holderField = f;
                                    break;
                                }
                            } catch (Throwable ignore) {}
                        }
                    } catch (Throwable ignore) {}
                }
                if (holderField == null) {
                    Log.error("Sky", "RenderGlobal holder NOT found", null);
                }
            }
            if (holderField == null) return;
            boolean want = modules.impl.CustomSky.wants() || modules.impl.Ambience.wants()
                || modules.impl.CustomTime.wants();
            Object cur;
            try {
                cur = holderField.get(ctx.gs);
            } catch (Throwable t) {
                errThrottled("holder read failed", t);
                return;
            }
            if (want) {
                if (cur instanceof CheatRenderGlobal) return; // уже наш
                if (cur == null || !(cur instanceof llIlIiiIiI)) return;
                if (proxy == null) {
                    proxy = new CheatRenderGlobal((iilliIliiI) ctx.gs);
                }
                copyFields(cur, proxy);
                try {
                    holderField.set(ctx.gs, proxy);
                } catch (Throwable t) {
                    errThrottled("swap failed", t);
                    return;
                }
                originalGlobal = cur;
                Log.info("Sky", "RenderGlobal swapped (original " + cur.getClass().getName()
                    + " want sky=" + modules.impl.CustomSky.wants()
                    + " amb=" + modules.impl.Ambience.wants()
                    + " time=" + modules.impl.CustomTime.wants() + ")");
            } else {
                if (cur instanceof CheatRenderGlobal && originalGlobal != null) {
                    try {
                        // вернуть оригиналу свежее состояние (иначе его примитивы
                        // заморожены на моменте свопа — split-brain после disable)
                        copyFields(cur, originalGlobal);
                        holderField.set(ctx.gs, originalGlobal);
                        Log.info("Sky", "RenderGlobal restored");
                    } catch (Throwable t) {
                        errThrottled("restore failed", t);
                    }
                }
            }
        } catch (Throwable t) {
            errThrottled("sync failed", t);
        }
    }

    /** Копия всех non-static полей иерархии llIlIiiIiI (состояние чанков). */
    private static void copyFields(Object src, Object dst) throws Exception {
        Class c = llIlIiiIiI.class;
        int n = 0;
        while (c != null && c != Object.class) {
            Field[] fs = c.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                Field f = fs[i];
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                f.set(dst, f.get(src));
                n++;
            }
            c = c.getSuperclass();
        }
        Log.info("Sky", "field copy: " + n + " fields");
    }

    private static void errThrottled(String msg, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastErr < 5000L) return;
        lastErr = now;
        Log.error("Sky", msg, t);
    }

    /**
     * Компактный снапшот GL-состояния для аудита утечек:
     * blend,src,dst|depthTest,func,mask|program,activeUnit. Читается через
     * lwjglx-шим (прямой делегат нативного GL11 — значения правдивые).
     * Вызывать на рендер-потоке.
     */
    public static String glState() {
        try {
            int blend = org.lwjglx.opengl.GL11.glGetInteger(0x0BE2);
            int src = org.lwjglx.opengl.GL11.glGetInteger(0x0BE1);
            int dst = org.lwjglx.opengl.GL11.glGetInteger(0x0BE0);
            int dt = org.lwjglx.opengl.GL11.glGetInteger(0x0B71);
            int func = org.lwjglx.opengl.GL11.glGetInteger(0x0B74);
            int mask = org.lwjglx.opengl.GL11.glGetInteger(0x0B72);
            int prog = org.lwjglx.opengl.GL11.glGetInteger(0x8B8D);
            int unit = org.lwjglx.opengl.GL11.glGetInteger(0x84E0);
            return blend + "," + src + "," + dst + "|" + dt + "," + func + "," + mask
                + "|" + prog + "," + unit;
        } catch (Throwable t) {
            return "read-failed";
        }
    }
}
