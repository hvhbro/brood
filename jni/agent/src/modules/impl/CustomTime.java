package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Method;

/**
 * Custom Time — клиентское время суток.
 *
 * ЦЕПОЧКА (дизасм 09-11, javap-верифицировано): World (rustme.IIlllIlIiI)
 * → getWorldInfo = illlillllI()Lrustme/liIllIlIiI; → setWorldTime =
 * iIlilIlllI(J)V — пара к геттеру liillIlllI()J (читает поле iIiIllllI:J),
 * который юзают World.getCelestialAngle iIiilllllI(F) (положение солнца/луны)
 * и calculateSkylightSubtracted IIilIllllI() (небесный свет) — т.е. время
 * сразу управляет и небом, и освещением.
 *
 * ЗАПИСЬ КЛИЕНТСКАЯ: сервер периодически шлёт TimeUpdate и перетирает поле —
 * поэтому применяем на КАЖДОМ TickEvent (цикл агента ~1мс), время держится
 * константой. Поле worldInfo берём заново каждый тик (мир меняется при
 * респавне/смене сервера).
 *
 * МОРГАНИЕ (фикс): агентного тика мало — между серверным пакетом (main thread)
 * и нашей перезаписью (agent thread) кадр успевает отрисоваться со серверным
 * временем. Поэтому та же запись дублируется на ГЛАВНОМ потоке в начале
 * мирового прохода (CheatRenderGlobal.IilIIIliII → writeNow): порядок на
 * main thread тотальный — [тик: пакеты] → [наша запись] → [рендер читает] —
 * и каждый кадр видит только наше время. Лайтмап обновляется раньше прохода
 * (mc.IllillIiII: сначала IiIIllIiII, потом llilllIiII) — там возможен лаг
 * в 1 кадр на серверных синках, глазом не ловится.
 *
 * Пресеты (vanilla 1.12.2): Day=6000 полдень, Sunset=12610, Night=15000,
 * Midnight=18000, Sunrise=23000. Клик по чипу пишет значение в слайдер
 * Time; Custom = ручное значение слайдера.
 */
public final class CustomTime extends Module {

    private static CustomTime INSTANCE;

    private final Module.ModeSetting stPreset = addMode("Preset",
        new String[]{"Day", "Sunset", "Night", "Midnight", "Sunrise", "Custom"}, 0);
    /** Тики суток 0..24000 (24000 = полные сутки). */
    private final Module.FloatSetting stTime = addSetting("Time", 0f, 24000f, 50f, 6000f);

    /** Тики пресетов (Custom — последний, слайдер не трогает). */
    private static final long[] PRESET_TICKS = {6000L, 12610L, 15000L, 18000L, 23000L};
    private static final int CUSTOM_IDX = 5;

    private int lastPreset = -1;
    private boolean resolvedOk;
    private Method getWorldInfoM; // World.illlillllI()Lrustme/liIllIlIiI;
    private Method setWorldTimeM; // WorldInfo.iIlilIlllI(J)V
    // кэш WorldInfo (мир стабилен — дёргать getWorldInfo каждый кадр не надо)
    private Object cachedWorld;
    private Object cachedWinfo;

    public CustomTime() {
        super("Custom Time", "Visuals");
        INSTANCE = this;
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (!isState() || !event.isInWorld()) return;
                    syncPreset();
                    applyTime();
                } catch (Throwable t) {
                    Log.error("CustomTime", "tick exception", t);
                }
            }
        });
        Log.info("CustomTime", "registered");
    }

    /** Нужен ли хук мирового прохода (для CheatRenderGlobal.sync). */
    public static boolean wants() {
        CustomTime i = INSTANCE;
        return i != null && i.isState();
    }

    /**
     * Запись времени с ГЛАВНОГО потока (начало мирового прохода, до всех
     * чтений рендера). Вызывается из CheatRenderGlobal.IilIIIliII.
     * Дешёвая (кэш WorldInfo), исключений наружу нет — хот-пас рендера.
     */
    public static void writeNow() {
        CustomTime inst = INSTANCE;
        if (inst == null || !inst.isState()) return;
        try {
            GameContext ctx = GameContext.get();
            if (!ctx.inWorld || ctx.world == null) return;
            if (!inst.resolve(ctx)) return;
            Object w = ctx.world;
            Object winfo = inst.cachedWinfo;
            if (w != inst.cachedWorld) {
                inst.cachedWorld = w;
                winfo = null;
            }
            if (winfo == null) {
                winfo = inst.getWorldInfoM.invoke(w);
                if (winfo == null) return;
                inst.cachedWinfo = winfo;
            }
            inst.setWorldTimeM.invoke(winfo, Long.valueOf((long) inst.stTime.value));
        } catch (Throwable ignore) {}
    }

    /** Клик по чипу пресета → значение в слайдер (Custom не трогает). */
    private void syncPreset() {
        int idx = stPreset.index();
        if (idx == lastPreset) return;
        lastPreset = idx;
        if (idx >= 0 && idx < PRESET_TICKS.length) {
            stTime.value = (float) PRESET_TICKS[idx];
        }
    }

    private boolean resolve(GameContext ctx) {
        if (resolvedOk) return true;
        try {
            Class worldC = ctx.gameLoader.loadClass("rustme.IIlllIlIiI");
            getWorldInfoM = worldC.getMethod("illlillllI");
            Class winfoC = ctx.gameLoader.loadClass("rustme.liIllIlIiI");
            setWorldTimeM = winfoC.getMethod("iIlilIlllI", long.class);
            resolvedOk = true;
            Log.info("CustomTime", "resolved (getWorldInfo=illlillllI, setWorldTime=iIlilIlllI)");
            return true;
        } catch (Throwable t) {
            return false; // тихо: мир ещё не загружен — ретрай следующим тиком
        }
    }

    private void applyTime() {
        GameContext ctx = GameContext.get();
        Object world = ctx.world;
        if (world == null) {
            resolvedOk = false; // новый мир — возможно, новый classloader
            return;
        }
        if (!resolve(ctx)) return;
        try {
            Object winfo = getWorldInfoM.invoke(world);
            if (winfo == null) return;
            setWorldTimeM.invoke(winfo, Long.valueOf((long) stTime.value));
        } catch (Throwable t) {
            Log.error("CustomTime", "apply failed", t);
        }
    }
}
