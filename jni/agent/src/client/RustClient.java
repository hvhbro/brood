package client;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.impl.AutoSprint;
import modules.impl.FullBright;
import utils.etc.GameContext;
import utils.etc.Log;

/**
 * Entry point клиента. Вызывается нативным DLL через install().
 * Запускает главный цикл: GameContext.update() + EventBus.post(TickEvent).
 *
 * Структура (expensive-стиль):
 *   client/RustClient.java     — этот класс: entry + главный цикл
 *   events/EventBus.java       — событийная шина (impl-события в events.impl)
 *   modules/api/Modules.java   — реестр модулей
 *   modules/impl/AutoSprint.java — модуль AutoSprint (toggle: X)
 *   utils/etc/GameContext.java — кэш игровых объектов + инъекция HUD
 *   utils/etc/Log.java         — логгер (C:\Logs
oslow_agent.log)
 *   utils/render/CheatHud.java — ArrayList (рисует CheatIngame на главном потоке)
 *   rustme/CheatIngame.java    — подменный GuiIngame (наследник игрового класса)
 */
public class RustClient implements Runnable {
    private AutoSprint autoSprint;
    

    private static void llog(String s) {
        Log.info("Agent", s);
    }

    public static void install() {
        try {
            Thread t = new Thread(new RustClient(), "CheatMain");
            // без лямбды (invokedynamic в защищённой JVM ненадёжен)
            t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread th, Throwable ex) {
                    Log.error("Agent", "uncaught, thread dying", ex);
                }
            });
            t.setDaemon(true);
            t.start();
            llog("installed, main thread started");
        } catch (Throwable e) {
            Log.error("Agent", "install failed", e);
        }
    }

    public void run() {
        try { Thread.sleep(2000L); } catch (InterruptedException e) { return; }
        llog("main loop starting");

        GameContext ctx = GameContext.get();

        // Инициализация GameContext (может занять несколько попыток, если меню)
        int attempts = 0;
        while (!ctx.init()) {
            attempts++;
            if (attempts > 60) { // ~60 секунд
                llog("context init failed after 60 attempts, exiting");
                return;
            }
            try { Thread.sleep(1000L); } catch (InterruptedException e) { return; }
        }
        llog("context initialized");

        // Модули (try/catch: умирание здесь раньше было невидимым)
        try {
            autoSprint = new AutoSprint();
            new FullBright();
            new modules.impl.Esp();
        } catch (Throwable t) {
            Log.error("Agent", "module init failed", t);
            return;
        }
        llog("modules created");

        // HUD: подменяем GuiIngame на наш (рендер-метод зовёт игра на главном потоке)
        try {
            if (ctx.ensureHud()) {
                llog("hud installed (CheatIngame swap)");
            } else {
                llog("hud install pending — retry in loop");
            }
        } catch (Throwable t) {
            Log.error("Agent", "HUD init failed", t);
        }

        // Главный цикл: 1мс + heartbeat каждые 10с
        long lastBeat = System.currentTimeMillis();
        while (true) {
            try {
                boolean inWorld = ctx.update();
                if (!ctx.hudInstalled) ctx.ensureHud(); // ленивая доустановка
                EventBus.post(new TickEvent(inWorld));
                long now = System.currentTimeMillis();
                if (now - lastBeat >= 10000L) {
                    lastBeat = now;
                    llog("heartbeat, inWorld=" + inWorld);
                }
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Log.error("Agent", "main loop exception", t);
                try { Thread.sleep(500L); } catch (InterruptedException e2) { return; }
            }
        }
    }
}

