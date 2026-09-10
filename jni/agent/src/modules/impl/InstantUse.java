package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * InstantUse — ВОЗВРАТ к shuse-версии (по требованию юзера 09-10:
 * «верни до фикса то что открывается интерфейс» — эта версия РАБОТАЛА).
 *
 * МЕХАНИКА: по нажатию use-клавиши мода (getKeyAction — ПКМ по умолчанию)
 * шлём клиентский пакет shuse ("rust:misc:shuse", PerformShortUseActionPacketData)
 * СРАЗУ, не дожидаясь хинта/корутины игры. Сервер выполняет короткое
 * использование немедленно — для интерактивных предметов (спальник и т.п.)
 * открывается интерфейс без задержки.
 *
 * ИГРА ШЛЁТ shuse ТОЛЬКО из HintOverlay.llIIillIil (короткое использование
 * при старте/отпуске) — мы шлём раньше игры, потому действие мгновенное.
 *
 * duration-форс (lguse-схема чита) УДАЛЁН: тест юзера 09-10 показал, что
 * сервер не завершает long-use по раннему lguse — вернулись к рабочему пути.
 */
public final class InstantUse extends Module {

    public static InstantUse INSTANCE;

    private static final long MIN_INTERVAL_MS = 300L;

    private boolean wasPressed;
    private long lastSend;
    private long lastDiag;

    private boolean resolved;
    private long lastResolveAttempt;
    private Method helperSend;          // liliiilliI.IlIIIIIIIl(player, channel, data)
    private Object shusePacket;         // PerformShortUseActionPacketData (пустой)
    private Object actionKeyBinding;    // KeyBinding use-клавиши (getKeyAction)

    public InstantUse() {
        super("InstantUse", "Combat");
        INSTANCE = this;
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (isState()) onTick(event);
                } catch (Throwable t) {
                    Log.error("InstantUse", "onTick exception", t);
                }
            }
        });
        Log.info("InstantUse", "registered (shuse on use-key press)");
    }

    @Override
    public void onTick(TickEvent event) {
        GameContext ctx = GameContext.get();
        if (!event.isInWorld() || ctx.player == null) {
            wasPressed = false;
            return;
        }
        try {
            if (!resolve(ctx)) return;

            boolean pressed = ctx.kbPressedField != null
                && actionKeyBinding != null
                && ctx.kbPressedField.getBoolean(actionKeyBinding);
            long now = System.currentTimeMillis();

            // edge-detect: только на ПЕРЕХОД отпущено→нажато (не спамим холд)
            if (pressed && !wasPressed && now - lastSend >= MIN_INTERVAL_MS) {
                helperSend.invoke(null, ctx.player, "rust:misc:shuse", shusePacket);
                lastSend = now;
                if (now - lastDiag > 1500L) {
                    lastDiag = now;
                    Log.info("InstantUse", "shuse sent (use-key press)");
                }
            }
            wasPressed = pressed;
        } catch (Throwable t) {
            Log.error("InstantUse", "tick failed", t);
        }
    }

    private boolean resolve(GameContext ctx) {
        if (resolved) return helperSend != null;
        long now = System.currentTimeMillis();
        if (now - lastResolveAttempt < 2000L) return false;
        lastResolveAttempt = now;
        try {
            // хелпер отправки payload: static liliiilliI.IlIIIIIIIl(SP player, String channel, PayloadPacketData)
            Class helper = ctx.gameLoader.loadClass("rustme.liliiilliI");
            Class ppdCls = ctx.gameLoader.loadClass(
                "ru.meproject.rustme.vanilla.network.common.model.PayloadPacketData");
            Method[] ms = helper.getMethods();
            for (int i = 0; i < ms.length; i++) {
                Method m = ms[i];
                if (!m.getName().equals("IlIIIIIIIl")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 3 && ps[1] == String.class && ppdCls.isAssignableFrom(ps[2])) {
                    helperSend = m;
                    break;
                }
            }
            if (helperSend == null) {
                Log.error("InstantUse", "helper method not found", null);
                return false;
            }
            Class shuseCls = ctx.gameLoader.loadClass(
                "ru.meproject.rustme.vanilla.network.misc.hint.PerformShortUseActionPacketData");
            Constructor ctor = shuseCls.getDeclaredConstructor();
            ctor.setAccessible(true);
            shusePacket = ctor.newInstance();

            // use-клавиша мода (та самая, что запускает hint-корутину)
            if (ctx.resolveSprintKey()) {
                actionKeyBinding = ctx.keyBindingFor("getKeyAction");
            }
            if (actionKeyBinding == null) {
                Log.info("InstantUse", "action key not resolved yet (retry)");
                return false;
            }
            resolved = true;
            Log.info("InstantUse", "ready (helper + shuse packet + action key OK)");
            return true;
        } catch (Throwable t) {
            Log.error("InstantUse", "resolve failed", t);
            return false;
        }
    }
}
