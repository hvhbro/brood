package modules.impl;

import modules.api.Module;
import org.lwjglx.opengl.GL11;
import org.lwjgl.opengl.GL20;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.GuiScale;
import utils.render.ShaderUtil;
import utils.render.Shaders;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;

/**
 * Custom Sky — звёздное небо (1:1 порт референса skyshader/ — light client,
 * режим Starry Sky: 3 гномонических звёздных чарта с мерцанием, Млечный Путь,
 * процедурная луна с кратерами).
 *
 * КАК РИСУЕМ (замена, БЕЗ пиксельных эвристик): CheatRenderGlobal (прокси
 * RenderGlobal в gs.Iillllil) перехватывает IilIIIliII(FI)V — метод неба,
 * который мировой проход зовёт ПЕРВЫМ (mc.llilllIiII @360, проверено
 * дизасмом). Рисуем чистый процедурный SKY_FSH (opaque, depth off) ВМЕСТО
 * ванильного неба (super не зовётся); террейн идёт после и сам перекрывает
 * небо там, где нужно. Никаких uScene/uBlur/tol — шейдеру нужны только
 * resolution/time/цвета/params/cameraData/starryParams.
 *
 * Шейдер: 1:1 порт skyshader_starry.fsh на GLSL 130 (fwidth/dFdx доступны —
 * их же юзает их ui_batch); UBO -> плоские uniform'ы, out -> gl_FragColor.
 * FOV читаем из статик PROJ-буфера lliIilliiI.lIIlIlIl (сигнатура перспективы
 * как в Esp), fallback 90. yaw/pitch — геттеры Entity IIiIillIII/iilIIIlIII
 * (XOR-самодекод), референс ждёт отрицательный yaw.
 */
public final class CustomSky extends Module {

    private static CustomSky INSTANCE;

    // ===== настройки (референс SkyShader / Starry Sky) =====
    public final Module.FloatSetting stDensity = addSetting("Плотность звёзд", 0.25f, 2f, 0.05f, 1f);
    public final Module.FloatSetting stBright = addSetting("Яркость звёзд", 0.2f, 2.5f, 0.1f, 1f);
    public final Module.FloatSetting stMilky = addSetting("Млечный Путь", 0f, 1.5f, 0.05f, 0.65f);
    public final Module.FloatSetting stSpeed = addSetting("Скорость", 0.1f, 5f, 0.1f, 1f);
    public final Module.FloatSetting stSize = addSetting("Размер звёзд", 1f, 20f, 0.5f, 5f);
    public final Module.FloatSetting stExposure = addSetting("Экспозиция", 0.001f, 0.05f, 0.001f, 0.01f);
    public final Module.FloatSetting stSkyBright = addSetting("Яркость неба", 0.3f, 1f, 0.05f, 1f);
    public final Module.BoolSetting stMoon = addBool("Луна", true);
    public final Module.ColorSetting stColor1 = addColor("Цвет 1", 0xFF4168B0);
    public final Module.ColorSetting stColor2 = addColor("Цвет 2", 0xFF8973BA);

    // ===== GL / состояние (рендер-поток читает, тик пишет только настройки) =====
    private ShaderUtil program;
    private boolean shaderFailed;
    private long startMillis = -1L;
    private long lastErr;
    private long lastAudit;

    // reflection-кэш
    private Field projField;   // lliIilliiI.lIIlIlIl (PROJ, FloatBuffer 16, static)
    private boolean projTried;
    private Method yawM;       // Entity.IIiIillIII()F
    private Method pitchM;     // Entity.iilIIIlIII()F
    private long lastGlErr;

    public CustomSky() {
        super("Custom Sky", "Visuals");
        INSTANCE = this;
        stColor1.picker = true;
        stColor2.picker = true;
        Log.info("CustomSky", "registered");
    }

    /** Нужен ли хук (для CheatRenderGlobal.sync). */
    public static boolean wants() {
        CustomSky i = INSTANCE;
        return i != null && i.isState();
    }

    /**
     * Рисует процедурное небо ВМЕСТО ванильного. Вызывается из прокси
     * CheatRenderGlobal.IilIIIliII на рендер-потоке (GL-контекст текущий).
     * true = нарисовано (super пропускаем), false = рисовать ванильное.
     */
    public static boolean drawSky() {
        CustomSky inst = INSTANCE;
        if (inst == null || !inst.isState()) return false;
        GameContext ctx = GameContext.get();
        if (ctx.gs == null || !ctx.inWorld || ctx.player == null) return false;
        try {
            ShaderUtil sh = inst.program();
            if (sh == null) return false; // шейдер не собрался — ванильное небо

            float pYaw = inst.playerYaw(ctx);
            float pPitch = inst.playerPitch(ctx);
            // третье лицо спереди (view 2): камера смотрит назад —
            // разворачиваем лучи как ванильный orientCamera (yaw+180, pitch→-pitch)
            try {
                if (modules.impl.Thirdperson.currentView() == 2) {
                    pYaw += 180f;
                    pPitch = -pPitch;
                }
            } catch (Throwable ignore) {}
            float yawRad = (float) Math.toRadians(-pYaw);
            float pitchRad = (float) Math.toRadians(pPitch);
            float fovDeg = inst.fovY(ctx);
            long now = System.currentTimeMillis();
            if (inst.startMillis < 0L) inst.startMillis = now;
            float time = (now - inst.startMillis) / 1000f;

            // разрешение фреймбуфера (viewport; в мировом проходе — полный кадр).
            // Шимовый glGetInteger на части драйверов врёт нулями (ср. 13.4.3) —
            // fallback: scaled × guiScale (проверенный путь RenderUtil).
            float srcW = 0f, srcH = 0f;
            try {
                java.nio.IntBuffer vpBuf = java.nio.ByteBuffer.allocateDirect(16)
                    .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
                GL11.glGetInteger(0x0BA2 /*GL_VIEWPORT*/, vpBuf);
                srcW = (float) vpBuf.get(2);
                srcH = (float) vpBuf.get(3);
            } catch (Throwable ignore) {}
            if (srcW <= 0f || srcH <= 0f) {
                try {
                    float sc = GuiScale.get(ctx);
                    if (ctx.scaledWidth > 0 && ctx.scaledHeight > 0 && sc > 0f) {
                        srcW = ctx.scaledWidth * sc;
                        srcH = ctx.scaledHeight * sc;
                    }
                } catch (Throwable ignore) {}
            }
            if (srcW <= 0f || srcH <= 0f) {
                srcW = 1280f;
                srcH = 720f;
            }

            // аудит утечек: какое состояние оставили прошлые кадры (раз в 5с)
            if (now - inst.lastAudit > 5000L) {
                inst.lastAudit = now;
                Log.info("SkyAudit", "sky entry: " + rustme.CheatRenderGlobal.glState());
            }

            int prevProgram = GL11.glGetInteger(0x8B8D); // GL_CURRENT_PROGRAM
            int prevBlend = GL11.glGetInteger(0x0BE2);   // GL_BLEND
            int prevDepthTest = GL11.glGetInteger(0x0B71); // GL_DEPTH_TEST
            int prevDepthMask = GL11.glGetInteger(0x0B72 /*GL_DEPTH_WRITEMASK*/);

            // сброс залипшего флага чужой ошибки, чтобы пост-проверка ловила
            // ТОЛЬКО наши вызовы (иначе чужой 1280 припишут небу)
            drainGlError();

            // opaque небо: бленд и тест глубины выкл, глубину не портим
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);

            boolean ok = false;
            try {
                sh.start();
                sh.uniform2F("resolution", srcW, srcH);
                sh.uniformF("time", time);
                sh.uniform3F("color1",
                    ((inst.stColor1.argb >> 16) & 0xFF) / 255f,
                    ((inst.stColor1.argb >> 8) & 0xFF) / 255f,
                    (inst.stColor1.argb & 0xFF) / 255f);
                sh.uniform3F("color2",
                    ((inst.stColor2.argb >> 16) & 0xFF) / 255f,
                    ((inst.stColor2.argb >> 8) & 0xFF) / 255f,
                    (inst.stColor2.argb & 0xFF) / 255f);
                sh.uniform4F("params", inst.stSkyBright.value, inst.stSpeed.value,
                    inst.stSize.value, inst.stExposure.value);
                sh.uniform4F("cameraData", yawRad, pitchRad, fovDeg, 0f);
                sh.uniform4F("starryParams", inst.stDensity.value, inst.stBright.value,
                    inst.stMilky.value, inst.stMoon.get() ? 1f : 0f);

                GL11.glBegin(GL11.GL_TRIANGLES);
                GL11.glVertex3f(-1f, -1f, 1f);
                GL11.glVertex3f(3f, -1f, 1f);
                GL11.glVertex3f(-1f, 3f, 1f);
                GL11.glEnd();

                sh.stop();
                ok = true;
            } finally {
                // restore ВСЕГДА (иначе любая ошибка = залипшее состояние
                // и «пропавший текст» до конца сессии)
                GL20.glUseProgram(prevProgram); // безусловно: и 0, и чужую программу
                // вернуть как было (кэш состояния игры)
                if (prevBlend != 0) GL11.glEnable(GL11.GL_BLEND);
                else GL11.glDisable(GL11.GL_BLEND);
                if (prevDepthTest != 0) GL11.glEnable(GL11.GL_DEPTH_TEST);
                else GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(prevDepthMask != 0);
                GL11.glColor4f(1f, 1f, 1f, 1f);
            }
            checkGlError(inst, "sky");
            return ok;
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - inst.lastErr > 5000L) {
                inst.lastErr = now;
                Log.error("CustomSky", "draw failed", t);
            }
            return false;
        }
    }

    /** Сброс залипших чужих GL-флагов (без лога). */
    private static void drainGlError() {
        try {
            for (int i = 0; i < 8; i++) {
                if (GL11.glGetError() == 0) break;
            }
        } catch (Throwable ignore) {}
    }

    /** Пост-проверка нашего прохода (троттлинг 5с — пинпоинт для AMD-репортов). */
    private static void checkGlError(CustomSky inst, String stage) {
        try {
            int err = GL11.glGetError();
            if (err == 0) return;
            long now = System.currentTimeMillis();
            if (now - inst.lastGlErr < 5000L) return;
            inst.lastGlErr = now;
            Log.error("CustomSky", "GL ERROR " + err + " after " + stage, null);
        } catch (Throwable ignore) {}
    }

    private ShaderUtil program() {        if (program != null || shaderFailed) return shaderFailed ? null : program;
        try {
            program = new ShaderUtil(Shaders.SKY_VERT, Shaders.SKY_FSH);
            if (program.programId() == 0) {
                program = null;
                shaderFailed = true;
                Log.error("CustomSky", "shader compile failed (fail-fast)", null);
            } else {
                Log.info("CustomSky", "procedural sky shader OK");
            }
        } catch (Throwable t) {
            shaderFailed = true;
            Log.error("CustomSky", "shader init failed", t);
        }
        return shaderFailed ? null : program;
    }

    /** Вертикальный FOV (град) из статик PROJ-буфера камеры мода. */
    private float fovY(GameContext ctx) {
        try {
            if (!projTried) {
                projTried = true;
                Class camC = ctx.gameLoader.loadClass("rustme.lliIilliiI");
                projField = camC.getField("lIIlIlIl"); // PROJ (2983), public static
            }
            if (projField != null) {
                Object o = projField.get(null);
                if (o instanceof FloatBuffer) {
                    FloatBuffer b = (FloatBuffer) o;
                    // читать ИЗ буфера (массив не обновляется — гатч 09-09)
                    float m0 = b.get(0), m5 = b.get(5), m10 = b.get(10), m15 = b.get(15);
                    // перспективная сигнатура (Esp v2): |m5|>0.5, |m15|<0.5, m10<0
                    if (Math.abs(m5) > 0.5f && Math.abs(m15) < 0.5f && m10 < 0f && m5 != 0f) {
                        float deg = (float) Math.toDegrees(2.0 * Math.atan(1.0 / m5));
                        if (deg > 10f && deg < 170f) return deg;
                    }
                }
            }
        } catch (Throwable ignore) {}
        return 90f;
    }

    private float playerYaw(GameContext ctx) {
        try {
            if (yawM == null) yawM = findMethod(ctx.player.getClass(), "IIiIillIII");
            if (yawM != null) return ((Float) yawM.invoke(ctx.player)).floatValue();
        } catch (Throwable ignore) {}
        return 0f;
    }

    private float playerPitch(GameContext ctx) {
        try {
            if (pitchM == null) pitchM = findMethod(ctx.player.getClass(), "iilIIIlIII");
            if (pitchM != null) return ((Float) pitchM.invoke(ctx.player)).floatValue();
        } catch (Throwable ignore) {}
        return 0f;
    }

    private static Method findMethod(Class c, String name) {
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ig) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
