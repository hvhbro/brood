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
 * Saturation — насыщенность картинки (фулскрин color-grade).
 *
 * Как: в overlay-фазе (CheatIngame, мир уже отрисован, HUD ещё нет) копируем
 * бэкбуфер в свою текстуру (glCopyTexSubImage2D — паттерн GlassGrab, без FBO)
 * и рисуем поверх фулскрин-квад с SATURATE_FSH: mix(luma, rgb, saturation).
 * 0 = ч/б, 1 = без изменений (модуль ничего не делает), 2 = vivid.
 * HUD (ванильный + наш) рисуется ПОСЛЕ — не тонируется.
 *
 * Состояние: сохраняем/возвращаем blend/depth/depthmask/texture/program/
 * матрицы (как CustomSky/Ambience); depth-ENABLE не трогаем вообще
 * (кэш GlStateManager, см. GlassGrab). Всё в try/catch с троттлинг-логом —
 * при любой ошибке кадр остаётся как был.
 */
public final class Saturation extends Module {

    private static Saturation INSTANCE;

    /** Насыщенность: 0 ч/б … 1 норма … 2 vivid. */
    public final Module.FloatSetting stSat = addSetting("Saturation", 0f, 2f, 0.05f, 1f);

    private static ShaderUtil gradeShader;
    private static boolean shaderTried;
    private static int copyTex;
    private static int copyW, copyH;
    private static Method mActiveTexture;
    private static long lastErr;

    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_ACTIVE_TEXTURE = 0x84E0;

    public Saturation() {
        super("Saturation", "Visuals");
        INSTANCE = this;
        Log.info("Saturation", "registered");
    }

    /** Нужен ли грейд (для CheatIngame). */
    public static boolean wants() {
        Saturation i = INSTANCE;
        try {
            return i != null && i.isState() && i.stSat.value != 1f;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Фулскрин-грейд мира. Вызывается из CheatIngame после GlassGrab.grab
     * (бэкбуфер = готовый мир, HUD ещё нет), главный поток, GL-контекст текущий.
     */
    public static void grade(GameContext ctx) {
        Saturation inst = INSTANCE;
        if (inst == null || !inst.isState()) return;
        float sat;
        try {
            sat = inst.stSat.value;
        } catch (Throwable ignore) {
            return;
        }
        if (sat == 1f) return; // точный no-op: ни копии, ни прохода
        if (sat < 0f) sat = 0f;
        if (sat > 2f) sat = 2f;
        try {
            if (!ensureShader()) return;

            int prevProgram = GL11.glGetInteger(0x8B8D); // GL_CURRENT_PROGRAM
            int prevBlend = GL11.glGetInteger(0x0BE2);   // GL_BLEND
            int prevDepthTest = GL11.glGetInteger(0x0B71); // GL_DEPTH_TEST
            int prevDepthMask = GL11.glGetInteger(0x0B72 /*GL_DEPTH_WRITEMASK*/);

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

            if (copyTex == 0 || srcW != copyW || srcH != copyH) {
                if (copyTex != 0) {
                    try {
                        GL11.glDeleteTextures(copyTex);
                    } catch (Throwable ignore) {}
                    copyTex = 0;
                }
                copyTex = newTex(srcW, srcH);
                copyW = srcW;
                copyH = srcH;
            }

            ensureActiveTexture0();
            // копия бэкбуфера (текущий FBO игры — источник, не трогаем биндинг)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTex);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, vpX, vpY, srcW, srcH);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            // своя ортопроекция под размер вьюпорта (как GlassGrab blur-проходы)
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
                ensureActiveTexture0();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTex);
                gradeShader.start();
                gradeShader.uniformI("tex", 0);
                gradeShader.uniform2F("texSize", (float) srcW, (float) srcH);
                gradeShader.uniform2F("texOffset", (float) vpX, (float) vpY);
                gradeShader.uniformF("saturation", sat);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glColor4f(1, 1, 1, 1);
                GL11.glVertex2f(0, 0);
                GL11.glVertex2f(0, (float) srcH);
                GL11.glVertex2f((float) srcW, (float) srcH);
                GL11.glVertex2f((float) srcW, 0);
                GL11.glEnd();
                gradeShader.stop();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            } finally {
                GL11.glMatrixMode(utils.render.RenderUtil.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(utils.render.RenderUtil.GL_MODELVIEW);
                GL11.glPopMatrix();
                // restore точь-в-точь (кэш игры); depth-ENABLE не меняем вообще
                if (prevBlend != 0) GL11.glEnable(GL11.GL_BLEND);
                else GL11.glDisable(GL11.GL_BLEND);
                if (prevDepthTest != 0) GL11.glEnable(GL11.GL_DEPTH_TEST);
                else GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(prevDepthMask != 0);
                GL11.glColor4f(1f, 1f, 1f, 1f);
                GL20.glUseProgram(prevProgram);
            }
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastErr > 5000L) {
                lastErr = now;
                Log.error("Saturation", "grade failed", t);
            }
        }
    }

    private static boolean ensureShader() {
        if (gradeShader != null) return gradeShader.programId() != 0;
        if (shaderTried) return false;
        shaderTried = true;
        try {
            gradeShader = new ShaderUtil(Shaders.VERT, Shaders.SATURATE_FSH);
            if (gradeShader.programId() == 0) {
                gradeShader = null;
                Log.error("Saturation", "shader compile failed", null);
                return false;
            }
            Log.info("Saturation", "grade shader OK");
            return true;
        } catch (Throwable t) {
            gradeShader = null;
            Log.error("Saturation", "shader init failed", t);
            return false;
        }
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

    private static void ensureActiveTexture0() {
        try {
            int cur = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
            if (cur == GL_TEXTURE0) return;
            if (mActiveTexture == null) {
                mActiveTexture = Class.forName("org.lwjgl.opengl.GL13")
                    .getMethod("glActiveTexture", int.class);
            }
            mActiveTexture.invoke(null, Integer.valueOf(GL_TEXTURE0));
        } catch (Throwable ignore) {}
    }
}
