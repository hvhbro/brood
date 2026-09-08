package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import rustme.CheatMenuScreen;
import utils.etc.GameContext;
import utils.etc.Log;

/**
 * Modern-меню (порт дизайна rock Class215). Toggle: RSHIFT (GLFW 344).
 *
 * Открытие: только с главного потока. Из агентного потока — gs.IllIIiiiil(Runnable)
 * (addScheduledTask форка, очередь исполняется внутри runGameLoop), в задаче —
 * gs.IIlIiilliI(screen) (displayGuiScreen).
 *
 * Закрытие: ESC или RSHIFT внутри экрана (CheatMenuScreen.keyTyped →
 * displayGuiScreen(null) + notifyClosed). onGuiClosed экрана тоже дергает
 * notifyClosed (если экран заменили чем-то другим).
 *
 * suppressUntilRelease: после закрытия по RSHIFT клавиша ещё зажата —
 * не переоткрывать, пока не отпустят (как I_field_5a в rock MenuModule).
 */
public final class MenuModule extends Module {
    public static final int TOGGLE_KEY = 344; // GLFW_KEY_RIGHT_SHIFT

    private static volatile boolean menuOpen;
    private static volatile boolean suppressUntilRelease;
    private boolean keyWasDown;

    public MenuModule() {
        super("Menu", "Misc", TOGGLE_KEY);
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    handleKey();
                } catch (Throwable t) {
                    Log.error("Menu", "tick exception", t);
                }
            }
        });
        Log.info("Menu", "registered (toggle: RSHIFT)");
    }

    /** true, пока открыт наш экран (модули не должны ловить свои тогглы). */
    public static boolean isOpen() {
        return menuOpen;
    }

    /** Экран закрылся (из самого экрана или заменился другим). */
    public static void notifyClosed() {
        menuOpen = false;
    }

    /** Не переоткрывать по RSHIFT, пока клавишу не отпустят. */
    public static void suppressUntilRelease() {
        suppressUntilRelease = true;
    }

    private void handleKey() {
        GameContext ctx = GameContext.get();
        boolean down = ctx.isKeyDown(TOGGLE_KEY);
        if (suppressUntilRelease) {
            if (!down) suppressUntilRelease = false;
            keyWasDown = down;
            return;
        }
        if (down && !keyWasDown) {
            toggle();
        }
        keyWasDown = down;
    }

    @Override
    protected void onEnable() {
        openScreen();
    }

    @Override
    protected void onDisable() {
        // НИКАКОГО displayGuiScreen(null) отсюда! Экран закрывает себя сам
        // (closeByModule -> gs.iIIIIilliI: форк сам возвращает мышь в игру).
        // Если шедулить null здесь, задача исполнится ПОСЛЕ grab из closeScreen
        // и снова отпустит мышь -> курсор висит + камера сломана (гонка).
        menuOpen = false;
    }

    private void openScreen() {
        if (menuOpen) return;
        GameContext ctx = GameContext.get();
        if (ctx.gs == null) {
            Log.info("Menu", "gs not ready, state rolled back");
            setState(false);
            return;
        }
        menuOpen = true;
        scheduleDisplay(new CheatMenuScreen());
    }

    /** Тост для юзера из экрана не нужен; state синхронизирует экран. */

    private void scheduleDisplay(final CheatMenuScreen screen) {
        try {
            GameContext ctx = GameContext.get();
            if (ctx.gs == null) {
                menuOpen = false;
                return;
            }
            // gs.IllIIiiiil(Runnable) = addScheduledTask (ISectionListener форка).
            // Через reflection: метод возвращает ListenableFuture, а guava
            // в компиляционном classpath отсутствует.
            java.lang.reflect.Method addTask = ctx.gs.getClass()
                .getMethod("IllIIiiiil", Runnable.class);
            addTask.invoke(ctx.gs, new Runnable() {
                @Override
                public void run() {
                    try {
                        // guiScale=2 ДО создания экрана: rock-пропорции (960x54x)
                        CheatMenuScreen.applyGameScale();
                        rustme.iilliIliiI gs = (rustme.iilliIliiI) GameContext.get().gs;
                        if (gs == null) return;
                        gs.IIlIiilliI(new CheatMenuScreen());
                        Log.info("Menu", "screen opened");
                    } catch (Throwable t) {
                        menuOpen = false;
                        Log.error("Menu", "display failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            menuOpen = false;
            Log.error("Menu", "schedule failed", t);
        }
    }
}
