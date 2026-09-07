package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import modules.api.Modules;
import utils.etc.GameContext;
import utils.etc.Log;

/**
 * Настоящий AutoSprint: каждый тик держит виртуальную клавишу спринта —
 * KeyBindNode спринта (модовые настройки) → KeyBinding, поле iilIIiIiI:Z (pressed).
 *
 * Игра сама в СВОЁМ тике (liIililiiI.liIIIiiilI, onLivingUpdate) читает
 * getKeySprint().isKeyDown() и вызывает setSprinting(true) — это честный
 * спринт: нативный FOV, ванильное поведение с едой/кромкой/полётом,
 * сервер получает корректный sprint-state.
 *
 * ВКЛЮЧЕНИЕ: клавиша X (GLFW 88, временно) — тап переключает модуль.
 * При выключении клавиша отпускается ОДИН раз (не спамим false,
 * иначе реальный спринт юзера перетирался бы каждый тик).
 */
public final class AutoSprint extends Module {
    public static final int TOGGLE_KEY = 88; // GLFW_KEY_X

    private boolean keyWasDown;   // edge-detect для тапа X
    private boolean lastWritten;  // что мы последний раз записали в pressed

    public AutoSprint() {
        super("AutoSprint");
        // стартуем включенным (как раньше)
        setState(true);
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    handleKey();
                    if (isState()) onTick(event);
                } catch (Throwable t) {
                    Log.error("AutoSprint", "onTick exception", t);
                }
            }
        });
        Log.info("AutoSprint", "registered (toggle: X)");
    }

    @Override
    protected void onDisable() {
        // отпускаем клавишу — игра сама снимет спринт
        // (сделает это onTick при следующем проходе: lastWritten=true)
    }

    private void handleKey() {
        GameContext ctx = GameContext.get();
        // toggle по X (tap)
        boolean xDown = ctx.isKeyDown(TOGGLE_KEY);
        if (xDown && !keyWasDown) {
            toggle();
            Log.info("AutoSprint", "toggled by X -> " + (isState() ? "ON" : "OFF"));
        }
        keyWasDown = xDown;
    }

    @Override
    public void onTick(TickEvent event) {
        GameContext ctx = GameContext.get();

        if (!event.isInWorld()) {
            // вышли из мира — отпускаем клавишу, чтобы она не осталась зажатой
            if (lastWritten) {
                forceSprintKey(false);
                lastWritten = false;
            }
            return;
        }

        if (isState()) {
            // держим клавишу каждый тик (перекрывает физические события — это autosprint)
            forceSprintKey(true);
            lastWritten = true;
        } else if (lastWritten) {
            // выключили — отпускаем один раз и больше не трогаем поле
            forceSprintKey(false);
            lastWritten = false;
        }
    }

    /**
     * Держит (state=true) или отпускает (state=false) клавишу спринта.
     * @return true если запись прошла
     */
    private boolean forceSprintKey(boolean state) {
        GameContext ctx = GameContext.get();
        // если init() не добрался (инжект из меню) — резолвим лениво
        if (ctx.kbPressedField == null && !ctx.resolveSprintKey()) return false;
        try {
            Object node = ctx.getSprintNode.invoke(ctx.keyBindingsCategory);
            if (node == null) return false;
            Object keyBinding = ctx.nodeGetValue.invoke(node);
            if (keyBinding == null || !ctx.keyBindingClass.isInstance(keyBinding)) return false;
            ctx.kbPressedField.setBoolean(keyBinding, state);
            if (state && !firstWriteLogged) {
                firstWriteLogged = true;
                Log.info("AutoSprint", "writing pressed=true");
            }
            return true;
        } catch (Throwable t) {
            Log.error("AutoSprint", "tick exception", t);
            return false;
        }
    }

    private boolean firstWriteLogged;
}
