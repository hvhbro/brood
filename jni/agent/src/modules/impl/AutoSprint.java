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
 * ВКЛЮЧЕНИЕ: бинд из меню (Module.tickBind), дефолтных клавиш нет.
 * При выключении клавиша отпускается ОДИН раз (не спамим false,
 * иначе реальный спринт юзера перетирался бы каждый тик).
 */
public final class AutoSprint extends Module {
    private boolean lastWritten;  // что мы последний раз записали в pressed

    public AutoSprint() {
        super("AutoSprint", "Combat");
        // стартуем включенным (как раньше)
        setState(true);
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (isState()) onTick(event);
                } catch (Throwable t) {
                    Log.error("AutoSprint", "onTick exception", t);
                }
            }
        });
        Log.info("AutoSprint", "registered (toggle: menu bind)");
    }

    @Override
    protected void onDisable() {
        // отпускаем клавишу — игра сама снимет спринт
        // (сделает это onTick при следующем проходе: lastWritten=true)
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

        if (MenuModule.isOpen()) {
            // в меню игра не двигает игрока — не держим спринт
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
