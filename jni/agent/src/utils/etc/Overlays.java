package utils.etc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Доступ к списку HUD-оверлеев форка (OverlayListener = rustme.liiiilIliI):
 * статик ArrayList с инстансами виджетов (HotbarOverlay, HeavyHelmetOverlay,
 * DivingMaskOverlay, bleeding/freezing...). Каждый кадр игра итерирует список
 * и рисует виджеты — удаление инстанса из списка = оверлей не рисуется.
 *
 * Имена верифицированы дизасмом 09-11: OverlayListener.clinit собирает 22
 * фабрики KFunction (индекс 2 = HeavyHelmetOverlay::new, 3 = DivingMaskOverlay::new,
 * 6 = HotbarOverlay::new — порядок совпадает со старой декомпиляцией).
 *
 * ВСЕ мутации списка — через ctx.runOnMainThread: рендер-поток итерирует
 * список каждый кадр, удаление с агентного потока = CME-крэш.
 */
public final class Overlays {

    private static boolean resolved;
    private static List overlays;   // OverlayListener.overlays (ArrayList)
    private static final Map<Class, List> removedByClass = new HashMap<Class, List>();

    private Overlays() {}

    private static boolean resolve(GameContext ctx) {
        if (resolved) return overlays != null;
        try {
            Class c = ctx.gameLoader.loadClass("rustme.liiiilIliI");
            for (Field f : c.getDeclaredFields()) {
                // overlays = static final List, конкретно ArrayList (список фабрик — Arrays$ArrayList)
                if ((f.getModifiers() & 8) != 0 && List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof ArrayList) {
                        overlays = (List) v;
                        resolved = true; // ОБЯЗАТЕЛЬНО: без этого resolve спамит каждый кадр
                        Log.info("Overlays", "resolved (" + overlays.size() + " widgets)");
                        return true;
                    }
                }
            }
            Log.error("Overlays", "overlays list NOT found", null);
        } catch (Throwable t) {
            Log.error("Overlays", "resolve failed", t);
        }
        resolved = true;
        return false;
    }

    private static int count(Class widgetClass) {
        int n = 0;
        if (overlays == null) return 0;
        for (int i = 0; i < overlays.size(); i++) {
            if (widgetClass.isInstance(overlays.get(i))) n++;
        }
        return n;
    }

    /** Спрятать оверлей: убрать все widgetClass-инстансы из списка (идемпотентно). */
    public static void hide(final GameContext ctx, final Class widgetClass) {
        if (!resolve(ctx) || count(widgetClass) == 0) return;
        ctx.runOnMainThread(new Runnable() {
            @Override
            public void run() {
                List removed = removedByClass.get(widgetClass);
                if (removed == null) {
                    removed = new ArrayList();
                    removedByClass.put(widgetClass, removed);
                }
                try {
                    for (int i = overlays.size() - 1; i >= 0; i--) {
                        Object o = overlays.get(i);
                        if (widgetClass.isInstance(o)) {
                            removed.add(o);
                            overlays.remove(i);
                        }
                    }
                } catch (Throwable t) {
                    Log.error("Overlays", "hide failed", t);
                }
            }
        });
    }

    /** Вернуть спрятанные виджеты (при выключении модуля). */
    public static void restore(final GameContext ctx, final Class widgetClass) {
        if (!resolve(ctx)) return;
        ctx.runOnMainThread(new Runnable() {
            @Override
            public void run() {
                List removed = removedByClass.remove(widgetClass);
                if (removed == null || overlays == null) return;
                try {
                    for (int i = 0; i < removed.size(); i++) {
                        if (!overlays.contains(removed.get(i))) overlays.add(removed.get(i));
                    }
                } catch (Throwable t) {
                    Log.error("Overlays", "restore failed", t);
                }
            }
        });
    }
}
