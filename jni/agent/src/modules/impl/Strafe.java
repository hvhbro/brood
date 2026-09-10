package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import rustme.IIlIIliIiI;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Field;

/**
 * Strafe — «убирает замедление при A/D», НЕ ускоряя бег вперёд (семантика
 * по тесту юзера 09-10: «не должно ускорять когда бежишь просто вперёд»).
 *
 * МЕХАНИКА (09-10 v3, по юзеру: «вернуть W и S — всем адекватную скорость как
 * сейчас по A/D»):
 *   - Инпут из модовых биндов (named KeyBindingsCategory):
 *     getKeyForward/Back/Left/Right → KeyBindNode.getValue() → KeyBinding.pressed.
 *     Конвенция ванильная: W=+1, S=−1, LEFT(A)=+1, RIGHT(D)=−1.
 *   - Эталон ref: величина горизонтального motion, которую игра даёт сама при
 *     ЧИСТОМ беге вперёд/назад (EMA 0.15 по 1мс-тику, отдельно земля/воздух —
 *     спринт/шаг/крадучись/лёд учитываются игрой).
 *   - ЛЮБОЙ инпут (W/S/A/D) перезаписывается: направление = вектор инпута,
 *     повёрнутый на yaw (ванильная форма moveRelative), величина = ref.
 *     Вперед получает свою естественную скорость, стрейф — ту же (замедление
 *     снято), диагональ не быстрее.
 *   - AutoJump: onGround + коллизия по горизонтали → motionY = 0.42 (vanilla).
 *
 * КАРТЫ ПОЛЕЙ (все доказаны дизасмом 09-10, ZNANIA 5/14.1 частично врала):
 *   motionY = iIIlIiliI (jump 0.42 пишется туда), motionX = IIiIIiliI,
 *   motionZ = IiiIIiliI, onGround = lliililiI (гейт прыжка onLivingUpdate:
 *   `lliililiI && jumpTicks==0 → jump()`, и move(): onGround =
 *   collidedVertically && yDelta<0), collidedHorizontally = IliIIiliI.
 *   ВНИМАНИЕ: iiIIIiliI — НЕ onGround (из-за него JumpCircle «стоял в воздухе»).
 */
public final class Strafe extends Module {

    private static final double JUMP_MOTION = 0.42;      // vanilla jump

    private final Module.BoolSetting stAutoJump = addBool("Auto Jump", false);
    // множитель эталонной скорости (страховка «медленно/быстро»: 1.0 = ровно как
    // играет сама игра, >1 — форс скорости, <1 — мягче)
    private final Module.FloatSetting stSpeed = addSetting("Ускорение", 0.5f, 1.6f, 0.05f, 1f);

    private Field motionXField;    // IIiIIiliI:D — Entity root (motionX)
    private Field motionZField;    // IiiIIiliI:D — Entity root (motionZ)
    private Field motionYField;    // iIIlIiliI:D — Entity root (motionY)
    private Field onGroundField;   // lliililiI:Z — Entity root (onGround)
    private Field collidedHField;  // IliIIiliI:Z — Entity root (horizontal collision)

    // named-бинды движения (KeyBinding инстансы из ctx.keyBindingFor)
    private Object kbForward, kbBack, kbLeft, kbRight;
    private boolean keysResolved;
    private long lastKeyResolveLog;

    // эталонная величина горизонтального motion при беге вперёд (EMA)
    private double refGround, refAir;

    private boolean resolved;
    private long lastResolveAttempt;

    public Strafe() {
        super("Strafe", "Movement");
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (isState()) tick(event);
                } catch (Throwable t) {
                    Log.error("Strafe", "tick exception", t);
                }
            }
        });
        Log.info("Strafe", "registered (toggle: menu bind)");
    }

    private void tick(TickEvent event) {
        GameContext ctx = GameContext.get();
        if (!event.isInWorld() || ctx.player == null) return;
        if (!(ctx.player instanceof IIlIIliIiI)) return;
        if (!resolve(ctx)) return;
        if (!resolveKeys(ctx)) return;
        try {
            IIlIIliIiI p = (IIlIIliIiI) ctx.player;
            boolean onGround = onGroundField.getBoolean(p);

            // инпут из модовых биндов (ванильная конвенция: W=+1, S=−1, A=+1, D=−1)
            float forward = (pressed(kbForward) ? 1f : 0f) + (pressed(kbBack) ? -1f : 0f);
            float strafe = (pressed(kbLeft) ? 1f : 0f) + (pressed(kbRight) ? -1f : 0f);

            double mx = motionXField.getDouble(p);
            double mz = motionZField.getDouble(p);
            double curMag = Math.sqrt(mx * mx + mz * mz);

            // измерение эталона — только на чистом W/S (без стрейфа), чтобы
            // стрейф-замедление форка не портило эталон. Физика движения в MC
            // симметрична по направлению, поэтому эталон вперёд годится и для
            // стрейфа, и для бега (равновесие EMA = естественная скорость игры
            // для текущего состояния: спринт/шаг/крадучись/лёд).
            if (strafe == 0f && curMag > 0.05) {
                if (onGround) refGround = refGround * 0.85 + curMag * 0.15;
                else refAir = refAir * 0.85 + curMag * 0.15;
            }

            // ЛЮБОЙ инпут (W/S/A/D) получает величину max(эталон, текущая).
            // Эталон = естественная скорость вперёд; ВЕЛИЧИНА НЕ НИЖЕ ЭТАЛОНА —
            // замедление стрейфа снято. Но если игра сама разогнала движение
            // ВЫШЕ эталона (спринт-прыжковый буст +0.2/tick, кнокбэк, ледяной
            // разгон) — СОХРАНЯЕМ её величину, только поворачиваем направление:
            // записывать ref «в лоб» значило бы каждый тик съедать буст —
            // спринт-бхоп становился медленнее ванили (тест юзера 09-10).
            double ref = onGround ? refGround : refAir;
            if (ref < 0.05) {
                if (curMag > 0.05) ref = curMag; // эталона нет — текущая величина
                else return;                     // стоим на месте — нечего задавать
            }
            double use = Math.max(ref * stSpeed.value, curMag);
            double yawr = Math.toRadians((double) p.IIiIillIII());
            double cosY = Math.cos(yawr);
            double sinY = Math.sin(yawr);
            double dirX = ((double) strafe) * cosY - ((double) forward) * sinY;
            double dirZ = ((double) forward) * cosY + ((double) strafe) * sinY;
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len < 1.0E-4) return;
            double k = use / len;
            motionXField.setDouble(p, Double.valueOf(dirX * k));
            motionZField.setDouble(p, Double.valueOf(dirZ * k));

            // AutoJump: onGround + горизонтальная коллизия → motionY = 0.42
            if (stAutoJump.get() && onGround && collidedHField.getBoolean(p)) {
                motionYField.setDouble(p, Double.valueOf(JUMP_MOTION));
            }
        } catch (Throwable t) {
            Log.error("Strafe", "tick failed", t);
        }
    }

    private static boolean pressed(Object keyBinding) {
        if (keyBinding == null) return false;
        try {
            return GameContext.get().kbPressedField.getBoolean(keyBinding);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean resolveKeys(GameContext ctx) {
        if (keysResolved) return true;
        // chain: Settings → getData → getKeybindingSettings — резолвится в resolveSprintKey
        if (!ctx.resolveSprintKey()) return false;
        kbForward = ctx.keyBindingFor("getKeyForward");
        kbBack = ctx.keyBindingFor("getKeyBack");
        kbLeft = ctx.keyBindingFor("getKeyLeft");
        kbRight = ctx.keyBindingFor("getKeyRight");
        if (kbForward == null || kbBack == null || kbLeft == null || kbRight == null) {
            long now = System.currentTimeMillis();
            if (now - lastKeyResolveLog > 5000L) {
                lastKeyResolveLog = now;
                Log.info("Strafe", "keybinds incomplete: fwd=" + (kbForward != null)
                    + " back=" + (kbBack != null) + " left=" + (kbLeft != null)
                    + " right=" + (kbRight != null));
            }
            return false;
        }
        keysResolved = true;
        Log.info("Strafe", "movement keybinds resolved (Forward/Back/Left/Right)");
        return true;
    }

    private boolean resolve(GameContext ctx) {
        if (resolved) return true;
        long now = System.currentTimeMillis();
        if (now - lastResolveAttempt < 2000L) return false;
        lastResolveAttempt = now;
        try {
            // ИЕРАРХИЧЕСКИЙ ПОДЪЁМ от SP. КАРТЫ: motionY=iIIlIiliI,
            // motionX=IIiIIiliI, motionZ=IiiIIiliI, onGround=lliililiI,
            // collidedHorizontally=IliIIiliI (все доказаны дизасмом).
            motionXField = walkUp(ctx.player.getClass(), "IIiIIiliI");
            motionYField = walkUp(ctx.player.getClass(), "iIIlIiliI");
            motionZField = walkUp(ctx.player.getClass(), "IiiIIiliI");
            onGroundField = walkUp(ctx.player.getClass(), "lliililiI");
            collidedHField = walkUp(ctx.player.getClass(), "IliIIiliI");
            if (motionXField == null || motionZField == null || motionYField == null
                    || onGroundField == null || collidedHField == null) {
                Log.info("Strafe", "resolve incomplete: mx=" + (motionXField != null)
                    + " mz=" + (motionZField != null) + " my=" + (motionYField != null)
                    + " ground=" + (onGroundField != null)
                    + " coll=" + (collidedHField != null));
                return false;
            }
            resolved = true;
            Log.info("Strafe", "resolved (motion y=iIIlIiliI x=IIiIIiliI z=IiiIIiliI,"
                + " onGround=lliililiI, root=" + motionXField.getDeclaringClass().getName() + ")");
            return true;
        } catch (Throwable t) {
            Log.error("Strafe", "resolve failed", t);
            return false;
        }
    }

    private static Field walkUp(Class c, String name) {
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignore) {}
            c = c.getSuperclass();
        }
        return null;
    }
}
