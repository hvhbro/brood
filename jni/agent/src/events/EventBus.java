package events;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import utils.etc.Log;

/**
 * Простая событийная шина чита.
 * Модули регистрируют слушатели, главный цикл диспатчит события.
 */
public final class EventBus {
    private static final Map<Class<? extends Event>, List<Listener<?>>> listeners = new HashMap<>();

    private EventBus() {}

    public static <T extends Event> void subscribe(Class<T> eventType, Listener<T> listener) {
        // без computeIfAbsent+лямбды: invokedynamic в защищённой JVM ненадёжен
        List<Listener<?>> list = listeners.get(eventType);
        if (list == null) {
            list = new CopyOnWriteArrayList<>();
            listeners.put(eventType, list);
        }
        list.add(listener);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Event> void post(T event) {
        List<Listener<?>> list = listeners.get(event.getClass());
        if (list == null) return;
        for (Listener<?> l : list) {
            try {
                ((Listener<T>) l).onEvent(event);
            } catch (Throwable t) {
                Log.error("EventBus", "listener exception for " + event.getClass().getSimpleName(), t);
            }
        }
    }

    public interface Listener<T extends Event> {
        void onEvent(T event);
    }

    // ===== События =====

    /** Вызывается каждый игровой тик (после update world/player). */
    public static class TickEvent extends Event {
        private final boolean inWorld;
        public TickEvent(boolean inWorld) { this.inWorld = inWorld; }
        public boolean isInWorld() { return inWorld; }
    }

    /** Вызывается когда игрок приседает (edge: начало). */
    public static class SneakStartEvent extends Event {
        private final Object player;
        public SneakStartEvent(Object player) { this.player = player; }
        public Object getPlayer() { return player; }
    }

    /** Вызывается когда игрок перестаёт приседать. */
    public static class SneakStopEvent extends Event {
        private final Object player;
        public SneakStopEvent(Object player) { this.player = player; }
        public Object getPlayer() { return player; }
    }

    public static abstract class Event {
        private boolean cancelled;
        public boolean isCancelled() { return cancelled; }
        public void setCancelled(boolean v) { cancelled = v; }
    }
}
