package utils.render;

import org.lwjglx.opengl.GL11;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * JumpDistort — screen-space волна прыжка 1:1 kimiko (jumpdistort.fsh).
 *
 * ПАТТЕРН GlassGrab (проверен в бою): копия текущего таргета через
 * glCopyTexSubImage2D в свою текстуру → fullscreen-квад с шейдером JUMP_FSH
 * в ТОТ ЖЕ таргет (искажённый сэмпл сцены). Вызывается из CheatIngame ДО super
 * (до ванильного HUD), чтобы волна искажала МИР, а не HUD — тот же порядок,
 * что у kimiko (мир → post → HUD).
 *
 * Шейдер: для каждого пикселя луч из камеры → пересечение с плоскостью y
 * круга (камера-относительные координаты) → кольцевая волна sin(w·π)(1−|w|),
 * сдвиг UV, 4-цветный градиент по углу, сатурация/подкраска, дым-конус.
 * Окклюзии по depth НЕТ (по решению юзера 09-10 — не нужна; depth-семантика
 * форка неясна и она глушила кольца целиком).
 *
 * УРОКИ (09-10): (1) copyTex НИ К ЧЕМУ не прикреплять — аттач к игровому FBO
 * даёт feedback loop при сэмпле → «зависшая картинка»; (2) fullscreen-проход —
 * в ТЕКУЩИЙ таргет, не в FBO 0 (игра рисует оверлей в свой FBO); (3) в GLSL-120
 * запрещены битовые '&'/'|'/'^' (GL_EXT_gpu_shader4) — только mod/floor.
 * FBO-функции — через reflection (GL30 нет в classpath). Только главный поток.
 */
public final class JumpDistort {

    private static int copyTex;
    private static int curFbw, curFbh;
    private static ShaderUtil jumpShader;
    private static boolean tried;
    private static boolean ok;
    private static boolean logged;
    private static Method mBindFramebuffer, mActiveTexture, mCheckStatus;

    /** Максимум кругов в волне (kimiko держит 16, у нас кольцо ≤ 8). */
    public static final int MAX_CIRCLES = 8;

    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_TEXTURE1 = 0x84C1;
    private static final int GL_ACTIVE_TEXTURE = 0x84E0;

    private JumpDistort() {}

    private static boolean initGl30() throws Exception {
        if (mBindFramebuffer != null) return true;
        Class gl30 = Class.forName("org.lwjgl.opengl.GL30");
        mBindFramebuffer = gl30.getMethod("glBindFramebuffer", int.class, int.class);
        mCheckStatus = gl30.getMethod("glCheckFramebufferStatus", int.class);
        mActiveTexture = Class.forName("org.lwjgl.opengl.GL13").getMethod("glActiveTexture", int.class);
        return true;
    }

    private static boolean init() {
        if (tried) return ok;
        tried = true;
        try {
            initGl30();
            jumpShader = new ShaderUtil(Shaders.VERT, Shaders.JUMP_FSH);
            ok = jumpShader.programId() != 0;
            if (!ok) Log.error("JumpDistort", "shader failed", null);
            else if (!logged) {
                logged = true;
                Log.info("JumpDistort", "shader OK (jumpdistort 1:1 kimiko, no occlusion)");
            }
        } catch (Throwable t) {
            ok = false;
            Log.error("JumpDistort", "init failed", t);
        }
        return ok;
    }

    private static void bindFbo(int f) {
        try {
            mBindFramebuffer.invoke(null, GL_FRAMEBUFFER, f);
        } catch (Throwable ignore) {}
    }

    private static void ensureActiveTexture0() {
        try {
            int cur = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
            if (cur != GL_TEXTURE0) mActiveTexture.invoke(null, GL_TEXTURE0);
        } catch (Throwable ignore) {}
    }

    private static void bindTex(int t) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
    }

    private static int newTex(int w, int h) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return t;
    }

    /**
     * Применить волну. Вызывается из CheatIngame ПЕРЕД super (мир в текущем
     * таргете, HUD ещё не нарисован).
     *
     * @param data   float[16*4]: на круг — (cx,cy,cz камера-относ., ringRadius),
     *               (ringWidth, amp, footprint, env)
     * @param colors float[32*4]: на круг 4 цвета (r,g,b,a) — градиент по углу
     * @param count  сколько кругов (0..MAX_CIRCLES)
     * @param camF/camR/camU  камерные базисы (мир, нормированные)
     * @param tanF   tan(fovY/2) реальной проекции игры
     */
    public static void apply(GameContext ctx, float[] data, float[] colors, int count,
                             float camFx, float camFy, float camFz,
                             float camRx, float camRy, float camRz,
                             float camUx, float camUy, float camUz,
                             float tanF, float aspect, float time,
                             float warpAmp, float tintAmount, float satFactor,
                             float glowOn, float glowIntensity,
                             float glowH, float glowW, float glowTint, float glowAlpha) {
        if (count <= 0) return;
        if (!init()) return;
        try {
            java.nio.IntBuffer vpBuf = ByteBuffer.allocateDirect(16)
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
            ensureActiveTexture0();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(0x0BC0); // GL_ALPHA_TEST — не режет сэмпл

            // --- копия текущего таргета (мир + всё до HUD) в свою текстуру.
            // ВАЖНО: copyTex НИ К ЧЕМУ не прикреплён (аттач к таргету, в который
            // рисуем = feedback loop при сэмпле → «зависшая картинка»).
            // glCopyTexSubImage2D читает цвет из ТЕКУЩЕГО read-фреймбуфера
            // (игровой оверлей-таргет). ---
            if (srcW != curFbw || srcH != curFbh) {
                if (copyTex != 0) GL11.glDeleteTextures(copyTex);
                copyTex = newTex(srcW, srcH);
                curFbw = srcW;
                curFbh = srcH;
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTex);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, srcW, srcH);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            // --- fullscreen-проход в ТЕКУЩИЙ оверлей-таргет (игра рисует
            // оверлей в свой FBO — рисовать в 0 значит рисовать мимо) ---
            GL11.glViewport(0, 0, srcW, srcH);
            GL11.glMatrixMode(RenderUtil.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0, srcW, srcH, 0, -1, 1);
            GL11.glMatrixMode(RenderUtil.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();

            bindTex(copyTex);
            jumpShader.start();
            jumpShader.uniformI("uScene", 0);
            jumpShader.uniform2F("uFbSize", srcW, srcH);
            jumpShader.uniform3F("uCamF", camFx, camFy, camFz);
            jumpShader.uniform3F("uCamR", camRx, camRy, camRz);
            jumpShader.uniform3F("uCamU", camUx, camUy, camUz);
            jumpShader.uniformF("uTanF", tanF);
            jumpShader.uniformF("uAspect", aspect);
            jumpShader.uniformF("uTime", time);
            jumpShader.uniformF("uWarpAmp", warpAmp);
            jumpShader.uniformF("uTintAmount", tintAmount);
            jumpShader.uniformF("uSatFactor", satFactor);
            jumpShader.uniformF("uGlowOn", glowOn);
            jumpShader.uniformF("uGlowIntensity", glowIntensity);
            jumpShader.uniformF("uGlowHeightMul", glowH);
            jumpShader.uniformF("uGlowWidthMul", glowW);
            jumpShader.uniformF("uGlowTint", glowTint);
            jumpShader.uniformF("uGlowAlpha", glowAlpha);
            jumpShader.uniformI("uCount", count);
            for (int i = 0; i < MAX_CIRCLES * 2; i++) {
                int o = i * 4;
                jumpShader.uniform4F("uData[" + i + "]", data[o], data[o + 1], data[o + 2], data[o + 3]);
            }
            for (int i = 0; i < MAX_CIRCLES * 4; i++) {
                int o = i * 4;
                jumpShader.uniform4F("uColors[" + i + "]", colors[o], colors[o + 1], colors[o + 2], colors[o + 3]);
            }

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glVertex2f(0f, 0f);
            GL11.glVertex2f(0f, srcH);
            GL11.glVertex2f(srcW, srcH);
            GL11.glVertex2f(srcW, 0f);
            GL11.glEnd();

            jumpShader.stop();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            GL11.glMatrixMode(RenderUtil.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(RenderUtil.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glViewport(vpX, vpY, srcW, srcH);
            // НЕ трогаем DEPTH_TEST (кэш GlStateManager — грабль GlassGrab),
            // blend возвращаем включённым (кэш игры считает его включённым)
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } catch (Throwable t) {
            Log.error("JumpDistort", "apply failed", t);
        }
    }
}
