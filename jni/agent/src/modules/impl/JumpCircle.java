package modules.impl;

import org.lwjglx.opengl.GL11;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import rustme.IIlIIliIiI;
import rustme.lliIilliiI;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.GuiScale;
import utils.render.JumpDistort;

import java.nio.FloatBuffer;

/**
 * JumpCircle — расширяющийся круг под ногами при прыжке (порт kimiko).
 *
 * ДЕТЕКТ ПРЫЖКА: (spacePressed && onGround) || (!onGround && prevOnGround && vy>0.02).
 * РЕНДЕР: overlay-фаза (CheatHud.renderFrame → render), 3D-круг на земле
 * через проекцию Esp (projectToScreen — та же камера/PROJ). Двусторонний:
 * верхняя грань + нижняя с обратным winding.
 *
 * ВИЗУАЛ kimiko: расширяющийся тор (innerRadius → currentRadius) с градиентом
 * по периметру (radial lerp между 2 цветами), ease-out, альфа затухает к концу.
 * Цвет: радуга (HSL вращение 360° за период) или фиксированный градиент.
 *
 * Настройки kimiko 1:1: Основное (Время/Размер/Анимация), Искажение
 * (Турбулентность+сила), Свечение (4 параметра), Подкраска, Цвет
 * (Радуга/Клиент/Свой) — сепараторы + чекбоксы + режимы + свотчи.
 */
public final class JumpCircle extends Module {

    private static JumpCircle INSTANCE;

    // ==== Настройки kimiko 1:1 (сепараторы + булы + режимы + цвета) ====
    private final Module.SectionSetting sepGeneral = addSection("Основное");
    private final Module.FloatSetting stMaxTime = addSetting("Время", 500f, 5000f, 50f, 1500f);
    private final Module.FloatSetting stRange = addSetting("Размер", 0.5f, 5f, 0.1f, 3f);
    private final Module.ModeSetting stAnim = addMode("Анимация",
        new String[]{"Обычная", "Эластичная", "Назад"}, 0);
    private final Module.SectionSetting sepDistort = addSection("Искажение");
    private final Module.FloatSetting stDistort = addSetting("Сила искажения", 0.2f, 3f, 0.1f, 1f);
    private final Module.FloatSetting stThickness = addSetting("Толщина", 0.3f, 0.8f, 0.05f, 0.5f);
    private final Module.FloatSetting stSaturation = addSetting("Насыщенность", -1f, 1f, 0.05f, 0f);
    private final Module.BoolSetting stWarp = addBool("Турбулентность", false);
    private final Module.FloatSetting stWarpStrength = addSetting("Сила турбулентности", 0.1f, 2f, 0.1f, 0.5f)
        .visibleWhen(stWarp, true);
    private final Module.SectionSetting sepGlow = addSection("Свечение");
    private final Module.BoolSetting stGlow = addBool("Свечение", false);
    private final Module.FloatSetting stGlowHeight = addSetting("Высота свечения", 15f, 45f, 1f, 22f)
        .visibleWhen(stGlow, true);
    private final Module.FloatSetting stGlowWidth = addSetting("Ширина свечения", 10f, 30f, 1f, 18f)
        .visibleWhen(stGlow, true);
    private final Module.FloatSetting stGlowTint = addSetting("Сила цвета свечения", 30f, 100f, 1f, 70f)
        .visibleWhen(stGlow, true);
    private final Module.FloatSetting stGlowAlpha = addSetting("Прозрачность свечения", 20f, 80f, 1f, 60f)
        .visibleWhen(stGlow, true);
    private final Module.SectionSetting sepTint = addSection("Подкраска");
    private final Module.BoolSetting stTint = addBool("Подкрашивать цветом", false);
    private final Module.FloatSetting stTintStrength = addSetting("Сила цвета", 0f, 100f, 1f, 55f)
        .visibleWhen(stTint, true);
    private final Module.SectionSetting sepColor = addSection("Цвет");
    private final Module.ModeSetting stColorMode = addMode("Режим цвета",
        new String[]{"Радуга", "Клиент", "Свой"}, 0);
    private final Module.BoolSetting stUseSecond = addBool("Второй цвет", false)
        .visibleWhen(stColorMode, 2);
    private final Module.ColorSetting stCustomColor = addColor("Цвет", 0xFFFFFFFF)
        .visibleWhen(stColorMode, 2);
    private final Module.ColorSetting stCustomSecond = addColor("Цвет 2", 0xFF575757)
        .visibleWhen(stColorMode, 2)
        .visibleWhen(stUseSecond, true);

    // «Клиент»-градиент: палитра нашего клиента (ArrayList #906BFF -> #5A4BFF)
    private static final int CLIENT_FIRST = 0xFF906BFF;
    private static final int CLIENT_SECOND = 0xFF5A4BFF;

    private final java.util.ArrayList<Circle> circles = new java.util.ArrayList<Circle>();
    private long lastDiag;
    private boolean prevOnGround = true;
    private double prevY;
    private long lastSpawn;
    private Object jumpKeyBinding;   // KeyBinding прыжка (named getKeyJump)
    private boolean jumpKeyResolved;

    public JumpCircle() {
        super("JumpCircle", "Visuals");
        INSTANCE = this;
        setState(true);  // старт ON
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (isState()) onTick(event);
                } catch (Throwable t) {
                    Log.error("JumpCircle", "onTick exception", t);
                }
            }
        });
        Log.info("JumpCircle", "registered");
    }

    @Override
    protected void onDisable() {
        circles.clear();
    }

    @Override
    public void onTick(TickEvent event) {
        GameContext ctx = GameContext.get();
        if (!event.isInWorld() || ctx.player == null) {
            circles.clear();
            return;
        }
        long nd = System.currentTimeMillis();
        if (nd - lastDiag > 5000L) {
            lastDiag = nd;
            Log.info("JumpCircle", "tick: inWorld=" + event.isInWorld()
                + " circles=" + circles.size() + " onGround=" + prevOnGround);
        }
        try {
            IIlIIliIiI p = (IIlIIliIiI) ctx.player;
            boolean onGround = isOnGround(ctx, p);
            double curY = p.liiiIllIII();

            // Детект (kimiko 1:1): (spacePressed && onGround) || upwardJump.
            // ГЛАВНАЯ ветка — бинд прыжка на земле: круги каждые ~150мс ПОД
            // НОГАМИ стоящего игрока (видно при взгляде вниз). Прежний порт
            // брал только отрыв — кольцо оставалось под улетающим игроком и
            // целиком уходило за экран (drawn=23, но все точки ниже экрана).
            boolean jump = false;
            if (onGround && jumpPressed(ctx)) {
                jump = true;
            } else if (!onGround && prevOnGround) {
                double vy = ctx.motionYField != null ? ctx.motionYField.getDouble(p) : 0;
                if (vy > 0.02 || curY > prevY + 0.02) jump = true;
            }
            if (jump) addCircle(p.IlIiillIII(), p.liiiIllIII(), p.lIilillIII());
            prevOnGround = onGround;
            prevY = curY;
        } catch (Throwable t) {
            // ignore
        }
    }

    // ==== Волна искажения (kimiko onAfterWorld 1:1) — данные для JumpDistort ====
    private static final float[] waveData = new float[JumpDistort.MAX_CIRCLES * 2 * 4];
    private static final float[] waveColors = new float[JumpDistort.MAX_CIRCLES * 4 * 4];

    /** Захват мира + волна искажения. CheatIngame зовёт ДО super.iliIiiIliI —
     *  в бэкбуфере ещё чистый мир без HUD (порядок kimiko: мир → post → HUD).
     *  Формулы их модуля: footprint=range*0.75, ringWidth=max(0.15,fp*th/2),
     *  amp=0.018*strength*env, env=fadeIn(delta/0.1)*fadeOut((1-delta)/0.3),
     *  ringRadius=eased*footprint, цвета 4 по углам с фазой 45*(1-delta) на круг. */
    public static void preOverlay(GameContext ctx, float partialTicks) {
        JumpCircle inst = INSTANCE;
        if (inst == null || !inst.isState() || inst.circles.isEmpty()) return;
        if (!ctx.inWorld || ctx.player == null) return;
        try {
            IIlIIliIiI me = (IIlIIliIiI) ctx.player;
            if (me == null) return;
            // камера (та же интерполяция, что Esp.updateCamera) + глаза
            double lx = me.IlIiillIII(), ly = me.liiiIllIII(), lz = me.lIilillIII();
            double lpx = me.IiilillIII(), lpy = me.lliilIlIII(), lpz = me.lilllIlIII();
            boolean tp = Math.abs(lx - lpx) > 8.0 || Math.abs(ly - lpy) > 8.0 || Math.abs(lz - lpz) > 8.0;
            double camX = tp ? lx : lpx + (lx - lpx) * partialTicks;
            double camY = (tp ? ly : lpy + (ly - lpy) * partialTicks) + me.iliilIiilI();
            double camZ = tp ? lz : lpz + (lz - lpz) * partialTicks;
            float yaw = me.IIiIillIII(), pitch = me.iilIIIlIII();
            // третье лицо: позиция камеры со смещением+клипом, front — разворот
            int tpv = Esp.thirdPersonView();
            float effYaw = yaw, effPitch = pitch;
            if (tpv == 2) {
                effYaw = yaw + 180f;
                effPitch = -pitch;
            }
            if (tpv != 0) {
                double[] tpCam = Esp.thirdPersonCam(ctx, camX, camY, camZ, yaw, pitch, tpv);
                camX = tpCam[0];
                camY = tpCam[1];
                camZ = tpCam[2];
            }
            double yr = Math.toRadians(effYaw), pr = Math.toRadians(effPitch);
            // базисы камеры: F — взгляд (MC: pitch+ = вниз), R = cross(F, up), U = cross(R, F)
            float fx = (float) (-Math.sin(yr) * Math.cos(pr));
            float fy = (float) (-Math.sin(pr));
            float fz = (float) (Math.cos(yr) * Math.cos(pr));
            float rx = (float) (-Math.cos(yr));
            float ry = 0f;
            float rz = (float) (-Math.sin(yr));
            float ux = ry * fz - rz * fy;
            float uy = rz * fx - rx * fz;
            float uz = rx * fy - ry * fx;
            // tan(fovY/2) реальной проекции игры: pF = m[1][1] захваченной PROJ
            float tanF = 1f; // fallback fov 90 (форк рендерит 90 — проверено ESP)
            try {
                FloatBuffer proj = lliIilliiI.lIIlIlIl;
                if (proj != null && proj.capacity() >= 16) {
                    float m11 = proj.get(5);
                    if (Math.abs(m11) > 0.2f && Math.abs(m11) < 5f) tanF = 1f / m11;
                }
            } catch (Throwable ignore) {}

            float maxLife = Math.max(1f, inst.stMaxTime.value);
            float footprint = inst.stRange.value * 0.75f;
            float ringWidth = Math.max(0.15f, footprint * inst.stThickness.value / 2f);
            float ampBase = 0.018f * inst.stDistort.value;
            long now = System.currentTimeMillis();
            int scale = Math.max(1, Math.round(GuiScale.get(ctx)));
            float aspect = (float) Math.max(64, ctx.scaledWidth * scale) / (float) Math.max(64, ctx.fbHeight);
            float time = (now % 100000L) / 1000f;
            float hueOffset = 0f;
            int count = 0;
            for (int ci = inst.circles.size() - 1; ci >= 0 && count < JumpDistort.MAX_CIRCLES; ci--) {
                Circle c = inst.circles.get(ci);
                float delta = (float) (now - c.spawnTime) / maxLife;
                if (delta >= 1f || delta < 0f) continue;
                float eased = inst.getEasing(delta);
                float env = clamp01(delta / 0.1f) * clamp01((1f - delta) / 0.3f);
                int o = count * 8;
                waveData[o]     = (float) (c.x - camX);
                waveData[o + 1] = (float) (c.y - camY);
                waveData[o + 2] = (float) (c.z - camZ);
                waveData[o + 3] = eased * footprint;
                waveData[o + 4] = ringWidth;
                waveData[o + 5] = ampBase * env;
                waveData[o + 6] = footprint;
                waveData[o + 7] = env;
                int base = count * 16;
                for (int q = 0; q < 4; q++) {
                    int col = inst.getColor((int) hueOffset + q * 90, 1f);
                    int co = base + q * 4;
                    waveColors[co]     = ((col >> 16) & 0xFF) / 255f;
                    waveColors[co + 1] = ((col >> 8) & 0xFF) / 255f;
                    waveColors[co + 2] = (col & 0xFF) / 255f;
                    waveColors[co + 3] = 1f;
                }
                hueOffset += 45f * (1f - delta);
                count++;
            }
            if (count == 0) return;

            float sat = 1f + inst.stSaturation.value;
            if (sat < 0f) sat = 0f;
            if (sat > 2f) sat = 2f;
            float gh = inst.stGlowHeight.value / 100f;
            if (gh < 0.05f) gh = 0.05f;
            if (gh > 5f) gh = 5f;
            float gw = inst.stGlowWidth.value / 100f;
            if (gw < 0.05f) gw = 0.05f;
            if (gw > 5f) gw = 5f;
            JumpDistort.apply(ctx, waveData, waveColors, count,
                fx, fy, fz, rx, ry, rz, ux, uy, uz, tanF, aspect, time,
                inst.stWarp.get() ? 0.01f * inst.stWarpStrength.value : 0f,
                inst.stTint.get() ? inst.stTintStrength.value / 100f : 0f,
                sat,
                inst.stGlow.get() ? 1f : 0f,
                0.6f,
                gh, gw,
                clamp01(inst.stGlowTint.value / 100f),
                clamp01(inst.stGlowAlpha.value / 100f));
        } catch (Throwable t) {
            // волна — чистая визуалка: любая ошибка не должна ронять overlay
        }
    }

    /** Нажат ли прыжковый бинд мода (KeyBindingsCategory.getKeyJump → pressed). */    private boolean jumpPressed(GameContext ctx) {
        if (!jumpKeyResolved) {
            if (!ctx.resolveSprintKey()) return false;
            jumpKeyBinding = ctx.keyBindingFor("getKeyJump");
            if (jumpKeyBinding != null) {
                jumpKeyResolved = true;
                Log.info("JumpCircle", "jump keybind resolved (getKeyJump)");
            } else {
                // диагностика один раз: почему резолв не удался
                if (!jumpKeyFailLogged) {
                    jumpKeyFailLogged = true;
                    Log.info("JumpCircle", "getKeyJump FAILED: kbCat=" + (ctx.keyBindingsCategory != null)
                        + " nodeGetValue=" + (ctx.nodeGetValue != null));
                }
            }
        }
        if (jumpKeyBinding == null || ctx.kbPressedField == null) return false;
        try {
            return ctx.kbPressedField.getBoolean(jumpKeyBinding);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean jumpKeyFailLogged;

    private void addCircle(double x, double y, double z) {
        long now = System.currentTimeMillis();
        if (now - lastSpawn < 150L) return;  // анти-спам
        lastSpawn = now;
        circles.add(new Circle(x, y + 0.05, z));
        if (circles.size() > 8) circles.remove(0);
        Log.info("JumpCircle", "circle spawned #" + circles.size()
            + " at (" + (int)x + "," + (int)y + "," + (int)z + ")");
    }

    /** Рендер. Вызывается из CheatHud.renderFrame после Esp.render. */
    private static final float[] PROJ_OUT = new float[2];
    private static final double NEAR_W = 0.2;   // вершины с cw<=NEAR_W вырожденно растянуты клампом
    private static int drawn;                    // сегментов ушло в GL (диаг)
    private static int skipped;                  // сегментов срезано near-clipом (диаг)
    private static long lastProjDiag;

    public static void render(GameContext ctx, float partialTicks, int scaledW, int scaledH) {
        try {
            if (INSTANCE == null || !INSTANCE.isState() || INSTANCE.circles.isEmpty()) return;
            if (!ctx.inWorld || ctx.player == null) return;

            Esp.updateCamera(ctx, partialTicks);
            if (!Esp.cameraReady()) return;

            float maxLife = INSTANCE.stMaxTime.value;
            float maxRadius = INSTANCE.stRange.value;
            long now = System.currentTimeMillis();

            boolean blendWas = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depthWas = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean texWas = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);

            GL11.glEnable(GL11.GL_BLEND);
            // kimiko рисует кольцо через RenderLayers.lightning() — это АДДИТИВНЫЙ
            // блендинг (SRC_ALPHA, ONE): кольцо «светится» неоном, ДОБАВЛЯЯСЬ к
            // фону, а не ложится поверх (обычный alpha-блендинг даёт плоскую
            // пастельную радугу — та самая «не та»).
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);

            // НЕЙТРАЛИЗАЦИЯ состояния мирового рендера: anything, что может
            // съесть цветные квады (lighting → чёрный цвет, cull → отсев
            // winding'а, alpha-test → discard, fog/color-material → подмена).
            // Константы литералами GL-стандарта (в стабах их нет, как в RenderUtil)
            GL11.glDisable(0x0BA0); // GL_LIGHTING
            GL11.glDisable(0x0B44); // GL_CULL_FACE
            GL11.glDisable(0x0BC0); // GL_ALPHA_TEST
            GL11.glDisable(0x0B57); // GL_COLOR_MATERIAL
            GL11.glDisable(0x0B60); // GL_FOG
            org.lwjgl.opengl.GL20.glUseProgram(0);

            GL11.glBegin(GL11.GL_QUADS);

            int segments = 64; // kimiko: 64 — плавный градиент без изломов
            drawn = 0;
            skipped = 0;

            for (int ci = INSTANCE.circles.size() - 1; ci >= 0; ci--) {
                Circle circle = INSTANCE.circles.get(ci);
                float delta = (float) (now - circle.spawnTime) / maxLife;
                if (delta >= 1.0f) {
                    INSTANCE.circles.remove(ci);
                    continue;
                }

                // easing: kimiko Обычная / Эластичная / Назад
                float eased = INSTANCE.getEasing(delta);
                float curR = Math.max(0.05f, eased * maxRadius);
                float innerR = Math.max(0f, curR - 0.35f * (1f - delta * 0.5f));
                // альфа: fadeIn при старте, fadeOut к концу
                float alpha = (1f - delta) * (delta < 0.1f ? delta / 0.1f : 1f);
                if (alpha <= 0.01f) continue;

                for (int s = 0; s < segments; s++) {
                    float a1 = (float) s / segments;
                    float a2 = (float) (s + 1) / segments;
                    double rad1 = a1 * Math.PI * 2;
                    double rad2 = a2 * Math.PI * 2;

                    int col1 = INSTANCE.getColor((int) (a1 * 360), alpha);
                    int col2 = INSTANCE.getColor((int) (a2 * 360), alpha);
                    int icol1 = multAlpha(col1, 0.15f);
                    int icol2 = multAlpha(col2, 0.15f);

                    float x1i = (float) (circle.x + Math.cos(rad1) * innerR);
                    float z1i = (float) (circle.z + Math.sin(rad1) * innerR);
                    float x1o = (float) (circle.x + Math.cos(rad1) * curR);
                    float z1o = (float) (circle.z + Math.sin(rad1) * curR);
                    float x2o = (float) (circle.x + Math.cos(rad2) * curR);
                    float z2o = (float) (circle.z + Math.sin(rad2) * curR);
                    float x2i = (float) (circle.x + Math.cos(rad2) * innerR);
                    float z2i = (float) (circle.z + Math.sin(rad2) * innerR);

                    // Проецируем 4 точки на экран; сегмент целиком за камерой — скип
                    double w = Esp.projectToScreen((double) x1i, circle.y, (double) z1i, scaledW, scaledH, PROJ_OUT);
                    if (w <= NEAR_W || Esp.lastW() <= NEAR_W) {
                        // около-плоскость камеры: кламп превращает вершину в дальний
                        // конец экрана → вырожденный растянутый квад (мусор). Скип.
                        skipped++;
                        continue;
                    }
                    float w2 = (float) Esp.lastW();
                    float px1i = PROJ_OUT[0], py1i = PROJ_OUT[1];
                    Esp.projectToScreen((double) x1o, circle.y, (double) z1o, scaledW, scaledH, PROJ_OUT);
                    if (Esp.lastW() <= NEAR_W) { skipped++; continue; }
                    float px1o = PROJ_OUT[0], py1o = PROJ_OUT[1];
                    Esp.projectToScreen((double) x2o, circle.y, (double) z2o, scaledW, scaledH, PROJ_OUT);
                    if (Esp.lastW() <= NEAR_W) { skipped++; continue; }
                    float px2o = PROJ_OUT[0], py2o = PROJ_OUT[1];
                    Esp.projectToScreen((double) x2i, circle.y, (double) z2i, scaledW, scaledH, PROJ_OUT);
                    if (Esp.lastW() <= NEAR_W) { skipped++; continue; }
                    float px2i = PROJ_OUT[0], py2i = PROJ_OUT[1];
                    drawn++;

                    // Кольцо: quad внутренний→внешний. Цвета вершин kimiko 1:1:
                    // inner1=×0.15 альфы, outer1=полный, outer2=полный, inner2=×0.15.
                    GL11.glColor4f(
                        ((icol1 >> 16) & 0xFF) / 255f, ((icol1 >> 8) & 0xFF) / 255f,
                        (icol1 & 0xFF) / 255f, ((icol1 >> 24) & 0xFF) / 255f);
                    GL11.glVertex2f(px1i, py1i);
                    GL11.glColor4f(
                        ((col1 >> 16) & 0xFF) / 255f, ((col1 >> 8) & 0xFF) / 255f,
                        (col1 & 0xFF) / 255f, ((col1 >> 24) & 0xFF) / 255f);
                    GL11.glVertex2f(px1o, py1o);
                    GL11.glColor4f(
                        ((col2 >> 16) & 0xFF) / 255f, ((col2 >> 8) & 0xFF) / 255f,
                        (col2 & 0xFF) / 255f, ((col2 >> 24) & 0xFF) / 255f);
                    GL11.glVertex2f(px2o, py2o);
                    GL11.glColor4f(
                        ((icol2 >> 16) & 0xFF) / 255f, ((icol2 >> 8) & 0xFF) / 255f,
                        (icol2 & 0xFF) / 255f, ((icol2 >> 24) & 0xFF) / 255f);
                    GL11.glVertex2f(px2i, py2i);
                }
            }

            GL11.glEnd();
            GL11.glColor4f(1f, 1f, 1f, 1f);
            // вернуть обычный блендинг — дальше в кадре рисуются Esp/HUD
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            if (depthWas) GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (!blendWas) GL11.glDisable(GL11.GL_BLEND);
            // диаг: раз в 300мс — сколько сегментов реально ушло в GL
            long nd = System.currentTimeMillis();
            if (nd - lastProjDiag > 300L && drawn + skipped > 0) {
                boolean first = lastProjDiag == 0;
                lastProjDiag = nd;
                Log.info("JumpCircle", "drawn=" + drawn + " skipped=" + skipped
                    + " circles=" + INSTANCE.circles.size());
                if (first) dumpGlState(scaledW, scaledH);
            }
        } catch (Throwable t) {
            Log.error("JumpCircle", "render failed", t);
        }
    }

    /** Разовый дамп GL-состояния на спавн круга (поиск пожирателя квадов). */
    private static void dumpGlState(int scaledW, int scaledH) {
        try {
            Log.info("JumpCircle", "GL: lighting=" + GL11.glIsEnabled(0x0BA0)
                + " cull=" + GL11.glIsEnabled(0x0B44)
                + " alphaTest=" + GL11.glIsEnabled(0x0BC0)
                + " scissor=" + GL11.glIsEnabled(0x0C11)
                + " fog=" + GL11.glIsEnabled(0x0B60)
                + " prog=" + GL11.glGetInteger(0x8B8D)
                + " screen=" + scaledW + "x" + scaledH);
        } catch (Throwable t) {
            Log.info("JumpCircle", "GL dump failed: " + t);
        }
    }

    /** Цвет по углу 0..360 с альфой — kimiko 1:1: Радуга ВРАЩАЕТСЯ со временем
     *  (hue = angle + ms/8), Клиент — ping-pong фейд палитры клиента,
     *  Свой — фейд двух своих цветов (второй — если включен). */
    private int getColor(int deg, float alpha) {
        int mode = stColorMode.index();
        int a255 = (int) (clamp01(alpha) * 255f);
        int hue = (int) (((deg + System.currentTimeMillis() / 8L) % 360 + 360) % 360);
        if (mode == 0) {
            return hsvToArgb(hue, 1f, 1f, a255);
        }
        int first, second;
        if (mode == 1) {
            first = CLIENT_FIRST;
            second = CLIENT_SECOND;
        } else {
            first = stCustomColor.argb;
            second = stUseSecond.get() ? stCustomSecond.argb : first;
        }
        if (first == second) {
            return (a255 << 24) | (first & 0xFFFFFF);
        }
        int ph = hue >= 180 ? 360 - hue : hue;
        float f = ph / 180f;
        int r = lerp((first >> 16) & 0xFF, (second >> 16) & 0xFF, f);
        int g = lerp((first >> 8) & 0xFF, (second >> 8) & 0xFF, f);
        int b = lerp(first & 0xFF, second & 0xFF, f);
        return (a255 << 24) | (r << 16) | (g << 8) | b;
    }

    /** easing kimiko: 0=Обычная, 1=Эластичная, 2=Назад (backOut). */
    private float getEasing(float delta) {
        float f = clamp01(delta);
        int mode = stAnim.index();
        if (mode == 1) return elasticOut(f);
        if (mode == 2) return backOut(f);
        return f;
    }

    private static float backOut(float f) {
        if (f <= 0f) return 0f;
        if (f >= 1f) return 1f;
        return (float) (1.0 + 2.70158 * Math.pow(f - 1.0, 3.0) + 1.70158 * Math.pow(f - 1.0, 2.0));
    }

    private static int hsvToArgb(int deg, float sat, float val, int alpha) {
        float c = val * sat;
        float x = c * (1 - Math.abs((deg / 60f) % 2 - 1));
        float m = val - c;
        float r = 0, g = 0, b = 0;
        switch (deg / 60) {
            case 0: r = c; g = x; break;
            case 1: r = x; g = c; break;
            case 2: g = c; b = x; break;
            case 3: g = x; b = c; break;
            case 4: r = x; b = c; break;
            default: r = c; b = x; break;
        }
        return (alpha << 24) | (((int) ((r + m) * 255)) << 16)
            | (((int) ((g + m) * 255)) << 8) | ((int) ((b + m) * 255));
    }

    private static int multAlpha(int argb, float mul) {
        int a = (int) (((argb >>> 24) & 0xFF) * clamp01(mul));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static int lerp(int a, int b, float t) {
        return a + (int) ((b - a) * clamp01(t));
    }

    private static float clamp01(float f) {
        return f < 0 ? 0 : (f > 1 ? 1 : f);
    }

    private static float elasticOut(float f) {
        if (f <= 0f || f >= 1f) return f;
        return (float) (Math.pow(2, -10f * f) * Math.sin((f * 10 - 0.75) * (Math.PI * 2 / 3)) + 1);
    }

    private static java.lang.reflect.Field onGroundField;
    private static boolean onGroundTried;
    private static boolean isOnGround(GameContext ctx, IIlIIliIiI p) {
        try {
            if (!onGroundTried && onGroundField == null) {
                onGroundTried = true;
                Class c = ctx.localSpClass;
                while (c != null && onGroundField == null) {
                    try {
                        // onGround = lliililiI:Z (доказано 09-10: гейт прыжка
                        // onLivingUpdate `lliililiI && jumpTicks==0 → jump()` и
                        // move(): onGround = collidedVertically && yDelta<0.
                        // ZNANIA 14.1 называла onGround=iiIIIiliI — неверно,
                        // из-за этого модуль «стоял» с onGround=false и не спавнил).
                        onGroundField = c.getDeclaredField("lliililiI");
                        onGroundField.setAccessible(true);
                    } catch (Throwable ig) { c = c.getSuperclass(); }
                }
                if (onGroundField == null) Log.info("JumpCircle", "onGround field NOT found");
            }
            if (onGroundField != null) return onGroundField.getBoolean(p);
        } catch (Throwable ig) {}
        return false; // не знаем — не спавним (true давало фантомный спавн)
    }

    private static final class Circle {
        final double x, y, z;
        final long spawnTime;
        Circle(double x, double y, double z) {
            this.x = x; this.y = y; this.z = z;
            this.spawnTime = System.currentTimeMillis();
        }
    }
}
