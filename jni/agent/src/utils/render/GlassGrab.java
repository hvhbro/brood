package utils.render;

import org.lwjgl.opengl.GL20;
import org.lwjglx.opengl.GL11;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Стеклянный фон меню (rock: backdrop blur): копируем текущий бэкбуфер в FBO,
 * 2 прохода gaussian в другие FBO, WINDOW-шейдер сэмплирует результат внутри
 * окна. Обычный рендер игры не трогаем.
 *
 * FBO-функции в LWJGL3 живут в GL30 — класса нет в компиляционном classpath,
 * зовём через reflection (кэш методов). Все проходы — в СВОЕЙ ортопроекции
 * (кадровое состояние форка произвольно).
 *
 * Разрешение blur-целей 1/2 — скорость. Только главный поток (HUD-фаза).
 * При любой ошибке ok=false — окно рисуется плоским фоном (fallback в
 * CheatMenuScreen.drawWindowGlassSafe).
 */
public final class GlassGrab {
    private static int copyTex, copyFbo, blurTex, blurFbo, tmpTex, tmpFbo;
    private static int tw, th;
    private static int curFbw, curFbh; // текущий размер copy-текстуры (по viewport)
    private static ShaderUtil blurShader, windowShader;
    private static boolean tried;
    private static boolean ok;
    private static boolean logged;
    private static Method mGenFramebuffers, mBindFramebuffer, mFramebufferTexture2D, mActiveTexture, mCheckStatus;

    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_ACTIVE_TEXTURE = 0x84E0;

    private GlassGrab() {}

    private static boolean initGl30() throws Exception {
        if (mBindFramebuffer != null) return true;
        Class gl30 = Class.forName("org.lwjgl.opengl.GL30");
        mGenFramebuffers = gl30.getMethod("glGenFramebuffers", IntBuffer.class);
        mBindFramebuffer = gl30.getMethod("glBindFramebuffer", int.class, int.class);
        mFramebufferTexture2D = gl30.getMethod("glFramebufferTexture2D",
            int.class, int.class, int.class, int.class, int.class);
        mCheckStatus = gl30.getMethod("glCheckFramebufferStatus", int.class);
        mActiveTexture = Class.forName("org.lwjgl.opengl.GL13").getMethod("glActiveTexture", int.class);
        return true;
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

    private static int newFbo(int tex) throws Exception {
        IntBuffer b = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        mGenFramebuffers.invoke(null, b);
        int f = b.get(0);
        mBindFramebuffer.invoke(null, GL_FRAMEBUFFER, f);
        mFramebufferTexture2D.invoke(null, GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        Object st = mCheckStatus.invoke(null, GL_FRAMEBUFFER);
        boolean complete = (st instanceof Number) && ((Number) st).intValue() == GL_FRAMEBUFFER_COMPLETE;
        return complete ? f : -1;
    }

    private static void bindFbo(int f) {
        try {
            mBindFramebuffer.invoke(null, GL_FRAMEBUFFER, f);
        } catch (Throwable ignore) {}
    }

    private static void bindTex(int t) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
    }

    private static void ensureActiveTexture0() {
        try {
            int cur = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
            if (cur != GL_TEXTURE0) mActiveTexture.invoke(null, GL_TEXTURE0);
        } catch (Throwable ignore) {}
    }

    private static boolean init(GameContext ctx) {
        if (tried) return ok;
        tried = true;
        try {
            initGl30();
            int scale = Math.max(1, Math.round(GuiScale.get(ctx)));
            curFbw = Math.max(64, ctx.scaledWidth * scale);
            curFbh = Math.max(64, ctx.fbHeight);
            tw = Math.max(64, curFbw / 2);
            th = Math.max(64, curFbh / 2);
            copyTex = newTex(curFbw, curFbh);   // копия экрана В ПОЛНЫЙ размер
            copyFbo = newFbo(copyTex);
            tmpTex = newTex(tw, th);
            tmpFbo = newFbo(tmpTex);
            blurTex = newTex(tw, th);
            blurFbo = newFbo(blurTex);
            bindFbo(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            if (copyFbo < 0 || tmpFbo < 0 || blurFbo < 0) {
                Log.error("Glass", "fbo incomplete", null);
                return false;
            }

            blurShader = new ShaderUtil(Shaders.VERT, Shaders.BLUR);
            windowShader = new ShaderUtil(Shaders.VERT, Shaders.WINDOW);
            ok = blurShader.programId() != 0
                && windowShader.programId() != 0;
            if (!ok) Log.error("Glass", "shaders failed", null);
            else if (!logged) {
                logged = true;
                Log.info("Glass", "OK, blur targets " + tw + "x" + th
                    + " (copy " + curFbw + "x" + curFbh + ")");
            }
        } catch (Throwable t) {
            ok = false;
            Log.error("Glass", "init failed", t);
        }
        return ok;
    }

    /** Доступ к full-res копии кадра (для sky/tint passes). После grab(). */
    public static int getCopyTex() { return copyTex; }

    /** Доступ к размытой копии (1/2 разрешения). После grab(). */
    public static int getBlurTex() { return blurTex; }

    /** Копия + блюр. В начале renderOverlay, пока бэкбуфер ещё цел. */
    public static void grab(GameContext ctx) {
        if (!init(ctx)) return;
        try {
            int prevFbo = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
            // ВАЖНО: значения приходят в БУФЕР (массив-аргумент не обновляется!)
            java.nio.IntBuffer vpBuf = ByteBuffer.allocateDirect(16)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
            GL11.glGetInteger(GL11.GL_VIEWPORT, vpBuf);
            int vpX = vpBuf.get(0), vpY = vpBuf.get(1);
            int srcW = vpBuf.get(2), srcH = vpBuf.get(3);
            // страховка: если чтение не сработало — размеры framebuffer'а из контекста
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

            // --- ЭТАП 1: копия БЭКБУФЕРА в copyTex (полный размер) через glCopyTexSubImage2D
            // (проверено wet_world: drawFs(copyShader) сэмплит ПУСТУЮ текстуру —
            // экран никто не положил в tex; читаем пиксели прямо из бэкбуфера) ---
            if (srcW != curFbw || srcH != curFbh) {
                // ресайз окна: пересоздаём копию под новый размер.
                // Ре-аттач строго на СВОЙ copyFbo (аттач на игровой FBO =
                // feedback loop при сэмпле → мусор на экране), затем возврат
                // на игровой FBO — источник копии должен быть он.
                GL11.glDeleteTextures(copyTex);
                copyTex = newTex(srcW, srcH);
                bindFbo(copyFbo);
                mFramebufferTexture2D.invoke(null, GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, copyTex, 0);
                bindFbo(prevFbo);
                curFbw = srcW;
                curFbh = srcH;
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTex);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, srcW, srcH);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            // --- ЭТАП 2/3: gaussian H и V в своей ортопроекции half-res ---
            GL11.glMatrixMode(RenderUtil.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0, tw, th, 0, -1, 1);
            GL11.glMatrixMode(RenderUtil.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            bindFbo(tmpFbo);
            GL11.glViewport(0, 0, tw, th);
            bindTex(copyTex);
            blurShader.start();
            blurShader.uniform2F("uDir", 1f, 0f);
            drawFsPass(blurShader, tw, th);
            blurShader.stop();

            bindFbo(blurFbo);
            bindTex(tmpTex);
            blurShader.start();
            blurShader.uniform2F("uDir", 0f, 1f);
            drawFsPass(blurShader, tw, th);
            blurShader.stop();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            // восстановить состояние (скопированный квад мог выставить эльвацию)
            GL11.glMatrixMode(RenderUtil.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(RenderUtil.GL_MODELVIEW);
            GL11.glPopMatrix();
            bindFbo(prevFbo);
            GL11.glViewport(vpX, vpY, srcW, srcH);
            // НЕ трогаем DEPTH_TEST: физический glEnable после мира десинхронит
            // кэш GlStateManager (он считает depth выключенным) — весь последующий
            // HUD начинает проходить depth-тест глубины мира и ИСЧЕЗАЕТ.
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } catch (Throwable t) {
            Log.error("Glass", "grab failed", t);
        }
    }

    private static void drawFsPass(ShaderUtil sh, int w, int h) {
        sh.uniform2F("uTexel", 1f / w, 1f / h);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glVertex2f(0, 0);
        GL11.glVertex2f(0, h);
        GL11.glVertex2f(w, h);
        GL11.glVertex2f(w, 0);
        GL11.glEnd();
    }

    /** Окно с сэмплом blur-текстуры. x,y,w,h — scaled; радиус тоже. */
    public static void drawWindowGlass(GameContext ctx, float x, float y, float w, float h, float radius) {
        if (!init(ctx)) return;
        try {
            float scale = GuiScale.get(ctx);
            float ex = x * scale, ey = y * scale;
            float ew = w * scale, eh = h * scale;
            float er = Math.min(radius * scale, Math.min(ew, eh) / 2f);

            ensureActiveTexture0();
            bindTex(blurTex);
            windowShader.start();
            windowShader.uniformI("tex", 0);
            windowShader.uniform4F("rect", ex, ey, ew, eh);
            windowShader.uniformF("radius", er);
            windowShader.uniformF("RectSmoothness", 0.6f * scale);
            windowShader.uniformF("fbHeight", (float) ctx.fbHeight);
            // физическая ширина фреймбуфера: для экранных uv стекла (см. WINDOW)
            windowShader.uniformF("fbWidth", (float) Math.max(64,
                Math.round(ctx.scaledWidth * scale)));
            windowShader.uniform4F("tint", 24f / 255f, 21f / 255f, 29f / 255f, 0.93f);
            windowShader.uniformF("blurMix", 0.30f);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x, y + h);
            GL11.glVertex2f(x + w, y + h);
            GL11.glVertex2f(x + w, y);
            GL11.glEnd();
            windowShader.stop();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        } catch (Throwable t) {
            Log.error("Glass", "window draw failed", t);
        }
    }

    /** Готов ли стеклянный путь (для fallback на плоский фон). */
    public static boolean available(GameContext ctx) {
        return init(ctx);
    }
}
