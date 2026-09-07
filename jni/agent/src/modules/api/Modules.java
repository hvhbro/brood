package modules.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Реестр модулей (expensive-стиль): Module.name + state, отображается HUD'ом.
 * Регистрация — в конструкторе Module; порядок = порядок отображения.
 */
public final class Modules {

    private static final List<Module> MODULES = new ArrayList<Module>();

    private Modules() {}

    public static void register(Module m) {
        MODULES.add(m);
    }

    public static List<Module> all() {
        return MODULES;
    }

    public static Module get(String name) {
        for (Module m : MODULES) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }

    /** Совместимость со старым вызовом Modules.set(name, on). */
    public static void set(String name, boolean on) {
        Module m = get(name);
        if (m != null) m.setState(on);
    }

    /** Хук для HUD: вызывается при изменении состояния (анимации сами подхватят). */
    public static void refresh() {
        // пока пусто: CheatHud сам опрашивает state каждый кадр
    }
}
