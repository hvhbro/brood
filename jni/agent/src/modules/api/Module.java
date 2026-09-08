package modules.api;

import events.EventBus;

/**
 * Базовый класс модуля (expensive-стиль): name, state, toggle.
 * Наследники переопределяют onEnable/onDisable/onTick.
 */
public abstract class Module {
    public final String name;
    /** Категория для меню ("Combat", "Visuals"...). */
    public final String category;
    /** Клавиша тоггла «из кода» (агент-слой); меню её не трогает. */
    public final int toggleKey;
    /** Клавиша, назначенная в меню (GLFW); тогглит модуль в тике. -1 = нет. */
    public int bindKey = -1;
    private boolean bindWasDown;
    private boolean state;

    protected Module(String name) {
        this(name, "Misc", -1);
    }

    protected Module(String name, String category, int key) {
        this.name = name;
        this.category = category;
        this.toggleKey = key;
        Modules.register(this);
    }

    /** Тик бинда из меню (вызывается из главного цикла агента). */
    public void tickBind(utils.etc.GameContext ctx, boolean menuOpen) {
        if (bindKey <= 0) return;
        try {
            boolean down = ctx.isKeyDown(bindKey);
            if (menuOpen) {
                bindWasDown = down;
                return;
            }
            if (down && !bindWasDown) {
                toggle();
                utils.etc.Log.info(name, "bind -> " + (isState() ? "ON" : "OFF"));
            }
            bindWasDown = down;
        } catch (Throwable ignore) {}
    }

    public boolean isState() { return state; }

    public void toggle() { setState(!state); }

    public void setState(boolean v) {
        if (state == v) return;
        state = v;
        Modules.refresh();
        if (v) onEnable(); else onDisable();
    }

    protected void onEnable() {}
    protected void onDisable() {}

    /** Тик модуля (вызывается из главного цикла, если включен). */
    public void onTick(events.EventBus.TickEvent event) {}
}
