package modules.api;

import events.EventBus;

/**
 * Базовый класс модуля (expensive-стиль): name, state, toggle.
 * Наследники переопределяют onEnable/onDisable/onTick.
 */
public abstract class Module {
    public final String name;
    private boolean state;

    protected Module(String name) {
        this.name = name;
        Modules.register(this);
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
