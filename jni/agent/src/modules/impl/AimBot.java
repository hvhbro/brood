package modules.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.lwjglx.opengl.GL11;

import events.EventBus;
import events.EventBus.TickEvent;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import modules.api.Module;
import rustme.IIlIIliIiI;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.Watermark;

/**
 * AimBot — порт конвейера конкурента (дизасм chtdump, полный).
 *
 * КОНВЕЙР (каждый тик при зажатой клавише R):
 *  1. Ammo-классификатор: NBT предмета в руке rustme_subdata → ammo_type →
 *     enum {1=pistol, 2=rifle, 3=HV, 4=hv9, 5=arrow, -1=?}: подстроки
 *     '5_56_hv'/'hv_5_56'/'556_hv'/'e_bullet'/'ullet_hv'→3; '9mm_'/'hv_9'→4;
 *     'high_velocity_arrow'→5; 'incendiary_rifle_bullet'→3; 'explosive_rifle_bullet'→3;
 *     '556'/'5_56'/'rifle'→2; '9mm'/'pistol'→1; 'arro'→5.
 *  2. Цели: world.playerEntities, скип локального. dist ∈ [0.1..300].
 *  3. Bone: posY + (knocked ? +0.25 : eyeHeight 1.62); IgnoreKnocked скипает лежачих.
 *  4. Anti-lag ring-buffer: снимок при сдвиге >= 0.2м ИЛИ раз в 150мс (стоячие затухают в 0 lead);
 *     delta = target.pos − buffer.oldest (движение за наблюдение);
 *     trackingSince = время добавления последнего снимка.
 *  5. Predict: k = clamp((now − trackingSince)*0.02*20.0*aimPingComp, 0..6);
 *     aimPos = target + delta*k; (итерации не нужны — линейная модель)
 *     при aimPredictY: kY = clamp(k, 0..2)*aimPredictScale, y = targetY + deltaY*kY.
 *  6. FOV-гейт: угол между взглядом и направлением на цель <= aimFov (120°).
 *  7. Visible: rayTraceBlocks(глаза→цель) — пусто = видно (lenient path: если
 *     резолв не удался, проверка пропускается).
 *  8. Поворот: пишем yaw/pitch ВХОДНЫЕ поля (IlIlIiliI/IilIIiliI — их только читает
 *     камера до lIIIlIlIII/illiIIlIII перезаписи) — прямая запись setRotation.
 *
 * Клавиша: R (GLFW 82, как aimBind=82 у конкурента).
 * Дефолты из их профиля: aimFov=120, aimPredictScale=1.0, aimPingComp=0.6,
 * aimLatency=~95мс (компенсация сетевого лага на trackingSince), aimBone=Head(1.62).
 *
 * РЕЖИМ (настройка Mode): Vector — как сейчас (доворот камеры через setRotation);
 * Silent — тот же пайплайн 1:1 (снапшоты, предикт, баллистика, дроп), но камера
 * НЕ трогается. Два слоя доставки (оба повторяют vector 1:1 для сервера):
 *  1) слушатель события атаки rustme.llilliiliI на шине мода (постится из
 *     gs.lIiIiilliI ДО действия): прямой контроллер.IiliIIlliI(player, target)
 *     + отмена события — покрывает melee/entity-механику;
 *  2) Netty outbound-хендлер "rustme-silent" (перед энкодером): переписывает
 *     yaw/pitch C03-пакетов (IIiiilIIiI/lIiiilIIiI, поля liiIliilI/iIiIliilI)
 *     на silent-углы — покрывает серверную баллистику пушек (swing-пакет
 *     несёт только arc, направление сервер берёт из взгляда). Углы клампятся
 *     тем же MAX_TURN от прошлого (паритет динамики с vector).
 */
public final class AimBot extends Module {

    public static final int TOGGLE_KEY = 82; // GLFW_KEY_R (aimBind=82 в их профиле)
    // aim = TOGGLE (по запросу юзера): R вкл/выкл

    // Константы из дизасма (0x2ae10/0x2c530)
    private static final float TIME_SCALE = 0.02f;     // мс → тики
    private static final float PREDICT_MULT = 20.0f;   // масштаб k
    private static final float KY_MAX = 2.0f;          // clamp вертикали (aimPredictY)
    private static final float DIST_MIN = 0.1f;
    private static final float DIST_MAX = 300.0f;
    private static final double EYE_HEIGHT_STAND = 1.62;
    private static final double KNOCKED_OFFSET = 0.25;
    private static final double MAG0 = 0.2;            // шаг ring-buffer снимков
    // Вертикаль (фикс скачков на прыжках, лог 09-11): сырой velY whiplash'ит.
    private static final double VY_UP_MAX = 9.0;       // кламп vy вверх (прыжок ~7)
    private static final double VY_DOWN_MAX = -12.0;   // кламп vy вниз
    private static final double VY_EMA = 0.35;         // сглаживание vy
    private static final long VY_SPAN_MS = 250L;       // окно span-земли
    private static final double VY_GROUND_SPAN = 0.15; // span Y < этого = земля → vy=0
    private static final double Y_LEAD_MAX = 3.5;      // кап вертикального лида (м)

    // Настройки (их дефолты; элементы в меню: Module.addSetting/addBool)
    private final Module.ModeSetting stMode = addMode("Mode", new String[]{"Vector", "Silent"}, 0);
    private final Module.FloatSetting stFov = addSetting("FOV", 10f, 360f, 1f, 120f);
    private final Module.FloatSetting stPingComp = addSetting("Ping Comp", 0.1f, 2f, 0.05f, 0.5f);
    private final Module.FloatSetting stPredict = addSetting("Prediction", 0.1f, 3f, 0.05f, 1.0f);
    private final Module.BoolSetting stDrawFov = addBool("Draw FOV", true);
    private final Module.BoolSetting stPredictY = addBool("Predict Y", true);
    private final Module.BoolSetting stVisible = addBool("Visible Check", true);
    private final Module.BoolSetting stAutoShoot = addBool("Auto Shoot", false);
    private final Module.FloatSetting stShootInterval = addSetting("Shoot Interval", 25f, 1000f, 5f, 60f);

    private float aimFov() { return stFov.value; }
    private boolean silentMode() { return stMode.index() == 1; }
    private float aimPingComp() { return stPingComp.value; }
    private float aimPredictScale() { return stPredict.value; }
    private boolean drawFov() { return stDrawFov.get(); }
    private boolean visibleCheck() { return stVisible.get(); }
    private static final boolean aimIgnoreKnocked = false;
    private static final boolean aimDebug = true;

    private long lastLog;
    private long lastAimWrite;
    private int candTotal, candFov, candVis; // счётчики no-target-диагностики

    // ==== Диагностика для пост-анализа промахов (09-10) ====
    private long lastStateLog;      // aim-строка состояния: раз в 100мс
    private long lastNoTargetLog;   // причина «нет цели»: раз в 1с
    private long lastResetLog;      // ресеты кольца: раз в 1с
    private long lastVelClampLog;
    private long lastTargetId = -1; // для ACQ/SWITCH/LOST-событий
    private long lastTargetSeen;    // когда последняя цель была в прицеле
    private boolean lastLmb;        // edge ЛКМ = маркер выстрела в логе
    private Object attackKb;        // (legacy, не используется — см. ClickWorker)
    private boolean autoShootPressed;
    private long lastAutoShootLog;
    private long lastAutoClick;
    private ClickWorker clickWorker;

    /** Эмуляция ЛКМ на уровне ОС (поток-воркер, без лямбд). */
    private static final class ClickWorker implements Runnable {
        volatile boolean requested;
        private boolean robotReady;
        private java.awt.Robot robot;

        ClickWorker() {
            Thread t = new Thread(this, "RustMe-Click");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void run() {
            while (true) {
                if (requested) {
                    requested = false;
                    if (!robotReady) {
                        try {
                            robot = new java.awt.Robot();
                            robotReady = true;
                        } catch (Throwable t) {
                            Log.error("AimBot", "robot init failed", t);
                        }
                    }
                    if (robotReady) {
                        try {
                            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                            Thread.sleep(15);
                            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                        } catch (Throwable ignore) {}
                    }
                }
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void requestClick() {
        if (clickWorker == null) clickWorker = new ClickWorker();
        clickWorker.requested = true;
    }

    /**
     * Слушатель события атаки мода (rustme.llilliiliI). Вызывается шиной
     * СИНХРОННО на главном потоке ДО действия игры (gs.lIiIiilliI: пост,
     * затем чтение отмены). Весь код в try/catch — исключение не должно
     * ломать чужой удар.
     */
    private static final class AttackListener implements kotlin.jvm.functions.Function1 {
        @Override
        public Object invoke(Object e) {
            try {
                AimBot inst = INSTANCE;
                if (inst != null) inst.onAttackEvent(e);
            } catch (Throwable t) {
                Log.error("AimBot", "attack listener failed", t);
            }
            try {
                AimBot inst = INSTANCE;
                Object u = inst != null ? inst.unitInstance : null;
                if (u != null) return u;
            } catch (Throwable ignore) {}
            return null;
        }
    }

    /** Стиринг удара в silent-режиме (главный поток, синхронно до действия). */
    private void onAttackEvent(Object e) {
        try {
            steerEvents++;
            if (steerEvents == 1) {
                Log.info("AimBot", "attack events flowing (bus subscription live)");
            }
            if (!isState() || !silentMode()) return;
            GameContext ctx = GameContext.get();
            if (!ctx.inWorld) return;
            if (e == null || attackEventC == null) return;
            if (!attackEventC.isInstance(e)) return;
            Object tgt = silentTarget;
            if (tgt == null || !silentHave) return;
            if (!(tgt instanceof IIlIIliIiI)) return;
            if (System.currentTimeMillis() - silentTime > 1000L) return; // протухший таргет
            Object player = ctx.player;
            if (player == null || attackController == null || attackEntityM == null) return;
            // прямой удар по нашей цели (тот же вызов, что сделала бы игра:
            // пакет сущности + замах), затем отмена игрового действия
            try {
                attackEntityM.invoke(attackController, player, tgt);
            } catch (Throwable t) {
                Log.error("AimBot", "silent attack invoke failed", t);
                return; // НЕ отменяем — пусть игра бьёт как обычно
            }
            try {
                if (resetCooldownM != null && cooldownEnum != null) {
                    resetCooldownM.invoke(player, cooldownEnum);
                }
            } catch (Throwable ignore) {}
            try {
                if (cancelM != null) cancelM.invoke(e, Boolean.TRUE);
            } catch (Throwable ignore) {}
            steerShots++;
            long now = System.currentTimeMillis();
            if (now - lastSteerLog > 2000L) {
                lastSteerLog = now;
                String nm = "?";
                try { nm = nameOf((IIlIIliIiI) tgt); } catch (Throwable ignore) {}
                Log.info("AimBot", "silent steer: shots=" + steerShots
                    + " events=" + steerEvents + " target=" + nm
                    + " yaw=" + f1(silentYaw) + " pitch=" + f1(silentPitch));
            }
        } catch (Throwable t) {
            Log.error("AimBot", "steer failed", t);
        }
    }

    /**
     * Подписка на событие атаки (паттерн форка 1:1: синглтон iiIiliIiiI +
     * IIlIIIlIIl(Class, Function1)). Ленивая, с троттлингом; проверка —
     * размер handler-листа llIIIIlIIl(Class).
     */
    private void ensureBus(GameContext ctx) {
        if (busSubscribed) return;
        long now = System.currentTimeMillis();
        if (now - lastBusAttempt < 5000L) return;
        lastBusAttempt = now;
        try {
            if (ctx.gameLoader == null || ctx.gs == null) return;
            ClassLoader ld = ctx.gameLoader;
            Class busC = ld.loadClass("rustme.lIllIilliI");
            Object bus = busC.getField("iiIiliIiiI").get(null);
            if (bus == null) {
                Log.info("AimBot", "bus: singleton null");
                return;
            }
            attackEventC = ld.loadClass("rustme.llilliiliI");
            Method sub = null;
            for (Method m : busC.getMethods()) {
                if (!m.getName().equals("IIlIIIlIIl")) continue;
                if (m.getParameterCount() != 2) continue;
                if (m.getParameterTypes()[0] != Class.class) continue;
                sub = m;
                break;
            }
            if (sub == null) {
                Log.error("AimBot", "bus: subscribe method not found", null);
                return;
            }
            sub.invoke(bus, attackEventC, new AttackListener());
            // Проверка llIIIIlIIl НЕ репрезентативна (в проде возвращает 0 при
            // живом потоке событий — см. лог: attackEvents растут) — только инфо.
            // Proof-of-subscription = сами события (steerEvents в слушателе).
            try {
                Method listM = busC.getMethod("llIIIIlIIl", Class.class);
                Object lst = listM.invoke(null, attackEventC);
                int n = lst instanceof java.util.List ? ((java.util.List) lst).size() : -1;
                Log.info("AimBot", "bus subscribed llilliiliI (list query=" + n + ")");
            } catch (Throwable ignore) {}
            // контроллер атаки + отмена + кулдаун (имена из дизасма lIiIiilliI)
            try {
                Object gs = ctx.gs;
                Object ctl = getFieldR(gs.getClass(), "iIiIiiIl").get(gs);
                Class ctlC = ld.loadClass("rustme.liIlIIliiI");
                if (ctl != null && ctlC.isInstance(ctl)) {
                    attackController = ctl;
                    Class wrapperC = ld.loadClass("rustme.IIiIIiIIiI");
                    Class entityC = ld.loadClass("rustme.IIlIIliIiI");
                    attackEntityM = ctlC.getMethod("IiliIIlliI", wrapperC, entityC);
                }
                Class spC = ctx.localSpClass;
                if (spC != null) {
                    Class cdC = ld.loadClass("rustme.llIIIilIiI");
                    resetCooldownM = spC.getMethod("IIIliIiilI", cdC);
                    cooldownEnum = cdC.getField("lliilIIlI").get(null);
                }
                cancelM = attackEventC.getMethod("lIIillliil", Boolean.TYPE);
            } catch (Throwable t) {
                Log.error("AimBot", "bus: attack refs resolve failed", t);
            }
            try {
                Class uC = Class.forName("kotlin.Unit", true, ld);
                unitInstance = uC.getField("INSTANCE").get(null);
            } catch (Throwable ignore) {}
            busSubscribed = true;
            Log.info("AimBot", "silent refs: controller=" + (attackController != null)
                + " attack=" + (attackEntityM != null)
                + " reset=" + (resetCooldownM != null && cooldownEnum != null)
                + " cancel=" + (cancelM != null));
        } catch (Throwable t) {
            Log.error("AimBot", "bus subscribe failed", t);
        }
    }

    private static Field getFieldR(Class c, String name) throws Throwable {
        try {
            Field f = c.getField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
    }

    private void clearSilent() {
        silentHave = false;
        silentTarget = null;
        lastSilentId = -1L;
    }

    /**
     * Outbound-хендлер Netty (видит пакеты ДО энкодера — addLast встаёт
     * у хвоста цепочки). Переписывает взгляд C03 на silent-углы, всё
     * остальное (включая inbound) проходит нетронутым. Любая ошибка =
     * passthrough: сеть ломать нельзя.
     */
    private static final class SilentOutbound extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                AimBot inst = INSTANCE;
                if (inst != null && inst.isState() && inst.silentMode() && inst.silentHave) {
                    Object tgt = inst.silentTarget;
                    long age = System.currentTimeMillis() - inst.silentTime;
                    if (tgt != null && age >= 0 && age < 500L) {
                        float yaw = inst.silentYaw;
                        float pitch = inst.silentPitch;
                        while (yaw > 180f) yaw -= 360f;
                        while (yaw < -180f) yaw += 360f;
                        if (pitch > 90f) pitch = 90f;
                        else if (pitch < -90f) pitch = -90f;
                        Object out = msg;
                        try {
                            out = inst.applySilentLook(msg, yaw, pitch);
                        } catch (Throwable ignore) {
                            out = msg;
                        }
                        if (out != msg) inst.c03Upgrades++;
                        inst.c03Rewrites++;
                        long now = System.currentTimeMillis();
                        if (now - inst.lastRewriteLog > 5000L) {
                            inst.lastRewriteLog = now;
                            Log.info("AimBot", "C03 spoof: rewrites=" + inst.c03Rewrites
                                + " upgrades=" + inst.c03Upgrades
                                + " yaw=" + f1(yaw) + " pitch=" + f1(pitch));
                        }
                        ctx.write(out, promise);
                        return;
                    }
                }
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                try {
                    AimBot inst = INSTANCE;
                    if (inst != null && now - inst.lastPipeErr > 10000L) {
                        inst.lastPipeErr = now;
                        Log.error("AimBot", "C03 handler failed (passthrough)", t);
                    }
                } catch (Throwable ignore) {}
            }
            ctx.write(msg, promise); // ВСЕГДА вперёд — дропать пакеты запрещено
        }
    }

    /** Поиск живого Channel: SP/world/mc → handler → NetworkManager → Channel. */
    private void ensurePipeline(GameContext ctx) {
        try {
            if (ctx.gameLoader == null || ctx.world == null) return;
            long now = System.currentTimeMillis();
            if (now - lastPipeAttempt < 3000L) return;
            lastPipeAttempt = now;
            ClassLoader ld = ctx.gameLoader;
            if (posRotC == null || c03YawF == null || c03PitchF == null) {
                if (!resolveC03(ld)) return;
            }
            Object ch = findChannel(ctx, ld);
            if (ch == null) {
                if (now - lastPipeLog > 10000L) {
                    lastPipeLog = now;
                    Log.info("AimBot", "C03: channel not found yet");
                }
                return;
            }
            if (ch == hookedChannel) return;
            installHandler(ch, ld);
            hookedChannel = ch;
            Log.info("AimBot", "C03: handler installed on new channel");
        } catch (Throwable t) {
            Log.error("AimBot", "pipeline ensure failed", t);
        }
    }

    /** C03-классы + yaw/pitch поля (имена из дизасма writePacketData). */
    private boolean resolveC03(ClassLoader ld) {
        try {
            Class pr = ld.loadClass("rustme.IIiiilIIiI"); // PositionRotation
            Class rt = ld.loadClass("rustme.lIiiilIIiI"); // Rotation
            Field yf = pr.getField("liiIliilI");
            yf.setAccessible(true);
            Field pf = pr.getField("iIiIliilI");
            pf.setAccessible(true);
            if (yf.getType() != Float.TYPE || pf.getType() != Float.TYPE) {
                Log.error("AimBot", "C03: yaw/pitch fields not float", null);
                return false;
            }
            posRotC = pr;
            rotC = rt;
            c03YawF = yf;
            c03PitchF = pf;
            // upgrade-резолв (best-effort: без него только in-place rewrite)
            try {
                Class pc = ld.loadClass("rustme.iIiiilIIiI"); // Position
                Class bc = ld.loadClass("rustme.iliiilIIiI"); // base
                java.lang.reflect.Constructor<?> prCtor =
                    pr.getConstructor(Double.TYPE, Double.TYPE, Double.TYPE,
                        Float.TYPE, Float.TYPE, Boolean.TYPE);
                java.lang.reflect.Constructor<?> rCtor =
                    rt.getConstructor(Float.TYPE, Float.TYPE, Boolean.TYPE);
                Field xf = pc.getField("IIiIiIilI");
                xf.setAccessible(true);
                Field yff = pc.getField("llliiIilI");
                yff.setAccessible(true);
                Field zf = pc.getField("liiIiIilI");
                zf.setAccessible(true);
                Field ogf = pc.getField("IiliIiilI");
                ogf.setAccessible(true);
                Field bogf = bc.getField("IiliIiilI");
                bogf.setAccessible(true);
                if (xf.getType() == Double.TYPE && yff.getType() == Double.TYPE
                    && zf.getType() == Double.TYPE && ogf.getType() == Boolean.TYPE
                    && bogf.getType() == Boolean.TYPE) {
                    posC = pc;
                    baseC = bc;
                    posRotCtor = prCtor;
                    rotCtor = rCtor;
                    posXF = xf;
                    posYF = yff;
                    posZF = zf;
                    posOnGroundF = ogf;
                    baseOnGroundF = bogf;
                }
            } catch (Throwable t) {
                Log.error("AimBot", "C03: upgrade refs failed (in-place only)", t);
            }
            Log.info("AimBot", "C03 resolved: IIiiilIIiI/lIiiilIIiI yaw=liiIliilI pitch=iIiIliilI"
                + " upgrade=" + (posRotCtor != null && rotCtor != null));
            return true;
        } catch (Throwable t) {
            Log.error("AimBot", "C03 resolve failed", t);
            return false;
        }
    }

    /**
     * Применить silent-взгляд к исходящему пакету (event-loop поток Netty).
     * Возвращает пакет для отправки: тот же (in-place) или пересобранный
     * upgrade (Position→PositionRotation, base→Rotation). Трогает только
     * поля пакета — состояние игры не читает/не пишет.
     */
    private Object applySilentLook(Object msg, float yaw, float pitch) {
        try {
            Class pr = posRotC, rc = rotC;
            if ((pr != null && pr.isInstance(msg)) || (rc != null && rc.isInstance(msg))) {
                Field yf = c03YawF, pf = c03PitchF;
                if (yf == null || pf == null) return msg;
                yf.setFloat(msg, yaw);
                pf.setFloat(msg, pitch);
                return msg;
            }
            Class pc = posC;
            if (pc != null && pc.isInstance(msg) && posRotCtor != null
                && posXF != null && posYF != null && posZF != null && posOnGroundF != null) {
                double x = ((Double) posXF.get(msg)).doubleValue();
                double y = ((Double) posYF.get(msg)).doubleValue();
                double z = ((Double) posZF.get(msg)).doubleValue();
                boolean og = ((Boolean) posOnGroundF.get(msg)).booleanValue();
                return posRotCtor.newInstance(Double.valueOf(x), Double.valueOf(y),
                    Double.valueOf(z), Float.valueOf(yaw), Float.valueOf(pitch),
                    Boolean.valueOf(og));
            }
            Class bc = baseC;
            if (bc != null && msg.getClass() == bc && rotCtor != null && baseOnGroundF != null) {
                boolean og = ((Boolean) baseOnGroundF.get(msg)).booleanValue();
                return rotCtor.newInstance(Float.valueOf(yaw), Float.valueOf(pitch),
                    Boolean.valueOf(og));
            }
        } catch (Throwable ignore) {}
        return msg;
    }

    /** Handler → NetworkManager → Channel (сканы по instanceof, не по именам). */
    private Object findChannel(GameContext ctx, ClassLoader ld) {
        try {
            Class handlerC;
            try {
                handlerC = ld.loadClass("rustme.iliilIliiI");
            } catch (Throwable t) {
                return null;
            }
            Object handler = scanInstance(ctx.player, handlerC);
            if (handler == null) handler = scanInstance(ctx.world, handlerC);
            if (handler == null && ctx.mc != null) handler = scanInstance(ctx.mc, handlerC);
            if (handler == null) return null;
            netHandler = handler; // кэш для proactive-отправки взгляда
            Class nmC;
            try {
                nmC = ld.loadClass("rustme.IllIlIIIiI");
            } catch (Throwable t) {
                return null;
            }
            Object nm = scanInstance(handler, nmC);
            if (nm == null) return null;
            Class chC;
            try {
                chC = Class.forName("io.netty.channel.Channel", true, ld);
            } catch (Throwable t) {
                return null;
            }
            return scanInstance(nm, chC);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Упреждающий Rotation со silent-взглядом сразу при смене цели
     * (не ждём следующего клиентского тика). sendPacket потокобезопасен
     * (очередь event loop), вызывается с агентного потока. Best-effort.
     */
    private void sendProactiveLook(GameContext ctx) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastProactiveMs < PROACTIVE_MIN_MS) return;
            if (netHandler == null || rotC == null) return;
            if (sendPacketM == null || onGroundF == null) {
                if (!sendTried) {
                    sendTried = true;
                    try {
                        sendPacketM = netHandler.getClass().getMethod("llIlIllilI",
                            ctx.gameLoader.loadClass("rustme.llillIIIiI"));
                    } catch (Throwable t) {
                        Log.error("AimBot", "proactive: send method not found", t);
                        return;
                    }
                    try {
                        Class c = ctx.localSpClass;
                        while (c != null && onGroundF == null) {
                            try {
                                onGroundF = c.getDeclaredField("lliililiI");
                                onGroundF.setAccessible(true);
                            } catch (Throwable ignore) {
                                c = c.getSuperclass();
                            }
                        }
                    } catch (Throwable ignore) {}
                }
                if (sendPacketM == null) return;
            }
            float yaw = silentYaw, pitch = silentPitch;
            boolean og = true;
            try {
                if (onGroundF != null && ctx.player != null) og = onGroundF.getBoolean(ctx.player);
            } catch (Throwable ignore) {}
            java.lang.reflect.Constructor<?> ctor = rotCtor;
            if (ctor == null && rotC != null) {
                try {
                    ctor = rotC.getConstructor(Float.TYPE, Float.TYPE, Boolean.TYPE);
                } catch (Throwable ignore) {}
            }
            if (ctor == null) return;
            Object pkt = ctor.newInstance(Float.valueOf(yaw), Float.valueOf(pitch), Boolean.valueOf(og));
            sendPacketM.invoke(netHandler, pkt);
            lastProactiveMs = now;
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastPipeErr > 10000L) {
                lastPipeErr = now;
                Log.error("AimBot", "proactive look failed", t);
            }
        }
    }

    /** Первое non-null поле, assignable к want (по иерархии вверх). */
    private static Object scanInstance(Object owner, Class want) {        if (owner == null || want == null) return null;
        try {
            Class c = owner.getClass();
            while (c != null && c != Object.class) {
                Field[] fs;
                try {
                    fs = c.getDeclaredFields();
                } catch (Throwable t) {
                    c = c.getSuperclass();
                    continue;
                }
                for (int i = 0; i < fs.length; i++) {
                    try {
                        fs[i].setAccessible(true);
                        Object v = fs[i].get(owner);
                        if (v != null && want.isInstance(v)) return v;
                    } catch (Throwable ignore) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** Установка хендлера на event loop (потокобезопасно для Netty). */
    private void installHandler(final Object ch, ClassLoader ld) throws Exception {
        final Method getPipeline = ch.getClass().getMethod("pipeline");
        final Object pl = getPipeline.invoke(ch);
        Class handlerType = Class.forName("io.netty.channel.ChannelHandler", true, ld);
        final Method addLast = pl.getClass().getMethod("addLast", String.class, handlerType);
        final Method remove = pl.getClass().getMethod("remove", String.class);
        Object loop = ch.getClass().getMethod("eventLoop").invoke(ch);
        Method exec = loop.getClass().getMethod("execute", Runnable.class);
        exec.invoke(loop, new Runnable() {
            @Override
            public void run() {
                try {
                    try {
                        remove.invoke(pl, "rustme-silent");
                    } catch (Throwable ignore) {}
                    addLast.invoke(pl, "rustme-silent", new SilentOutbound());
                } catch (Throwable t) {
                    Log.error("AimBot", "pipeline install failed", t);
                }
            }
        });
    }

    /**
     * Была ли entityId нашей недавней целью (<= withinMs) и по буферу
     * снимков она находилась в радиусе radius от (x,y,z) — атрибуция
     * «мы убили этого игрока» для KillEffect. id=-1 если нет.
     */
    public static long recentTargetNear(double x, double y, double z, double radius, long withinMs) {
        AimBot inst = INSTANCE;
        if (inst == null || inst.lastTargetId < 0) return -1;
        if (System.currentTimeMillis() - inst.lastTargetSeen > withinMs) return -1;
        Snapshot[] buf = (Snapshot[]) inst.buffers.get(Long.valueOf(inst.lastTargetId));
        if (buf == null) return -1;
        Snapshot n = buf[0];
        double dx = n.x - x, dy = n.y - y, dz = n.z - z;
        if (dx * dx + dy * dy + dz * dz > radius * radius) return -1;
        return inst.lastTargetId;
    }
    private final java.util.HashMap<Long, String> nameCache = new java.util.HashMap<Long, String>();

    // Anti-lag буферы на игрока (per-entity ring): entityId → Position[] + время
    private final java.util.HashMap<Long, Snapshot[]> buffers = new java.util.HashMap<Long, Snapshot[]>();
    private final java.util.HashMap<Long, Long> trackingSince = new java.util.HashMap<Long, Long>();
    private final java.util.HashMap<Long, Double> smoothVy = new java.util.HashMap<Long, Double>();

    // reflection-кэш (ленивый)
    private boolean resolved;
    private Method stackGetTag;        // liIIIIIIiI.llIiIililI() → liilIIIIiI
    private Method nbtGetCompound;     // liilIIIIiI.IIlliIlilI(String) → liilIIIIiI
    private Method nbtGetInt;          // liilIIIIiI.iiIIiIlilI(String) → I
    private Method rotateMethod;       // Entity.iiiiIllIII(FF)V = setRotation(yaw,pitch)
    private Field inventoryField;      // wrapper.iIliiIiII (InventoryPlayer) — с иерархией
    private Object world;

    // ==== Silent-снапшот (пишет aimTick агентного потока, читает слушатель
    // шины на ГЛАВНОМ потоке синхронно перед ударом — volatile) ====
    private volatile Object silentTarget;  // IIlIIliIiI best entity (null = нет цели)
    private volatile float silentYaw;
    private volatile float silentPitch;
    private volatile long silentTime;
    private volatile boolean silentHave;

    // ==== Шина мода (атака-событие) ====
    private volatile boolean busSubscribed;
    private volatile long lastBusAttempt;
    private volatile Object attackController; // gs.iIiIiiIl (liIlIIliiI)
    private volatile Method attackEntityM;    // IiliIIlliI(wrapper, entity)
    private volatile Method resetCooldownM;   // playerSP.IIIliIiilI(llIIIilIiI)
    private volatile Object cooldownEnum;     // llIIIilIiI.lliilIIlI
    private volatile Class attackEventC;      // rustme.llilliiliI
    private volatile Method cancelM;          // lIIillliil(Z)
    private volatile Object unitInstance;     // kotlin.Unit.INSTANCE (возврат слушателя)
    private volatile long steerEvents;        // постов атаки seen
    private volatile long steerShots;         // перенаправлено ударов
    private volatile long lastSteerLog;
    private volatile long lastBusStatLog;

    // ==== C03-спуф (Netty pipeline; серверная баллистика идёт от взгляда) ====
    // Пушки бьют НЕ entity-пакетами (55 стиров = 0 киллов, лог 09-11), а
    // серверным рейкастом из C03-взгляда (swing-пакет несёт только arc).
    // Хендлер в outbound-цепочке ПЕРЕД энкодером переписывает yaw/pitch
    // PositionRotation/Rotation на silent-углы; камера не трогается.
    private volatile Class posRotC;           // rustme.IIiiilIIiI
    private volatile Class rotC;              // rustme.lIiiilIIiI
    private volatile Field c03YawF;           // liiIliilI:F (база iliiilIIiI)
    private volatile Field c03PitchF;         // iIiIliilI:F
    // upgrade бескрылых пакетов: Position(iIiiilIIiI: x,y,z,onGround) и
    // base(iliiilIIiI, точный класс: onGround) пересобираем со взглядом,
    // иначе стоя/идя сервер смотрит старым взглядом (промахи в silent)
    private volatile Class posC;              // rustme.iIiiilIIiI
    private volatile Class baseC;             // rustme.iliiilIIiI
    private volatile java.lang.reflect.Constructor<?> posRotCtor; // (DDDFFZ)
    private volatile java.lang.reflect.Constructor<?> rotCtor;    // (FFZ)
    private volatile Field posXF, posYF, posZF, posOnGroundF;
    private volatile Field baseOnGroundF;
    private volatile Object hookedChannel;    // текущий канал (identity)
    private volatile long lastPipeAttempt;
    private volatile long c03Rewrites;
    private volatile long c03Upgrades;
    private volatile long lastRewriteLog;
    private volatile long lastPipeLog;
    private volatile long lastPipeErr;
    // упреждающий взгляд: смена цели шлёт Rotation сразу (не ждём тик),
    // автоогонь молчит 150мс пока взгляд едет до сервера (иначе первые пули мимо)
    private static final long SETTLE_MS = 150L;
    private static final long PROACTIVE_MIN_MS = 100L;
    private volatile long lastAcquireMs;
    private volatile long lastSilentId = -1L;
    private volatile long lastProactiveMs;
    private volatile long lastSettleLog;
    private volatile Object netHandler;       // iliilIliiI (для proactive send)
    private volatile Method sendPacketM;      // llIlIllilI(llillIIIiI)
    private volatile Field onGroundF;         // SP.lliililiI:Z
    private volatile boolean sendTried;

    private static final class Snapshot {
        long t;
        double x, y, z;
    }

    /** Синглтон для CheatHud (render — статик, state живёт в инстансе). */
    public static AimBot INSTANCE;

    public AimBot() {
        super("AimBot", "Combat");
        INSTANCE = this;
        // старт ВЫКЛЮЧЕННЫМ: включение — биндом из меню (tickBind).
        // ВАЖНО: никаких handleKey здесь — при bindKey из меню будет
        // ДВОЙНОЙ edge-детектор на одной клавише (бесконечный ON/OFF).
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (isState()) onTick(event);
                } catch (Throwable t) {
                    Log.error("AimBot", "onTick exception", t);
                }
            }
        });
        Log.info("AimBot", "registered (toggle: menu bind)");
    }

    @Override
    public void onTick(TickEvent event) {
        GameContext ctx = GameContext.get();
        if (!event.isInWorld() || ctx.player == null) {
            buffers.clear();
            trackingSince.clear();
            smoothVy.clear();
            nameCache.clear();
            lastTargetId = -1;
            lastLmb = false;
            clearSilent();
            return;
        }
        // сбор снимков позиций ВСЕГДА (для предикта нужны данные ДО нажатия R)
        try {
            collectSnapshots(ctx);
        } catch (Throwable t) {
            if (System.currentTimeMillis() - lastLog > 5000L) {
                lastLog = System.currentTimeMillis();
                Log.error("AimBot", "collect failed", t);
            }
        }
        // aim активен пока модуль включен (toggle-режим)
        try {
            aimTick(ctx);
        } catch (Throwable t) {
            Log.error("AimBot", "aim failed", t);
        }
    }

    @Override
    protected void onDisable() {
        clearSilent();
    }

    /** Обновляет ring-buffer позиций всех игроков (шаг снимка >= MAG0). */
    private void collectSnapshots(GameContext ctx) throws Exception {
        Object worldNow = ctx.world;
        if (worldNow != world) {
            world = worldNow;
            buffers.clear();
            trackingSince.clear();
            smoothVy.clear();
        }
        java.util.List<?> players = (java.util.List<?>) ctx.playersField.get(ctx.world);
        if (players == null || players.isEmpty()) return;
        long now = System.currentTimeMillis();
        Object[] arr = players.toArray(new Object[0]);
        for (int i = 0; i < arr.length; i++) {
            Object w = arr[i];
            if (w == null || ctx.localSpClass.isInstance(w)) continue;
            if (!(w instanceof IIlIIliIiI)) continue;
            IIlIIliIiI e = (IIlIIliIiI) w;
            long id = e.iiIIIIlIII(); // getEntityId
            double px = e.IlIiillIII(), py = e.liiiIllIII(), pz = e.lIilillIII();
            Snapshot[] buf = buffers.get(Long.valueOf(id));
            if (buf == null) {
                // ВСЕ слоты = текущая позиция: иначе buf[1] остаётся (0,0,0) и
                // vel на первом тике = newest−origin (кламп 100 бл/с) → прицел
                // дёргает ОТ цели, пока кольцо не заполнится (тест юзера 09-10).
                // vel=0 на свежем треке — семантика их ресет-ветки (0x2a840).
                buf = new Snapshot[8];
                for (int s = 0; s < buf.length; s++) {
                    buf[s] = new Snapshot(); // сначала объекты (их пропуск = NPE 09-10)
                    buf[s].x = px; buf[s].y = py; buf[s].z = pz; buf[s].t = now;
                }
                buffers.put(Long.valueOf(id), buf);
                trackingSince.put(Long.valueOf(id), Long.valueOf(now));
                Log.info("AimBot", "track new id=" + id + " " + nameOf(e));
                continue;
            }
            Snapshot last = buf[0];
            double dx = px - last.x, dy = py - last.y, dz = pz - last.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // снимок: сдвиг >= MAG0 ИЛИ прошло >=150мс. Если бы вставляли только
            // по сдвигу, у ЗАСТЫВШЕЙ цели в кольце оставались старые движущиеся
            // снимки → фантомный lead у стоячих игроков (тест юзера 09-10).
            boolean moved = dist >= MAG0;
            boolean stale = now - last.t >= 150L;
            if (!moved && !stale) continue;
            // их ресеты (0x2a840): телепорт >25м ИЛИ стоянка >0.25с → vel=0,
            // trackingSince=now (их +0x50 — возраст трекинга для k начинается заново)
            if (dist > 25.0 || (!moved && now - last.t >= 250L)) {
                for (int s = 0; s < buf.length; s++) {
                    buf[s].x = px; buf[s].y = py; buf[s].z = pz; buf[s].t = now;
                }
                trackingSince.put(Long.valueOf(id), Long.valueOf(now));
                if (now - lastResetLog > 1000L) {
                    lastResetLog = now;
                    Log.info("AimBot", "snap reset id=" + id + " " + nameOf(e) + ": "
                        + (dist > 25.0 ? "телепорт " + f1(dist) + "м" : "стоянка >250мс"));
                }
                continue;
            }
            // сдвиг: [0] ← новый, остальные ← старые (delta = движение за интервал).
            // t КОПИРУЕТСЯ ОБЯЗАТЕЛЬНО: без него buf[1].t заморожен на момент
            // ресета → dt = секунды → vel занижен в десятки раз → «не предиктит»
            // (лог 09-10: dt=1502..2706мс при живом движении цели).
            for (int s = buf.length - 1; s > 0; s--) {
                buf[s].x = buf[s - 1].x; buf[s].y = buf[s - 1].y; buf[s].z = buf[s - 1].z;
                buf[s].t = buf[s - 1].t;
            }
            buf[0].x = px; buf[0].y = py; buf[0].z = pz; buf[0].t = now;
            // trackingSince не сбрасываем — elapsed растёт пока следим за целью
        }
    }

    private void aimTick(GameContext ctx) throws Exception {
        resolve(ctx);
        ensureBus(ctx);
        ensurePipeline(ctx);
        Object me = ctx.player;
        IIlIIliIiI meE = (IIlIIliIiI) me;
        // глаза: interpolated
        double mx = meE.IlIiillIII();
        double my = meE.liiiIllIII() + meE.iliilIiilI(); // + getEyeHeight
        double mz = meE.lIilillIII();
        float myYaw = meE.IIiIillIII(), myPitch = meE.iilIIIlIII();

        int ammoType = classifyAmmo(ctx);
        if (ammoType < 1) return; // diag() выше покажет где сорвалось

        // выбор цели общий (использует и AutoShoot через findTarget)
        float[] sel = new float[3]; // [0]=угол к прицелу °, [1]=смещение кости, [2]=дистанция
        IIlIIliIiI best = selectTarget(ctx, mx, my, mz, DIST_MAX, sel);
        double bestScore = sel[0];
        double bestAimY = sel[1];
        double bestDist = sel[2];
        if (best == null) {
            long nw = System.currentTimeMillis();
            clearSilent();
            if (aimDebug && nw - lastNoTargetLog > 1000L) {
                lastNoTargetLog = nw;
                if (lastTargetId != -1) {
                    Log.info("AimBot", "target LOST id=" + lastTargetId);
                    lastTargetId = -1;
                }
                Log.info("AimBot", "no target: total=" + candTotal + " inFov=" + candFov
                    + " visible=" + candVis + " ammo=" + ammoType);
            }
            return;
        }

        // ==== ПРЕДИКТ 1:1 ИЗ ДАМПА КОНКУРЕНТА (их функция 0x2ae10, реверс 09-10) ====
        // pred = pos + vel*(k*0.05 + flightSec(distH)*k2) + Ydrop(distH)*mult.
        //   k = clamp(elapsed*20 + pingMs*0.02*pingComp, 0, 6) — тики (возраст
        //   трекинга + пинг); k2 = clamp(Prediction,0,2)*factor(=1.0); dist для
        //   баллистики и дропа — ГОРИЗОНТАЛЬНАЯ; 3 итерации фикс. точки (edi=3).
        long id = best.iiIIIIlIII();
        lastTargetSeen = System.currentTimeMillis();
        if (id != lastTargetId) {
            Log.info("AimBot", (lastTargetId == -1 ? "target ACQ: id=" + id
                : "target SWITCH id=" + lastTargetId + " -> id=" + id)
                + " " + nameOf(best) + " d=" + f1(bestDist) + " ang=" + f2(bestScore));
            lastTargetId = id;
        }
        Snapshot[] buf = buffers.get(Long.valueOf(id));
        Long seenAt = trackingSince.get(Long.valueOf(id));
        if (buf == null || seenAt == null) { clearSilent(); return; }
        Snapshot newest = buf[0];
        Snapshot prev = buf[1];

        // скорость цели: delta/dt по кольцу (их vel — сдвиг за тик, кап 5 бл/тик).
        // prev.t==0 (несуществующий снимок) → vel=0, не мусор из нулевого слота.
        double ddx = newest.x - prev.x;
        double ddy = newest.y - prev.y;
        double ddz = newest.z - prev.z;
        double dtSec = (prev.t > 0 && newest.t > prev.t) ? (newest.t - prev.t) / 1000.0 : 0.05;
        double velX = 0, velY = 0, velZ = 0; // блоков/сек
        if (prev.t > 0 && newest.t > prev.t) {
            if (dtSec < 0.01) dtSec = 0.05;
            velX = ddx / dtSec;
            velY = ddy / dtSec;
            velZ = ddz / dtSec;
        }
        double velMag = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
        if (velMag > 100.0) { // 5 блоков/тик (кап 0x299d0)
            double sc = 100.0 / velMag;
            velX *= sc; velY *= sc; velZ *= sc;
            long vcNow = System.currentTimeMillis();
            if (vcNow - lastVelClampLog > 1000L) {
                lastVelClampLog = vcNow;
                Log.info("AimBot", "vel clamp 100: было " + f1(velMag) + " бл/с id=" + id);
            }
        }

        // ВЕРТИКАЛЬ (лог 09-11, k00shka): сырой velY из 2 точек/50мс даёт
        // whiplash ±8 м/с → предикт скачет ±2.5м, прицел дёргается вверх-вниз.
        // Чиним: кламп + EMA + span-земля (разброс Y за 250мс < 0.15м → vy=0).
        double vyRawC = velY;
        if (vyRawC > VY_UP_MAX) vyRawC = VY_UP_MAX;
        if (vyRawC < VY_DOWN_MAX) vyRawC = VY_DOWN_MAX;
        double spanY = ySpan(buf, System.currentTimeMillis(), VY_SPAN_MS);
        Double psm = (Double) smoothVy.get(Long.valueOf(id));
        double svy;
        if (spanY < VY_GROUND_SPAN) svy = 0.0;
        else if (psm == null) svy = vyRawC;
        else svy = psm.doubleValue() + VY_EMA * (vyRawC - psm.doubleValue());
        smoothVy.put(Long.valueOf(id), Double.valueOf(svy));

        // k: возраст СВЕЖЕГО снапшота в тиках + пинг (их aimLatency*0.02*pingComp).
        // ВАЖНО (09-10): у конкурента +0x50 = время ПОСЛЕДНЕГО обновления структуры
        // (пишется каждый тик), а НЕ возраст трекинга — elapsed всегда мал
        // (~1 тик + пинг). Порт с trackingSince давал k=6.0 постоянно →
        // упреждение vel*0.3с сверх нужного → прицел мимо цели.
        double elapsedSec = (System.currentTimeMillis() - newest.t) / 1000.0;
        int pingMs = Watermark.resolvePing(ctx);
        if (pingMs < 0) pingMs = 0;
        if (pingMs > 1000) pingMs = 1000;
        double kTicks = elapsedSec * 20.0 + (pingMs * 0.02) * aimPingComp();
        if (kTicks < 0) kTicks = 0;
        if (kTicks > 6) kTicks = 6;
        double kSec = kTicks * 0.05;

        // k2 = clamp(Prediction, 0, 2) × factor (их per-target фактор, стартует 1.0;
        // пути count<4 держат ровно 1.0 — реверс 0x299d0)
        double k2 = aimPredictScale();
        if (k2 < 0) k2 = 0;
        if (k2 > 2) k2 = 2;

        // базовая точка = кость (глаза / +0.25 лежачий) — их pos = точка прицела
        boolean predictY = stPredictY.get();
        double boneY = newest.y + bestAimY;

        // первый член: pos + vel*k
        double predX = newest.x + velX * kSec;
        double predZ = newest.z + velZ * kSec;
        double predY = boneY + (predictY ? svy * kSec : 0.0);

        // 3 итерации фиксированной точки: dist(pred) → тики полёта → pred заново
        double cLast = 0; // последнее c — в лог (полётное упреждение в сек)
        if (k2 > 0.001) {
            for (int it = 0; it < 3; it++) {
                double ihx = predX - mx, ihz = predZ - mz;
                double distIt = Math.sqrt(ihx * ihx + ihz * ihz);
                float ticks = flightTimeCoeff(distIt, ammoType);
                double c = ticks * 0.05f * k2;
                cLast = c;
                predX = newest.x + velX * (kSec + c);
                predZ = newest.z + velZ * (kSec + c);
                if (predictY) predY = boneY + svy * (kSec + c);
            }
        }

        // кап вертикального лида (сырой vy дёргается — дроп идёт отдельно ниже)
        double yLead = predY - boneY;
        if (yLead > Y_LEAD_MAX) yLead = Y_LEAD_MAX;
        else if (yLead < -Y_LEAD_MAX) yLead = -Y_LEAD_MAX;
        predY = boneY + yLead;

        // Y-drop: их таблицы (0x1760c0/0x176110/0x176160), dist ГОРИЗОНТАЛЬНАЯ
        double dhx = predX - mx, dhz = predZ - mz;
        double distH = Math.sqrt(dhx * dhx + dhz * dhz);
        double drop = dropFor(ammoType, distH);
        predY += drop;

        double px = predX;
        double pz = predZ;
        double py = predY;

        // Направление и поворот (INPUT-паттерн: yaw/pitch читаются камерой до тика,
        // пишем корневые поля Entity — тот же приём, что у конкурента setRotationYaw/Pitch)
        double ax = px - mx, ay = py - my, az = pz - mz;
        double horiz = Math.sqrt(ax * ax + az * az);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(ay, horiz)));

        // ГЕЙТ 4: если предикченная позиция далеко от реальной цели (>15 бл) —
        // данные буфера мусорные (респавн с тем же entityId) — сброс
        double realDx = best.IlIiillIII() - newest.x;
        double realDy = best.liiiIllIII() - newest.y;
        double realDz = best.lIilillIII() - newest.z;
        if (realDx*realDx + realDy*realDy + realDz*realDz > 225.0) {
            // данные устарели — пересоздаём буфер
            buffers.remove(Long.valueOf(id));
            trackingSince.remove(Long.valueOf(id));
            clearSilent();
            return;
        }

        // ПЛАВНАЯ доводка: не прыгаем к цели, а интерполируем за один кадр.
        // MC pitch: положительный вверх, отрицательный вниз. Мы пишем АБСОЛЮТ
        // но ограничиваем скорость, чтобы мышь игрока не боролась с аимом.
        float curYaw = meE.IIiIillIII();
        float curPitch = meE.iilIIIlIII();
        float dyaw = (float) wrapDeg(targetYaw - curYaw);
        float dpitch = targetPitch - curPitch;
        float rawDyaw = dyaw, rawDpitch = dpitch; // для флага CLAMP в логе

        // ==== AUTO SHOOT (настройка): цель есть и видна (selectTarget уже
        // отфильтровал) → ЛКМ-клик через java.awt.Robot. Форк читает мышь
        // напрямую — поле KeyBinding огонь не триггерит (лог 09-11: pressing
        // = true без выстрелов). Клик эмулирует реальное нажатие ОС.
        try {
            if (stAutoShoot.get()) {
                long now = System.currentTimeMillis();
                // silent: первые пули после смены цели летят в старый взгляд —
                // держим огонь, пока свежий взгляд едет до сервера
                if (silentMode() && now - lastAcquireMs < SETTLE_MS) {
                    if (now - lastSettleLog > 2000L) {
                        lastSettleLog = now;
                        Log.info("AimBot", "auto shoot held: look settling");
                    }
                } else {
                long interval = (long) stShootInterval.value;
                if (now - lastAutoShootLog > 2000L) {
                    lastAutoShootLog = now;
                    Log.info("AimBot", "auto shoot: target d=" + f1(bestDist)
                        + " interval=" + interval);
                }
                if (now - lastAutoClick >= interval) {
                    lastAutoClick = now;
                    requestClick();
                }
                }
            }
        } catch (Throwable t) {
            Log.error("AimBot", "auto shoot failed", t);
        }

        // МЁРТВАЯ ЗОНА: малые отклонения не пишем (мышь игрока доминирует)

        // Ограничение скорости на запись (10° за 50мс = 200°/с максимум)
        float MAX_TURN = 10f;
        if (dyaw > MAX_TURN) dyaw = MAX_TURN;
        if (dyaw < -MAX_TURN) dyaw = -MAX_TURN;
        if (dpitch > MAX_TURN) dpitch = MAX_TURN;
        if (dpitch < -MAX_TURN) dpitch = -MAX_TURN;
        float newYaw = curYaw + dyaw;
        float newPitch = curPitch + dpitch;
        if (newPitch > 90f) newPitch = 90f;
        if (newPitch < -90f) newPitch = -90f;
        // silent-снапшот (ОБА режима — бесшовное переключение Vector/Silent):
        // слушатель шины перенаправит удар, Netty-хендлер — взгляд C03.
        // Углы клампим тем же MAX_TURN от прошлого опубликованного (паритет
        // с vector: сервер видит ту же динамику доводки, камера стоит).
        if (!silentHave) {
            silentYaw = curYaw;
            silentPitch = curPitch;
        }
        float sdy = (float) wrapDeg((double) targetYaw - (double) silentYaw);
        float sdp = targetPitch - silentPitch;
        if (sdy > MAX_TURN) sdy = MAX_TURN;
        else if (sdy < -MAX_TURN) sdy = -MAX_TURN;
        if (sdp > MAX_TURN) sdp = MAX_TURN;
        else if (sdp < -MAX_TURN) sdp = -MAX_TURN;
        silentYaw += sdy;
        silentPitch += sdp;
        if (silentPitch > 90f) silentPitch = 90f;
        else if (silentPitch < -90f) silentPitch = -90f;
        // новая цель/свежий трек: взгляд на сервере обновится только со
        // следующим C03 — шлём Rotation сразу + держим автоогонь SETTLE_MS
        if (!silentHave || id != lastSilentId) {
            lastSilentId = id;
            lastAcquireMs = System.currentTimeMillis();
            sendProactiveLook(ctx);
        }
        silentTarget = best;
        silentTime = System.currentTimeMillis();
        silentHave = true;
        if (!silentMode()) {
            // VECTOR: доворот камеры через setRotation (как раньше).
            // SILENT: камеру НЕ трогаем — удар перенаправит слушатель шины.
            // Вызываем МЕТОДЫ setRotation(yaw,pitch) — они обновляют prev-поля и
            // синхронизируют камеру (field-write этого НЕ делает → дёрганье).
            // Entity root: iiiiIllIII(FF)V = setRotation(yaw,pitch) [ZNANIA 14.1]
            if (rotateMethod == null) {
                Class root = ctx.gameLoader.loadClass("rustme.IIlIIliIiI");
                for (Method m : root.getMethods()) {
                    if (m.getName().equals("iiiiIllIII") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == Float.TYPE
                        && m.getParameterTypes()[1] == Float.TYPE) {
                        rotateMethod = m;
                        break;
                    }
                }
            }
            if (rotateMethod != null) {
                rotateMethod.invoke(me, Float.valueOf(newYaw), Float.valueOf(newPitch));
            }
        }

        // ==== ДИАГНОСТИКА ДЛЯ АНАЛИЗА ПРОМАХОВ ====
        long nw = System.currentTimeMillis();
        boolean clamped = dyaw != rawDyaw || dpitch != rawDpitch;
        // строка состояния раз в 100мс (не каждый тик — их ~1000/с)
        if (aimDebug && nw - lastStateLog >= 100L) {
            lastStateLog = nw;
            Log.info("AimBot", "aim: " + nameOf(best) + "#" + id
                + " mode=" + (silentMode() ? "silent" : "vector")
                + " d=" + f1(distH)
                + " ammo=" + ammoType + " ping=" + pingMs
                + " snapAge=" + (nw - newest.t) + "мс dt=" + f0(dtSec * 1000.0) + "мс"
                + " vel=(" + f2(velX) + "," + f2(velY) + "," + f2(velZ) + ")"
                + " vyS=" + f2(svy) + " sp=" + f2(spanY)
                + " k=" + f2(kTicks) + " c=" + f2(cLast) + " drop=" + f2(drop)
                + " pred=(" + f1(px) + "," + f1(py) + "," + f1(pz) + ")"
                + " real=(" + f1(best.IlIiillIII()) + "," + f1(best.liiiIllIII())
                    + "," + f1(best.lIilillIII()) + ")"
                + " yaw " + f1(curYaw) + "->" + f1(wrapDeg(targetYaw))
                + " pitch " + f1(curPitch) + "->" + f1(targetPitch)
                + (clamped ? " [CLAMP]" : ""));
        }
        // SHOT-маркер: нажатие ЛКМ при активной цели — снимок всех параметров
        // на момент выстрела (именно эти строки сверяем с попаданием/промахом)
        boolean lmb = false;
        try {
            lmb = ctx.isMouseButtonDown(0);
        } catch (Throwable ignore) {}
        if (lmb && !lastLmb) {
            Log.info("AimBot", "SHOT: " + nameOf(best) + "#" + id
                + " d=" + f1(distH) + " ammo=" + ammoType + " ping=" + pingMs
                + " snapAge=" + (nw - newest.t) + "мс"
                + " vel=(" + f2(velX) + "," + f2(velY) + "," + f2(velZ) + ")"
                + " vyS=" + f2(svy) + " sp=" + f2(spanY)
                + " k=" + f2(kTicks) + " c=" + f2(cLast) + " drop=" + f2(drop)
                + " pred=(" + f1(px) + "," + f1(py) + "," + f1(pz) + ")"
                + " real=(" + f1(best.IlIiillIII()) + "," + f1(best.liiiIllIII())
                    + "," + f1(best.lIilillIII()) + ")"
                + " yaw=" + f1(newYaw) + " pitch=" + f1(newPitch));
        }
        lastLmb = lmb;
        // статистика шины (события атаки seen — идут ли выстрелы через событие)
        if (nw - lastBusStatLog > 5000L) {
            lastBusStatLog = nw;
            if (steerEvents > 0 || busSubscribed) {
                Log.info("AimBot", "bus: subscribed=" + busSubscribed
                    + " attackEvents=" + steerEvents + " steered=" + steerShots);
            }
        }
    }


    // ===== Баллистика (1:1 из дампа: функция 0x29440 + таблицы .rdata) =====
    // Три строки по 20 f32 в .rdata; выбор строки по типу патрона:
    //   type==0    → ROW_TYPE0  (0x176200..0x17624c)
    //   type==2,3  → ROW_RIFLE  (0x1761b0..0x1761fc)
    //   type==1,4  → ROW_PISTOL (0x176250..0x17629c)
    // Значения = время полёта (в тиках) на дистанциях, лерп по idx с клампом [0..18],
    // комisd-пороги: dist<0.00062 → t[0]; dist>200.0 (f32 в .data @0x33697c) → t[19].
    // Базовые coeff — переменные в .data (0x2d75e8=0.65 type0, 0x2d75f4=0.70 type1/4,
    // 0x2d7600=0.75 type2/3), в дампе нулевые, инициализируются рантаймом —
    // константы 0.65/0.70/0.75 взяты из ZNANIA-дизасма.
    private static final float[] ROW_TYPE0 = {
        4.842f, 6.654f, 8.286f, 9.842f, 11.362f, 12.864f, 14.357f, 15.844f,
        17.329f, 18.813f, 20.295f, 21.777f, 23.259f, 24.748f, 26.237f, 27.726f,
        29.215f, 30.703f, 32.192f, 33.681f
    };
    private static final float[] ROW_RIFLE = {
        4.573f, 6.003f, 7.200f, 8.364f, 9.509f, 10.641f, 11.767f, 12.888f,
        14.004f, 15.120f, 16.235f, 17.348f, 18.464f, 19.584f, 20.705f, 21.826f,
        22.946f, 24.067f, 25.188f, 26.308f
    };
    private static final float[] ROW_PISTOL = {
        4.578f, 5.924f, 7.161f, 8.347f, 9.507f, 10.650f, 11.783f, 12.909f,
        14.032f, 15.152f, 16.233f, 17.347f, 18.551f, 19.677f, 20.804f, 21.930f,
        23.057f, 24.183f, 25.310f, 26.436f
    };
    private static final float BALL_DIST_MIN = 4.0f;
    private static final float BALL_DIST_MAX = 200.0f;   // f32 @0x33697c
    private static final int LERP_MAX_IDX = 18;     // mov eax,0x12; cmovg

    private static float baseCoeff(int ammoType) {
        switch (ammoType) {
            case 2: case 3: return 0.75f;  // rifle/HV
            case 1: case 4: return 0.70f;  // pistol/hv9
            case 0: case 5: return 0.65f;  // arrow/hv_arrow
            default: return 0.75f;
        }
    }

    private static float[] rowFor(int ammoType) {
        switch (ammoType) {
            case 0: return ROW_TYPE0;
            case 1: case 4: return ROW_PISTOL;
            default: return ROW_RIFLE;   // 2, 3 и fallback
        }
    }

    // ==== Y-DROP ТАБЛИЦЫ КОНКУРЕНТА (их .rdata 0x1760c0/0x176110/0x176160, 1:1) ====
    // ammo 0/5 (стрелы) — большой дроп; 1/4 (пистолет/9мм) — средний; 2/3 (винтовка) — малый.
    private static final float[] DROP_ARROW = {
        0.0617f, 0.2454f, 0.5383f, 0.9414f, 1.4628f, 2.0944f, 2.8495f, 3.7173f,
        4.7096f, 5.8174f, 7.0487f, 8.3979f, 9.8691f, 11.454f, 13.1649f, 14.9748f,
        16.8836f, 18.8914f, 20.9982f, 23.2039f
    };
    private static final float[] DROP_PISTOL = {
        -0.0223f, -0.0084f, 0.0556f, 0.1716f, 0.3394f, 0.5606f, 0.8364f, 1.1678f,
        1.5554f, 2.0034f, 2.6112f, 3.2626f, 3.9578f, 4.6967f, 5.4793f, 6.3055f,
        7.1754f, 8.089f, 9.0463f, 10.0473f
    };
    private static final float[] DROP_RIFLE = {
        -0.0165f, -0.0327f, -0.0295f, 0.0034f, 0.072f, 0.1801f, 0.33f, 0.5291f,
        0.7689f, 1.0562f, 1.4135f, 1.7996f, 2.8008f, 3.0831f, 3.449f, 3.8406f,
        4.2579f, 4.7008f, 5.1694f, 5.6637f
    };

    private static float[] dropRow(int ammoType) {
        switch (ammoType) {
            case 0: case 5: return DROP_ARROW;
            case 1: case 4: return DROP_PISTOL;
            default: return DROP_RIFLE;   // 2, 3 и fallback
        }
    }

    private static float dropMult(int ammoType) {
        switch (ammoType) {
            case 5: return 0.45f;   // их xmm3 @0x2b255
            case 4: return 0.55f;   // 0x2b27e
            case 3: return 0.6f;    // 0x2b2a7
            default: return 1.0f;   // 0x2b2b1
        }
    }

    /** Их Y-drop (хвост 0x2ae10): distH ≤ 10 → max(0, tbl[0]); 10..200 → лерп
     *  по (distH−10)/10, причём клампится ТОЛЬКО верхний сэмпл (max(0, tbl[idx+1])),
     *  нижний берётся как есть; ≥ 200 → max(0, tbl[19]); результат × mult. */
    private static double dropFor(int ammoType, double distH) {
        float[] tbl = dropRow(ammoType);
        double drop;
        if (distH <= 10.0) {
            drop = Math.max(0f, tbl[0]);
        } else if (distH < 200.0) {
            double t = (distH - 10.0) / 10.0;
            int idx = (int) t;
            if (idx < 0) idx = 0;
            if (idx > 18) idx = 18;
            double frac = t - idx;
            double v1 = Math.max(0f, tbl[idx + 1]);
            double v0 = tbl[idx];
            drop = (v1 - v0) * frac + v0;
        } else {
            drop = Math.max(0f, tbl[19]);
        }
        return drop * dropMult(ammoType);
    }

    /**
     * flightTimeCoeff(dist, ammoType): точная копия их 0x29440.
     * t = row[clamp((dist - 4) / (200 - 4) * 19, 0, 18)];
     * coeff = base * lerp(t[idx], t[idx+1], frac); dist за пределами [4, 200] — кламп.
     * Возвращает множитель упреждения — умножается на предикт-delta.
     */
    private static float flightTimeCoeff(double distD, int ammoType) {
        float dist = (float) distD;
        float base = baseCoeff(ammoType);
        float[] row = rowFor(ammoType);
        if (dist <= BALL_DIST_MIN) return base * row[0];
        if (dist >= BALL_DIST_MAX) return base * row[LERP_MAX_IDX]; // их mulss xmm3,[rax] = &row[18]
        float t = (dist - BALL_DIST_MIN) / (BALL_DIST_MAX - BALL_DIST_MIN) * (LERP_MAX_IDX + 1);
        int idx = (int) t;
        if (idx < 0) idx = 0;
        if (idx > LERP_MAX_IDX) idx = LERP_MAX_IDX;
        float frac = t - idx;
        float a = row[idx];
        float b = row[idx + 1];
        return base * (a + (b - a) * frac);
    }

    private long lastAmmoDiag;

    /** Диагностика classifyAmmo: раз в 2с (aimDebug). */
    private void diag(String msg) {
        if (!aimDebug) return;
        long now = System.currentTimeMillis();
        if (now - lastAmmoDiag > 2000L) {
            lastAmmoDiag = now;
            Log.info("AimBot", "ammo: " + msg);
        }
    }

    // ===== Классификация по НАЗВАНИЮ оружия (displayName из лога юзера) =====
    // displayName содержит название модели: "MP5A4", "Винтовка", "LR300" и т.д.
    // Маппинг по типу патрона (как в Rust): SMG/пистолет→9mm(1), штурмовые→5.56(2),
    // HV→3, лук→5.
    private static int classifyWeaponString(String s) {
        if (s == null || s.isEmpty()) return -1;
        String v = s.toLowerCase();
        // --- HV-варианты (высокоскоростные) ---
        if (v.contains("5_56_hv") || v.contains("hv_5_56") || v.contains("556_hv")) return 3;
        if (v.contains("hv") && (v.contains("rifle") || v.contains("винтовка"))) return 3;
        // --- стрелковое ---
        if (v.contains("mp5") || v.contains("smg") || v.contains("custom smg")) return 1;
        if (v.contains("thompson")) return 1;
        if (v.contains("python") || v.contains("revolver")) return 1;
        if (v.contains("nailgun")) return 1;
        if (v.contains("pistol") || v.contains("пистолет")) return 1;
        if (v.contains("semi-auto pistol")) return 1;
        if (v.contains("spas") || v.contains("shotgun") || v.contains("дробовик")) return 1;
        if (v.contains("waterpipe")) return 1;
        if (v.contains("double barrel") || v.contains("двустволка")) return 1;
        if (v.contains("eoka")) return 1;
        if (v.contains("flamethrower") || v.contains("огнемёт")) return 1;
        // --- штурмовые винтовки / болтовки (5.56) ---
        if (v.contains("lr300") || v.contains("lr-300")) return 2;
        if (v.contains("ak") || v.contains("assault rifle") || v.contains("автомат")) return 2;
        if (v.contains("m4")) return 2;
        if (v.contains("bolt") || v.contains("болтов")) return 2;
        if (v.contains("l96") || v.contains("снайпер")) return 2;
        if (v.contains("m39") || v.contains("dmr") || v.contains("semi-auto rifle")) return 2;
        if (v.contains("винтовка")) return 2;
        if (v.contains("rifle") || v.contains("m249") || v.contains("m249")) return 2;
        if (v.contains("556") || v.contains("5_56") || v.contains("5.56")) return 2;
        // --- лук/арбалет ---
        if (v.contains("bow") || v.contains("лук") || v.contains("арбалет")
            || v.contains("crossbow") || v.contains("compound")) return 5;
        if (v.contains("arrow") || v.contains("стрел")) return 5;
        // --- гранаты/взрывчатка → HV (для аима не важно, но пусть будет) ---
        if (v.contains("grenade") || v.contains("гранат") || v.contains("rocket")
            || v.contains("explosive") || v.contains("с4")) return 3;
        return -1;
    }

    /** Достаёт строку-ID оружия из стака: unlocalizedName + displayName + NBT ammo. */
    private String weaponString(Object stack) throws Exception {
        StringBuilder sb = new StringBuilder();
        // unlocalizedName / displayName (оба String-геттера ItemStack)
        for (String mn : new String[]{"IlIlIililI", "iIllIililI"}) {
            try {
                Method m = stack.getClass().getMethod(mn);
                Object s = m.invoke(stack);
                if (s instanceof String) sb.append((String) s).append(' ');
            } catch (Throwable ignore) {}
        }
        // NBT rustme_subdata: строковые ключи ammo
        try {
            Object tag = stackGetTag.invoke(stack);
            if (tag != null) {
                Object sub = nbtGetCompound.invoke(tag, "rustme_subdata");
                if (sub != null) {
                    for (String key : new String[]{"ammo_type", "ammo", "bullet", "weapon"}) {
                        try {
                            Method gs = sub.getClass().getMethod("lIiIiIlilI", String.class);
                            Object v = gs.invoke(sub, key);
                            if (v instanceof String && !((String) v).isEmpty()) sb.append(v).append(' ');
                        } catch (Throwable ignore) {}
                    }
                }
            }
        } catch (Throwable ignore) {}
        return sb.toString();
    }

    /** Классификация патрона: строка оружия → enum их 0x28e10. -1 = не стрелять. */
    private int classifyAmmo(GameContext ctx) throws Exception {
        if (inventoryField == null) { diag("resolver null"); return -1; }
        Object inv = inventoryField.get(ctx.player);
        if (inv == null) { diag("inv null"); return -1; }
        Method cur = inv.getClass().getMethod("lIiIIIiilI");
        Object stack = cur.invoke(inv);
        if (stack == null) { diag("stack null (пустая рука)"); return -1; }
        String ws = weaponString(stack);
        int r = classifyWeaponString(ws);
        diag("ws=\"" + ws.trim() + "\" -> " + r);
        return r;
    }

    /** Knocked: цели-тиммейта со статусом Wounded/Downed. Без TeamInfo = не knocked. */
    private boolean isKnocked(IIlIIliIiI e) {
        // лёгкая эвристика: knocked-игрок лежит → pitch в узком диапазоне и он не в воздухе
        // точный путь (TeamInfo→Wounded) требует отдельного резолва; сейчас pitch-эвристика
        try {
            float pitch = e.iilIIIlIII();
            float yaw = e.IIiIillIII();
            // у лежачих pitch сильно отрицательный или положительный (камера набок)
            return pitch > 55.0f || pitch < -55.0f;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Выбор цели: перебор playerEntities, FOV-КОНУС (3D-угол взгляд↔цель,
     * 0..180; FOV=360 → half=180 → цели со всех сторон), Visible Check.
     * out[0]=угол °, out[1]=смещение кости (knocked/head), out[2]=дистанция.
     * Работает и при выключенном модуле (нужно AutoShoot) — настройки INSTANCE.
     */
    private IIlIIliIiI selectTarget(GameContext ctx, double mx, double my, double mz,
                                    float maxDist, float[] out) throws Exception {
        out[0] = 999f; out[1] = 0f; out[2] = 0f;
        java.util.List<?> players = (java.util.List<?>) ctx.playersField.get(ctx.world);
        if (players == null) return null;
        Object[] arr = players.toArray(new Object[0]);

        IIlIIliIiI best = null;
        double bestScore = Double.MAX_VALUE;
        double bestAimY = 0;
        double bestDist = 0;
        candTotal = 0; candFov = 0; candVis = 0;

        // вектор взгляда (MC: pitch+ = вниз)
        IIlIIliIiI me = (IIlIIliIiI) ctx.player;
        double myYaw = me.IIiIillIII(), myPitch = me.iilIIIlIII();
        double yr = Math.toRadians(myYaw), pr = Math.toRadians(myPitch);
        double vx = -Math.sin(yr) * Math.cos(pr);
        double vy = -Math.sin(pr);
        double vz = Math.cos(yr) * Math.cos(pr);

        for (int i = 0; i < arr.length; i++) {
            Object w = arr[i];
            if (w == null || ctx.localSpClass.isInstance(w)) continue;
            if (!(w instanceof IIlIIliIiI)) continue;
            candTotal++;
            IIlIIliIiI e = (IIlIIliIiI) w;
            double tx = e.IlIiillIII(), ty = e.liiiIllIII(), tz = e.lIilillIII();
            double dx = tx - mx, dy = ty - my, dz = tz - mz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < DIST_MIN || dist > maxDist) continue;

            boolean knocked = isKnocked(e);
            if (aimIgnoreKnocked && knocked) continue;

            // друзья вне прицела (utils.etc.Friends): не наводимся, не стреляем
            try {
                if (utils.etc.Friends.isFriend(nameOf(e))) continue;
            } catch (Throwable ignore) {}

            // угол цель↔прицел (3D, градусы)
            double dot = (dx * vx + dy * vy + dz * vz) / Math.max(1e-9, dist);
            double ang = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
            if (ang > aimFov() * 0.5) continue;
            candFov++;

            if (visibleCheck() && !isVisible(ctx, mx, my, mz, tx, ty + EYE_HEIGHT_STAND, tz)) continue;
            candVis++;

            if (ang < bestScore) {
                bestScore = ang;
                best = e;
                bestAimY = knocked ? KNOCKED_OFFSET : EYE_HEIGHT_STAND;
                bestDist = dist;
            }
        }
        if (best != null) {
            out[0] = (float) bestScore;
            out[1] = (float) bestAimY;
            out[2] = (float) bestDist;
        }
        return best;
    }

    /**
     * Публичная точка для AutoShoot: выбор цели с настройками AimBot
     * (FOV-конус + Visible Check), НЕ зависит от состояния модуля AimBot.
     */
    public static IIlIIliIiI findTarget(GameContext ctx, float maxDist, float[] out) {
        AimBot inst = INSTANCE;
        if (inst == null || ctx == null || ctx.world == null || ctx.player == null) {
            if (out != null) { out[0] = 999f; out[1] = 0f; out[2] = 0f; }
            return null;
        }
        try {
            IIlIIliIiI me = (IIlIIliIiI) ctx.player;
            double mx = me.IlIiillIII(), my = me.liiiIllIII() + me.iliilIiilI(), mz = me.lIilillIII();
            return inst.selectTarget(ctx, mx, my, mz, maxDist, out);
        } catch (Throwable t) {
            if (out != null) { out[0] = 999f; out[1] = 0f; out[2] = 0f; }
            return null;
        }
    }

    // видимость: Vec3-ctor + World.IlIilllllI(Vec3,Vec3) (ванильный
    // rayTraceBlocks(start,end); null = чисто). Кэш: резолв один раз —
    // вызов был на каждого кандидата каждый тик.
    private java.lang.reflect.Constructor<?> vecCtor;
    private Method rayM;
    private boolean visFailLogged;

    /**
     * Ray-trace видимость: глаза → голова цели. Fail-open, но с РАЗОВЫМ
     * логом: прежняя версия резолвила несуществующую сигнатуру
     * (lIllIilIiI = BlockPos, а не Vec3!) и молча всегда возвращала true —
     * проверка фактически не работала (выяснено 09-10).
     */
    private boolean isVisible(GameContext ctx, double x0, double y0, double z0,
                              double x1, double y1, double z1) {
        try {
            if (ctx.world == null) return true;
            if (rayM == null) {
                Class vecC = ctx.gameLoader.loadClass("rustme.lliililIiI"); // Vec3
                vecCtor = vecC.getConstructor(Double.TYPE, Double.TYPE, Double.TYPE);
                rayM = ctx.worldClass.getMethod("IlIilllllI", vecC, vecC);
            }
            Object start = vecCtor.newInstance(Double.valueOf(x0), Double.valueOf(y0), Double.valueOf(z0));
            Object end = vecCtor.newInstance(Double.valueOf(x1), Double.valueOf(y1), Double.valueOf(z1));
            return rayM.invoke(ctx.world, start, end) == null;
        } catch (Throwable t) {
            if (!visFailLogged) {
                visFailLogged = true;
                Log.error("AimBot", "visible check failed (fail-open)", t);
            }
            return true;
        }
    }

    private void resolve(GameContext ctx) throws Exception {
        if (resolved) return;
        resolved = true;
        try {
            Class stackCls = ctx.gameLoader.loadClass("rustme.liIIIIIIiI");
            Class nbtCls = ctx.gameLoader.loadClass("rustme.liilIIIIiI");
            stackGetTag = stackCls.getMethod("llIiIililI");
            nbtGetCompound = nbtCls.getMethod("IIlliIlilI", String.class);
            Log.info("AimBot", "NBT ammo chain resolved");
        } catch (Throwable t) {
            Log.error("AimBot", "nbt resolve failed", t);
        }
        // rotateMethod резолвится лениво в aimTick (нужен root-класс)
        try {
            // iIliiIiII объявлен в wrapper (IIiIIiIIiI), НЕ в SP — иерархический подъём
            Class c2 = ctx.localSpClass;
            while (c2 != null) {
                try {
                    inventoryField = c2.getDeclaredField("iIliiIiII");
                    inventoryField.setAccessible(true);
                    Log.info("AimBot", "inventory field resolved on " + c2.getName());
                    break;
                } catch (Throwable ignore) {}
                c2 = c2.getSuperclass();
            }
            if (inventoryField == null) Log.error("AimBot", "inventory field NOT found", null);
        } catch (Throwable t) {
            Log.error("AimBot", "inventory resolve failed", t);
        }
    }

    private static float clampF(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static double wrapDeg(double d) {
        while (d > 180.0) d -= 360.0;
        while (d < -180.0) d += 360.0;
        return d;
    }

    /** Разброс Y по свежим снимкам кольца (для span-земли вертикали). */
    private static double ySpan(Snapshot[] buf, long now, long windowMs) {
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        boolean any = false;
        for (int i = 0; i < buf.length; i++) {
            Snapshot s = buf[i];
            if (s == null || now - s.t > windowMs) continue;
            if (s.y < mn) mn = s.y;
            if (s.y > mx) mx = s.y;
            any = true;
        }
        return any ? mx - mn : 0.0;
    }

    // ===== форматирование лога (Locale.US — иначе на ru-WIN запятые) =====

    private static String f0(double v) { return String.valueOf(Math.round(v)); }

    private static String f1(double v) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(v));
    }

    private static String f2(double v) {
        return String.format(java.util.Locale.US, "%.2f", Double.valueOf(v));
    }

    /** Ник цели (wrapper.IlIiIiiilI → GameProfile.getName, кэш по entityId). */
    private String nameOf(IIlIIliIiI e) {
        long id;
        try {
            id = e.iiIIIIlIII();
        } catch (Throwable t) {
            return "?";
        }
        Long key = Long.valueOf(id);
        String cached = (String) nameCache.get(key);
        if (cached != null) return cached;
        String n = "#" + id;
        try {
            // через reflection: тип GameProfile отсутствует в cp компиляции
            Method profileGetter = e.getClass().getMethod("IlIiIiiilI");
            Object profile = profileGetter.invoke(e);
            if (profile != null) {
                Object nm = profile.getClass().getMethod("getName").invoke(profile);
                if (nm instanceof String && !((String) nm).isEmpty()) n = (String) nm;
            }
        } catch (Throwable ignore) {}
        if (nameCache.size() > 64) nameCache.clear();
        nameCache.put(key, n);
        return n;
    }

    /** FOV-круг по центру экрана. Вызывается из CheatHud.renderFrame после Esp.render. */
    public static void render(GameContext ctx, float partialTicks, int scaledW, int scaledH) {
        try {
            if (INSTANCE == null || !INSTANCE.isState() || !INSTANCE.drawFov()) return;
            if (!ctx.inWorld || ctx.player == null) return;

            // радиус FOV-конуса на экране от КАМЕРЫ: r = tan(fov/2)/tan(camFov/2)
            // × (высота экрана/2). camFovY — вертикальный FOV камеры из
            // захваченной PROJ (m11 = 1/tan(fovY/2); fallback 90 — рендер форка).
            // Прежний r = fov/180 × screenH/2 мерил от ЭКРАНА, не от камеры.
            float camFovY = 90f;
            try {
                java.nio.FloatBuffer proj = rustme.lliIilliiI.lIIlIlIl;
                if (proj != null && proj.capacity() >= 16) {
                    float m11 = proj.get(5);
                    if (Math.abs(m11) > 0.2f && Math.abs(m11) < 5f) {
                        camFovY = (float) Math.toDegrees(2.0 * Math.atan(1.0 / m11));
                    }
                }
            } catch (Throwable ignore) {}
            float half = INSTANCE.aimFov() * 0.5f;
            if (half >= 89.9f) return; // конус ≥180° накрывает весь экран
            float r = (float) (Math.tan(Math.toRadians(half))
                / Math.tan(Math.toRadians(Math.min(89.0, camFovY * 0.5f))) * (scaledH * 0.5f));
            if (r > Math.max(scaledW, scaledH) * 1.5f) return; // круг ушёл за экран
            float cx = scaledW * 0.5f, cy = scaledH * 0.5f;

            boolean blendWas = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depthWas = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean texWas = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            boolean lineSmoothWas = GL11.glIsEnabled(GL11.GL_LINE_SMOOTH);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1.0f);

            // акцентный цвет клиента, 60% альфы
            GL11.glColor4f(0x90 / 255f, 0x6B / 255f, 0xFF / 255f, 0.6f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            int seg = 64;
            for (int i = 0; i < seg; i++) {
                double a = (Math.PI * 2.0) * i / seg;
                GL11.glVertex2f(cx + (float) (Math.cos(a) * r), cy + (float) (Math.sin(a) * r));
            }
            GL11.glEnd();

            GL11.glColor4f(1f, 1f, 1f, 1f);
            if (!lineSmoothWas) GL11.glDisable(GL11.GL_LINE_SMOOTH);
            if (texWas) GL11.glEnable(GL11.GL_TEXTURE_2D);
            if (depthWas) GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (!blendWas) GL11.glDisable(GL11.GL_BLEND);
        } catch (Throwable t) {
            Log.error("AimBot", "fov render failed", t);
        }
    }

    private static final float[] TMP = new float[2];
}
