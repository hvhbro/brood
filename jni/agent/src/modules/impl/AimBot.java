package modules.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.lwjglx.opengl.GL11;

import events.EventBus;
import events.EventBus.TickEvent;
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

    // Настройки (их дефолты; элементы в меню: Module.addSetting/addBool)
    private final Module.FloatSetting stFov = addSetting("FOV", 10f, 180f, 1f, 120f);
    private final Module.FloatSetting stPingComp = addSetting("Ping Comp", 0.1f, 2f, 0.05f, 0.5f);
    private final Module.FloatSetting stPredict = addSetting("Prediction", 0.1f, 3f, 0.05f, 1.0f);
    private final Module.BoolSetting stDrawFov = addBool("Draw FOV", true);
    private final Module.BoolSetting stPredictY = addBool("Predict Y", true);

    private float aimFov() { return stFov.value; }
    private float aimPingComp() { return stPingComp.value; }
    private float aimPredictScale() { return stPredict.value; }
    private boolean drawFov() { return stDrawFov.get(); }
    private static final boolean aimVisibleCheck = true;
    private static final boolean aimIgnoreKnocked = false;
    private static final boolean aimDebug = true;

    private long lastLog;
    private long lastAimWrite;

    // ==== Диагностика для пост-анализа промахов (09-10) ====
    private long lastStateLog;      // aim-строка состояния: раз в 100мс
    private long lastNoTargetLog;   // причина «нет цели»: раз в 1с
    private long lastResetLog;      // ресеты кольца: раз в 1с
    private long lastVelClampLog;
    private long lastTargetId = -1; // для ACQ/SWITCH/LOST-событий
    private boolean lastLmb;        // edge ЛКМ = маркер выстрела в логе
    private final java.util.HashMap<Long, String> nameCache = new java.util.HashMap<Long, String>();

    // Anti-lag буферы на игрока (per-entity ring): entityId → Position[] + время
    private final java.util.HashMap<Long, Snapshot[]> buffers = new java.util.HashMap<Long, Snapshot[]>();
    private final java.util.HashMap<Long, Long> trackingSince = new java.util.HashMap<Long, Long>();

    // reflection-кэш (ленивый)
    private boolean resolved;
    private Method stackGetTag;        // liIIIIIIiI.llIiIililI() → liilIIIIiI
    private Method nbtGetCompound;     // liilIIIIiI.IIlliIlilI(String) → liilIIIIiI
    private Method nbtGetInt;          // liilIIIIiI.iiIIiIlilI(String) → I
    private Method rotateMethod;       // Entity.iiiiIllIII(FF)V = setRotation(yaw,pitch)
    private Field inventoryField;      // wrapper.iIliiIiII (InventoryPlayer) — с иерархией
    private Object world;

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
            nameCache.clear();
            lastTargetId = -1;
            lastLmb = false;
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

    /** Обновляет ring-buffer позиций всех игроков (шаг снимка >= MAG0). */
    private void collectSnapshots(GameContext ctx) throws Exception {
        Object worldNow = ctx.world;
        if (worldNow != world) {
            world = worldNow;
            buffers.clear();
            trackingSince.clear();
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
            // сдвиг: [0] ← новый, остальные ← старые (delta = движение за интервал)
            for (int s = buf.length - 1; s > 0; s--) {
                buf[s].x = buf[s - 1].x; buf[s].y = buf[s - 1].y; buf[s].z = buf[s - 1].z;
            }
            buf[0].x = px; buf[0].y = py; buf[0].z = pz; buf[0].t = now;
            // trackingSince не сбрасываем — elapsed растёт пока следим за целью
        }
    }

    private void aimTick(GameContext ctx) throws Exception {
        resolve(ctx);
        Object me = ctx.player;
        IIlIIliIiI meE = (IIlIIliIiI) me;
        // глаза: interpolated
        double mx = meE.IlIiillIII();
        double my = meE.liiiIllIII() + meE.iliilIiilI(); // + getEyeHeight
        double mz = meE.lIilillIII();
        float myYaw = meE.IIiIillIII(), myPitch = meE.iilIIIlIII();

        int ammoType = classifyAmmo(ctx);
        if (ammoType < 1) return; // diag() выше покажет где сорвалось

        java.util.List<?> players = (java.util.List<?>) ctx.playersField.get(ctx.world);
        if (players == null) return;
        Object[] arr = players.toArray(new Object[0]);

        IIlIIliIiI best = null;
        double bestScore = Double.MAX_VALUE;
        double bestAimY = 0;
        double bestDist = 0;
        int candTotal = 0, candFov = 0, candVis = 0; // для no-target диагностики

        for (int i = 0; i < arr.length; i++) {
            Object w = arr[i];
            if (w == null || ctx.localSpClass.isInstance(w)) continue;
            if (!(w instanceof IIlIIliIiI)) continue;
            candTotal++;
            IIlIIliIiI e = (IIlIIliIiI) w;
            double tx = e.IlIiillIII(), ty = e.liiiIllIII(), tz = e.lIilillIII();
            double dx = tx - mx, dy = ty - my, dz = tz - mz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < DIST_MIN || dist > DIST_MAX) continue;

            boolean knocked = isKnocked(e);
            if (aimIgnoreKnocked && knocked) continue;

            // FOV-гейт: угол взгляд→цель <= aimFov/2
            double yawTo = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
            double pitchTo = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            double dyaw = wrapDeg(yawTo - myYaw);
            double dpitch = pitchTo - myPitch;
            double ang = Math.sqrt(dyaw * dyaw + dpitch * dpitch);
            if (ang > aimFov() * 0.5) continue;
            candFov++;

            if (aimVisibleCheck && !isVisible(ctx, mx, my, mz, tx, ty + EYE_HEIGHT_STAND, tz)) continue;
            candVis++;

            if (ang < bestScore) {
                bestScore = ang;
                best = e;
                bestAimY = knocked ? KNOCKED_OFFSET : EYE_HEIGHT_STAND;
                bestDist = dist;
            }
        }
        if (best == null) {
            long nw = System.currentTimeMillis();
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
        if (id != lastTargetId) {
            Log.info("AimBot", (lastTargetId == -1 ? "target ACQ: id=" + id
                : "target SWITCH id=" + lastTargetId + " -> id=" + id)
                + " " + nameOf(best) + " d=" + f1(bestDist) + " ang=" + f2(bestScore));
            lastTargetId = id;
        }
        Snapshot[] buf = buffers.get(Long.valueOf(id));
        Long seenAt = trackingSince.get(Long.valueOf(id));
        if (buf == null || seenAt == null) return;
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
        double predY = boneY + (predictY ? velY * kSec : 0.0);

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
                if (predictY) predY = boneY + velY * (kSec + c);
            }
        }

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

        // ==== ДИАГНОСТИКА ДЛЯ АНАЛИЗА ПРОМАХОВ ====
        long nw = System.currentTimeMillis();
        boolean clamped = dyaw != rawDyaw || dpitch != rawDpitch;
        // строка состояния раз в 100мс (не каждый тик — их ~1000/с)
        if (aimDebug && nw - lastStateLog >= 100L) {
            lastStateLog = nw;
            Log.info("AimBot", "aim: " + nameOf(best) + "#" + id
                + " d=" + f1(distH)
                + " ammo=" + ammoType + " ping=" + pingMs
                + " snapAge=" + (nw - newest.t) + "мс dt=" + f0(dtSec * 1000.0) + "мс"
                + " vel=(" + f2(velX) + "," + f2(velY) + "," + f2(velZ) + ")"
                + " k=" + f2(kTicks) + " c=" + f2(cLast) + " drop=" + f2(drop)
                + " pred=(" + f1(px) + "," + f1(py) + "," + f1(pz) + ")"
                + " real=(" + f1(best.IlIiillIII()) + "," + f1(best.liiiIllIII())
                    + "," + f1(best.lIilillIII()) + ")"
                + " yaw " + f1(curYaw) + "->" + f1(targetYaw)
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
                + " k=" + f2(kTicks) + " c=" + f2(cLast) + " drop=" + f2(drop)
                + " pred=(" + f1(px) + "," + f1(py) + "," + f1(pz) + ")"
                + " real=(" + f1(best.IlIiillIII()) + "," + f1(best.liiiIllIII())
                    + "," + f1(best.lIilillIII()) + ")"
                + " yaw=" + f1(newYaw) + " pitch=" + f1(newPitch));
        }
        lastLmb = lmb;
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

    /** Ray-trace видимость: глаза → голова цели. Fail-open (резолв не удался = видно). */
    private boolean isVisible(GameContext ctx, double x0, double y0, double z0,
                              double x1, double y1, double z1) {
        try {
            if (ctx.world == null) return true;
            Class vecCls = ctx.gameLoader.loadClass("rustme.lIllIilIiI");
            Class vecBufCls = ctx.gameLoader.loadClass("rustme.lIiIlilIiI");
            Object vec = vecBufCls.getMethod("iIIIIlillI", Double.TYPE, Double.TYPE, Double.TYPE)
                .invoke(null, Double.valueOf(x1), Double.valueOf(y1), Double.valueOf(z1));
            // world.ilIlillllI(vec) → RayTraceResult; null = чисто
            Method rt = ctx.worldClass.getMethod("ilIlillllI", vecCls);
            Object hit = rt.invoke(ctx.world, vec);
            return hit == null;
        } catch (Throwable t) {
            return true; // fail-open
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

            // радиус круга: aimFov/180 * (высота экрана/2) — конус в градусах -> экран
            float r = INSTANCE.aimFov() / 180f * (scaledH * 0.5f);
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
