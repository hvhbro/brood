package modules.impl;

import modules.api.Module;
import org.lwjgl.opengl.GL20;
import org.lwjglx.opengl.GL11;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.GuiScale;
import utils.render.ShaderUtil;
import utils.render.Shaders;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Motion Blur (по мотивам EvaWare: там velocity-reprojection на современном
 * пайплайне; у нас GL120/fixed pipeline — эквивалент: направленные тапы
 * вдоль вектора движения камеры + временной фидбэк предыдущего кадра).
 *
 * Как: в overlay-фазе (CheatIngame, ПОСЛЕ Saturation-грейда, ДО всего HUD)
 * копируем бэкбуфер в curTex, рисуем фулскрин-квад MOTIONBLUR_FSH
 * (9 тапов вдоль motion + mix с prv), результат копируем в prvTex для
 * следующего кадра. Вектор движения — из дельты yaw/pitch игрока
 * (пиксели через px/deg, симметричные тапы — знак не важен).
 *
 * Настройки: Strength 0..100 (доля прошлого кадра), Length 0..20px
 * (разлёт направленных тапов при повороте). Модуль выкл / Strength 0 —
 * точный no-op. Смена мира — сброс фидбэка (без шлейфа телепорта).
 *
 * Состояние GL сохраняем/возвращаем как в Saturation; depth-ENABLE не
 * трогаем (кэш GlStateManager). Всё в try/catch с троттлинг-логом.
 */
public final class MotionBlur extends Module {

    private static MotionBlur INSTANCE;

    /** Доля прошлого кадра 0..100. */
    public final Module.FloatSetting stStrength = addSetting("Strength", 0f, 100f, 1f, 35f);
    /** Разлёт направленных тапов при повороте, px. */
    public final Module.FloatSetting stLength = addSetting("Length", 0f, 20f, 0.5f, 8f);

    private static ShaderUtil blurShader;
    private static boolean shaderTried;
    private static int curTex, prvTex;
    private static int copyW, copyH;
    private static Method mActiveTexture;
    private static long lastErr;
    private static Object lastWorld;
    private static boolean needInit = true;
    // вектор камеры прошлого кадра (для дельты)
    private static float lastYaw;
    private static float lastPitch;
    private static boolean hasLastAng;
    private static Method yawM, pitchM;

    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_TEXTURE1 = 0x84C1;
    private static final int GL_ACTIVE_TEXTURE = 0x84E0;

    public MotionBlur() {
        super("Motion Blur", "Visuals");
        INSTANCE = this;
        Log.info("MotionBlur", "registered");
    }

    @Override
    protected void onEnable() {
        needInit = true;
    }

    @Override
    protected void onDisable() {
        needInit = true;
    }

    /** Нужен ли проход (для CheatIngame). */
    public static boolean wants() {
        MotionBlur i = INSTANCE;
        try {
            return i != null && i.isState() && i.stStrength.value > 0.5f;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Motion blur проход. Вызывается из CheatIngame после Saturation.grade
     * (бэкбуфер = готовый мир), главный поток, GL-контекст текущий.
     */
    public static void blur(GameContext ctx) {
        MotionBlur inst = INSTANCE;
        if (inst == null || !inst.isState()) return;
        float strength;
        float length;
        try {
            strength = inst.stStrength.value;
            length = inst.stLength.value;
        } catch (Throwable ignore) {
            return;
        }
        if (strength <= 0.5f) return;
        float keep = Math.min(0.85f, strength / 100f * 0.85f);
        if (length < 0f) length = 0f;
        if (length > 20f) length = 20f;
        try {
            if (!ensureShader()) return;

            int prevProgram = GL11.glGetInteger(0x8B8D); // GL_CURRENT_PROGRAM
            int prevBlend = GL11.glGetInteger(0x0BE2);   // GL_BLEND
            int prevDepthTest = GL11.glGetInteger(0x0B71); // GL_DEPTH_TEST
            int prevDepthMask = GL11.glGetInteger(0x0B72 /*GL_DEPTH_WRITEMASK*/);
            int prevActive = GL11.glGetInteger(GL_ACTIVE_TEXTURE);

            IntBuffer vpBuf = ByteBuffer.allocateDirect(16)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
            GL11.glGetInteger(GL11.GL_VIEWPORT, vpBuf);
            int vpX = vpBuf.get(0), vpY = vpBuf.get(1);
            int srcW = vpBuf.get(2), srcH = vpBuf.get(3);
            int scale = Math.max(1, Math.round(GuiScale.get(ctx)));
            if (srcW <= 0 || srcH <= 0) {
                srcW = Math.max(64, ctx.scaledWidth * scale);
                srcH = Math.max(64, ctx.fbHeight);
                vpX = 0;
                vpY = 0;
            }
            if (srcW <= 0 || srcH <= 0) return;

            if (curTex == 0 || prvTex == 0 || srcW != copyW || srcH != copyH) {
                deleteTex();
                curTex = newTex(srcW, srcH);
                prvTex = newTex(srcW, srcH);
                copyW = srcW;
                copyH = srcH;
                needInit = true;
            }
            if (ctx.world != lastWorld) {
                lastWorld = ctx.world;
                needInit = true;
            }

            // вектор движения камеры (пиксели за кадр) из дельты yaw/pitch
            float[] motion = camMotion(ctx, srcW, srcH);

            setActiveTexture(GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, curTex);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, vpX, vpY, srcW, srcH);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            if (needInit) {
                // первый кадр: фидбэк = текущий (без шлейфа из прошлого)
                setActiveTexture(GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prvTex);
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, vpX, vpY, srcW, srcH);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                needInit = false;
                restoreState(prevProgram, prevBlend, prevDepthTest, prevDepthMask, prevActive);
                return;
            }

            // своя ортопроекция под размер вьюпорта
            GL11.glMatrixMode(utils.render.RenderUtil.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0, srcW, srcH, 0, -1, 1);
            GL11.glMatrixMode(utils.render.RenderUtil.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();

            try {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);
                GL11.glDisable(GL11.GL_BLEND);
                setActiveTexture(GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, curTex);
                setActiveTexture(GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prvTex);
                blurShader.start();
                blurShader.uniformI("cur", 0);
                blurShader.uniformI("prv", 1);
                blurShader.uniform2F("texSize", (float) srcW, (float) srcH);
                blurShader.uniform2F("texOffset", (float) vpX, (float) vpY);
                blurShader.uniform2F("motion", motion[0], motion[1]);
                blurShader.uniformF("keep", keep);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glColor4f(1, 1, 1, 1);
                GL11.glVertex2f(0, 0);
                GL11.glVertex2f(0, (float) srcH);
                GL11.glVertex2f((float) srcW, (float) srcH);
                GL11.glVertex2f((float) srcW, 0);
                GL11.glEnd();
                blurShader.stop();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                setActiveTexture(GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                // результат — в фидбэк следующего кадра
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prvTex);
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, vpX, vpY, srcW, srcH);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            } finally {
                GL11.glMatrixMode(utils.render.RenderUtil.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(utils.render.RenderUtil.GL_MODELVIEW);
                GL11.glPopMatrix();
                restoreState(prevProgram, prevBlend, prevDepthTest, prevDepthMask, prevActive);
            }
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastErr > 5000L) {
                lastErr = now;
                Log.error("MotionBlur", "blur failed", t);
            }
        }
    }

    private static void restoreState(int prevProgram, int prevBlend, int prevDepthTest,
                                     int prevDepthMask, int prevActive) {
        try {
            if (prevBlend != 0) GL11.glEnable(GL11.GL_BLEND);
            else GL11.glDisable(GL11.GL_BLEND);
            if (prevDepthTest != 0) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(prevDepthMask != 0);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            setActiveTexture(prevActive != 0 ? prevActive : GL_TEXTURE0);
            GL20.glUseProgram(prevProgram);
        } catch (Throwable ignore) {}
    }

    /**
     * Вектор движения камеры в пикселях: дельта yaw/pitch × px/deg
     * (fovY=90 как везде — проверено ESP). Симметричные тапы, знак не важен.
     */
    private static float[] camMotion(GameContext ctx, int srcW, int srcH) {
        float[] out = new float[2];
        try {
            if (ctx.player == null) {
                hasLastAng = false;
                return out;
            }
            if (yawM == null || pitchM == null) {
                yawM = findMethod(ctx.player.getClass(), "IIiIillIII");
                pitchM = findMethod(ctx.player.getClass(), "iilIIIlIII");
                if (yawM == null || pitchM == null) return out;
            }
            float yaw = ((Float) yawM.invoke(ctx.player)).floatValue();
            float pitch = ((Float) pitchM.invoke(ctx.player)).floatValue();
            if (!hasLastAng) {
                lastYaw = yaw;
                lastPitch = pitch;
                hasLastAng = true;
                return out;
            }
            float dyaw = yaw - lastYaw;
            while (dyaw > 180f) dyaw -= 360f;
            while (dyaw < -180f) dyaw += 360f;
            float dpitch = pitch - lastPitch;
            lastYaw = yaw;
            lastPitch = pitch;
            // px/deg: горизонталь из aspect при fovY=90, вертикаль srcH/90
            float aspect = srcH > 0 ? (float) srcW / (float) srcH : 16f / 9f;
            float hfov = (float) Math.toDegrees(2.0 * Math.atan(aspect));
            if (hfov < 1f) hfov = 90f;
            float pxPerDegX = srcW / hfov;
            float pxPerDegY = srcH / 90f;
            MotionBlur inst = INSTANCE;
            float len = inst != null ? inst.stLength.value : 8f;
            if (len < 0f) len = 0f;
            if (len > 20f) len = 20f;
            // нормируем дельту на длину: направление × Length
            float mx = dyaw * pxPerDegX;
            float my = dpitch * pxPerDegY;
            float mag = (float) Math.sqrt(mx * mx + my * my);
            if (mag < 0.5f) return out;
            float k = Math.min(mag, 64f) / 64f * len;
            out[0] = mx / mag * k;
            out[1] = my / mag * k;
            return out;
        } catch (Throwable ignore) {
            return out;
        }
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

    private static boolean ensureShader() {
        if (blurShader != null) return blurShader.programId() != 0;
        if (shaderTried) return false;
        shaderTried = true;
        try {
            blurShader = new ShaderUtil(Shaders.VERT, Shaders.MOTIONBLUR_FSH);
            if (blurShader.programId() == 0) {
                blurShader = null;
                Log.error("MotionBlur", "shader compile failed", null);
                return false;
            }
            Log.info("MotionBlur", "blur shader OK");
            return true;
        } catch (Throwable t) {
            blurShader = null;
            Log.error("MotionBlur", "shader init failed", t);
            return false;
        }
    }

    private static void deleteTex() {
        try {
            if (curTex != 0) GL11.glDeleteTextures(curTex);
        } catch (Throwable ignore) {}
        try {
            if (prvTex != 0) GL11.glDeleteTextures(prvTex);
        } catch (Throwable ignore) {}
        curTex = 0;
        prvTex = 0;
    }

    private static int newTex(int w, int h) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return t;
    }

    private static void setActiveTexture(int unit) {
        try {
            if (mActiveTexture == null) {
                mActiveTexture = Class.forName("org.lwjgl.opengl.GL13")
                    .getMethod("glActiveTexture", int.class);
            }
            mActiveTexture.invoke(null, Integer.valueOf(unit));
        } catch (Throwable ignore) {}
    }
}
