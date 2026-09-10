package modules.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.net.PacketHook;

/**
 * PacketFly: полёт с мутацией исходящих C03-пакетов (анализ 09-08).
 *
 * СХЕМА:
 *  - Позиция пакета = реальная позиция игрока (iiIlililiI читает геттеры перед
 *    отправкой), поэтому двигаем ЛОКАЛЬНО через motion-поля (доказанный вход,
 *    ZNANIA 11.1: travel их только читает) — пакет уносит новые координаты сам.
 *  - Вертикаль: jump→вверх (0.16-0.08 гравитации = +0.08 бл/тик, «медленно
 *    поднимается»), sneak→вниз, ничего→парение (0.08-0.08=0).
 *  - Горизонталь: ванильная формула moveRelative от yaw+инпут, масштаб 0.4 бл/тик
 *    (8 бл/с), клампы MAX_STEP/MAX_TOTAL.
 *  - Пакетный хук (PacketHook): спуф onGround=true — партиклы ходьбы у наблюдателей
 *    + гасит серверный floating-check. packetDx/Dy/Dz = 0 (канал готов для фазы 3,
 *    сейчас дублировал бы локальное движение).
 *
 * Клавиша V (GLFW 86). Стартует ВЫКЛЮЧЕННЫМ.
 */
public final class PacketFly extends Module {

    public static final int TOGGLE_KEY = 86; // GLFW_KEY_V
    private static final int GLFW_KEY_SPACE = 32;

    // Лимиты скорости (блоков/тик)
    private static final double MOVE_SCALE = 0.40;   // горизонталь (8 бл/с)
    private static final double MAX_STEP = 0.5;      // кламп на ось
    private static final double MAX_TOTAL = 0.6;     // кламп по модулю
    private static final double MOTION_UP = 0.16;    // запись при space (net +0.08)
    private static final double MOTION_HOVER = 0.08; // запись в покое (net 0)

    private boolean keyWasDown;
    private Method yawGetter;      // IIiIillIII()F — XOR-самодекод (ZNANIA 14.1)
    private Field jumpField;       // input holder iiIiIIiII:Z = space (дизасм SP @598/@849)

    // Автонырок: серверный floating-check копит 80 тиков подряд с dY>=-0.03125
    // и кикает. Каждые ~3с даём окно 250мс с чистым падением (dY~-0.08/тик) —
    // счётчик обнуляется, игрок опускается на ~0.4 блока (незаметно).
    private long nextDipAt;
    private long dipUntil;
    private boolean dipLogged;

    public PacketFly() {
        super("PacketFly");
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    handleKey();
                    if (isState()) onTick(event);
                } catch (Throwable t) {
                    Log.error("PacketFly", "onTick exception", t);
                }
            }
        });
        Log.info("PacketFly", "registered (toggle: V, default OFF)");
    }

    private void handleKey() {
        GameContext ctx = GameContext.get();
        boolean down = ctx.isKeyDown(TOGGLE_KEY);
        if (down && !keyWasDown) {
            toggle();
            Log.info("PacketFly", "toggled by V -> " + (isState() ? "ON" : "OFF"));
        }
        keyWasDown = down;
    }

    @Override
    protected void onDisable() {
        // пакеты снова идут как есть; motion-поля больше не пишем — физика вернётся
        PacketHook.packetDx = 0.0;
        PacketHook.packetDy = 0.0;
        PacketHook.packetDz = 0.0;
        PacketHook.spoofGround = false;
    }

    @Override
    public void onTick(TickEvent event) {
        GameContext ctx = GameContext.get();

        if (!event.isInWorld() || ctx.player == null) {
            PacketHook.spoofGround = false;
            return;
        }
        if (!PacketHook.ensureInstalled(ctx)) return;

        try {
            applyFly(ctx);
        } catch (Throwable t) {
            Log.error("PacketFly", "applyFly failed", t);
        }
    }

    private void applyFly(GameContext ctx) throws Exception {
        // --- резолвы один раз ---
        if (yawGetter == null) {
            yawGetter = ctx.localSpClass.getMethod("IIiIillIII");
        }
        if (jumpField == null && ctx.movementInput != null) {
            try {
                jumpField = ctx.movementInput.getClass().getField("iiIiIIiII");
            } catch (Throwable t) {
                Log.info("PacketFly", "jump field not found: " + t);
            }
        }

        // --- вертикаль: space вверх, sneak вниз, иначе парение (записи motionY) ---
        boolean jump = false;
        if (jumpField != null && ctx.movementInput != null) {
            try {
                jump = jumpField.getBoolean(ctx.movementInput);
            } catch (Throwable ignore) {}
        }
        long now = System.currentTimeMillis();
        if (nextDipAt == 0L) {
            nextDipAt = now + 3000L; // первый нырок через 3с после включения
        }
        boolean dipping = now < dipUntil;
        if (!jump && !dipping && now >= nextDipAt && nextDipAt != 0L) {
            dipUntil = now + 250L;   // 5 тиков чистого падения: dY=-0.08/тик — счётчик в 0
            nextDipAt = now + 3000L;
            if (!dipLogged) {
                dipLogged = true;
                Log.info("PacketFly", "auto-dip started (anti floating-check)");
            }
        }
        if (jump) {
            ctx.motionYField.setDouble(ctx.player, MOTION_UP);
        } else if (dipping) {
            ctx.motionYField.setDouble(ctx.player, 0.0); // net -0.08: нырок, сброс floating-счётчика
        } else if (ctx.isSneaking != null && ((Boolean) ctx.isSneaking.invoke(ctx.player)).booleanValue()) {
            ctx.motionYField.setDouble(ctx.player, 0.0); // net -0.08: плавный спуск
        } else {
            ctx.motionYField.setDouble(ctx.player, MOTION_HOVER); // net 0: парение
        }

        // --- горизонталь: moveRelative-формула от yaw+инпут, клампы ---
        float strafe = 0.0f, forward = 0.0f;
        if (ctx.moveForwardField != null && ctx.movementInput != null) {
            forward = ctx.moveForwardField.getFloat(ctx.movementInput);
            strafe = ctx.moveStrafeField.getFloat(ctx.movementInput);
        }
        double dx = 0.0, dz = 0.0;
        double dist = (double) (strafe * strafe + forward * forward);
        if (dist >= 1.0E-4) {
            dist = Math.sqrt(dist);
            if (dist < 1.0) dist = 1.0;
            float yaw = ((Float) yawGetter.invoke(ctx.player)).floatValue();
            double rad = (double) yaw * Math.PI / 180.0;
            double sin = Math.sin(rad), cos = Math.cos(rad);
            double scale = MOVE_SCALE / dist;
            dx = ((double) strafe * cos - (double) forward * sin) * scale;
            dz = ((double) forward * cos + (double) strafe * sin) * scale;
        }
        dx = clamp(dx, -MAX_STEP, MAX_STEP);
        dz = clamp(dz, -MAX_STEP, MAX_STEP);
        double total = Math.sqrt(dx * dx + dz * dz);
        if (total > MAX_TOTAL) {
            double k = MAX_TOTAL / total;
            dx *= k;
            dz *= k;
        }
        ctx.motionXField.setDouble(ctx.player, dx);
        ctx.motionZField.setDouble(ctx.player, dz);

        // --- пакет: только спуф onGround (дельты 0 — движение уже в координатах) ---
        PacketHook.spoofGround = true;
        PacketHook.packetDx = 0.0;
        PacketHook.packetDy = 0.0;
        PacketHook.packetDz = 0.0;
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }
}
