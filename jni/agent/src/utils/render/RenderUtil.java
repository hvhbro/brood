package utils.render;

import org.lwjgl.opengl.GL20;
import org.lwjglx.opengl.GL11;
import utils.etc.GameContext;
import utils.etc.Log;

/**
 * Рендер-утилиты (expensive-стиль) на lwjglx-шимпе:
 * матрицы, квады с градиентом, скруглённая имитация, scissor.
 *
 * ВСЁ вызывается только с главного потока (из CheatIngame).
 */
public final class RenderUtil {

    private static final int GL_CURRENT_PROGRAM = 0x8B8D;

    private RenderUtil() {}

    // ===== Матрицы =====

    // GL-константы для матриц (в стабе их нет; в рантайме lwjglx проксирует
    // настоящий LWJGL3 GL11, там значения стандартные)
    public static final int GL_MATRIX_MODE = 0x0BA0;
    public static final int GL_MODELVIEW = 0x1700;
    public static final int GL_PROJECTION = 0x1701;
    public static final int GL_COLOR_BUFFER_BIT = 0x4000;

    /**
     * Экранное ортографическое пространство 0..w / 0..h (scaled-пиксели).
     * Сохраняет текущие матрицы; парный вызов screenSpaceEnd.
     * Нужен потому, что в drawScreen фаза GUI игры может иметь произвольные
     * матрицы (прод-форк 1.12.2 рендерит экраны не как ваниль 1.12) —
     * наши scaled-координаты умножались дважды → квад заливал весь экран.
     */
    public static void screenSpaceStart(float width, float height) {
        GL11.glMatrixMode(GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, width, height, 0, -1, 100);
        GL11.glMatrixMode(GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
    }

    public static void screenSpaceEnd() {
        GL11.glMatrixMode(GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL_MODELVIEW);
    }

    public static void scaleStart(float x, float y, float scaleX, float scaleY) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(scaleX, scaleY, 1);
        GL11.glTranslatef(-x, -y, 0);
    }

    public static void scaleEnd() {
        GL11.glPopMatrix();
    }

    /** Текст в SCALED-координатах (FontRenderer игры сам масштабирует — эмпирика v29-32). */
    public static void drawStringScaled(GameContext ctx, String s, float xScaled, float yScaled, int color, boolean shadow) {
        ctx.drawString(s, xScaled, yScaled, color, shadow);
    }

    /** Ванильный drawRect (lIilIliliI) в scaled-координатах — как Gui.drawRect ванили. */
    public static void drawRectVanilla(GameContext ctx, int x1, int y1, int x2, int y2, int color) {
        ctx.drawRect(x1, y1, x2, y2, color);
    }

    // ===== Квады =====

    /** Плоский прямоугольник с цветом ARGB (GlStateManager-независимый). */
    public static void drawRect(GameContext ctx, float x, float y, float w, float h, int color) {
        if (w <= 0 || h <= 0) return;
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4ub(
            (byte) ((color >> 16) & 0xFF),
            (byte) ((color >> 8) & 0xFF),
            (byte) (color & 0xFF),
            (byte) ((color >> 24) & 0xFF));
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        // восстановить нейтральный цвет: наш glColor4ub (цвет полоски) остаётся
        // текущим после кадра, а GlStateManager игры пропустит свой glColor по
        // кэшу → сущности рендерятся нашим цветом (голубой персонаж)
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
    }

    /** Вертикальный градиент (top -> bottom), по строкам-полосам (fixed-pipeline совместимо). */
    public static void drawGradientRectV(GameContext ctx, float x, float y, float w, float h, int topColor, int bottomColor, int bands) {
        if (w <= 0 || h <= 0 || bands <= 0) return;
        int aT = (topColor >> 24) & 0xFF, rT = (topColor >> 16) & 0xFF, gT = (topColor >> 8) & 0xFF, bT = topColor & 0xFF;
        int aB = (bottomColor >> 24) & 0xFF, rB = (bottomColor >> 16) & 0xFF, gB = (bottomColor >> 8) & 0xFF, bB = bottomColor & 0xFF;
        float bandH = h / bands;
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < bands; i++) {
            float t0 = i / (float) bands;
            float t1 = (i + 1) / (float) bands;
            int a0 = aT + (int) ((aB - aT) * t0), r0 = rT + (int) ((rB - rT) * t0), g0 = gT + (int) ((gB - gT) * t0), b0 = bT + (int) ((bB - bT) * t0);
            int a1 = aT + (int) ((aB - aT) * t1), r1 = rT + (int) ((rB - rT) * t1), g1 = gT + (int) ((gB - gT) * t1), b1 = bT + (int) ((bB - bT) * t1);
            float yy = y + bandH * i;
            GL11.glColor4ub((byte) r0, (byte) g0, (byte) b0, (byte) a0);
            GL11.glVertex2f(x, yy);
            GL11.glVertex2f(x, yy + bandH);
            GL11.glColor4ub((byte) r1, (byte) g1, (byte) b1, (byte) a1);
            GL11.glVertex2f(x + w, yy + bandH);
            GL11.glVertex2f(x + w, yy);
        }
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f); // сброс цвета (см. drawRect)
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
    }

    /**
     * «Скруглённый» угол на fixed pipeline: блок + маленькие срезанные углы
     * (имитация SDF без шейдеров; для честного скругления — GL20-шейдер, этап 2).
     */
    public static void drawRoundedRect(GameContext ctx, float x, float y, float w, float h, float r, int color) {
        if (w <= 0 || h <= 0) return;
        // шейдерный путь: ровные скругления вместо ступенчатых квадов
        drawRoundedRectShader(ctx, x, y, w, h, Math.max(r, 0.5f),
            color, color, color, color, 0.25f);
    }


    // ===== Шейдерный путь (GL20) =====

    private static ShaderUtil roundShader;

    private static ShaderUtil roundShader() {
        if (roundShader == null) {
            roundShader = new ShaderUtil(Shaders.VERT, Shaders.ROUND);
        }
        return roundShader;
    }

    /**
     * Честный скруглённый прямоугольник: SDF-шейдер + поканальный градиент.
     * x,y,w,h в scaled-координатах; guiScale переводит их в экранные пиксели.
     * colors ARGB: topLeft, topRight, bottomLeft, bottomRight.
     */
    public static void drawRoundedRectShader(GameContext ctx, float x, float y, float w, float h,
                                             float radius, int c1, int c2, int c3, int c4) {
        drawRoundedRectShader(ctx, x, y, w, h, radius, c1, c2, c3, c4, 1.0f);
    }

    /** То же с управляемой мягкостью края (их RectSmoothness; 0.25 = почти жёсткий край). */
    public static void drawRoundedRectShader(GameContext ctx, float x, float y, float w, float h,
                                             float radius, int c1, int c2, int c3, int c4, float smoothness) {
        try {
            // ВЕРШИНЫ — в SCALED-координатах (ванильная ModelView-матрица guiScale ещё жива и
            // сама умножит на ×2; v31 это доказал). Uniform'ы шейдера — в ФИЗИЧЕСКИХ пикселях.
            float scale = GuiScale.get(ctx);
            float ex = x * scale, ey = y * scale;
            float ew = w * scale, eh = h * scale;
            float er = Math.min(radius * scale, Math.min(ew, eh) / 2f);

            // программа восстанавливается БЕЗУСЛОВНО (и в 0 тоже): кэш GL-состояния
            // игры считает привязку неизменной; физически оставленный наш шейдер
            // ломал их меню-рендер в лобби (иконки сквозь MSDF-шейдер → фиолетовый экран)
            int prevProgram = GL11.glGetInteger(GL_CURRENT_PROGRAM);

            ShaderUtil sh = roundShader();
            sh.start();
            sh.uniform4F("rect", ex, ey, ew, eh);
            sh.uniformF("radius", er);
            sh.uniformF("RectSmoothness", smoothness * scale);
            sh.uniformF("fbHeight", (float) ctx.fbHeight);
            sh.uniform4F("color1",
                ((c1 >> 16) & 0xFF) / 255f, ((c1 >> 8) & 0xFF) / 255f, (c1 & 0xFF) / 255f, ((c1 >> 24) & 0xFF) / 255f);
            sh.uniform4F("color2",
                ((c2 >> 16) & 0xFF) / 255f, ((c2 >> 8) & 0xFF) / 255f, (c2 & 0xFF) / 255f, ((c2 >> 24) & 0xFF) / 255f);
            sh.uniform4F("color3",
                ((c3 >> 16) & 0xFF) / 255f, ((c3 >> 8) & 0xFF) / 255f, (c3 & 0xFF) / 255f, ((c3 >> 24) & 0xFF) / 255f);
            sh.uniform4F("color4",
                ((c4 >> 16) & 0xFF) / 255f, ((c4 >> 8) & 0xFF) / 255f, (c4 & 0xFF) / 255f, ((c4 >> 24) & 0xFF) / 255f);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glColor4f(1, 1, 1, 1);
            // вершины в SCALED — матрица guiScale сама переведёт в физику
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x, y + h);
            GL11.glVertex2f(x + w, y + h);
            GL11.glVertex2f(x + w, y);
            GL11.glEnd();

            GL11.glEnable(GL11.GL_TEXTURE_2D);
            // blend НЕ выключаем: GlStateManager игры кэширует GL-состояние и
            // после модового HUD считает blend включённым; физический disable
            // десинхронизирует кэш → следующие полупрозрачные плашки рисуются
            // непрозрачно. Оставляем blend включённым (кэш совпадает с реальностью).
            sh.stop();
            GL20.glUseProgram(prevProgram); // безусловно: и 0, и чужую программу
        } catch (Throwable t) {
            utils.etc.Log.error("Render", "roundRect shader failed", t);
        }
    }

    private static ShaderUtil borderShader;

    private static ShaderUtil cornersShader;
    private static boolean cornersFailed;

    /**
     * Скруглённый прямоугольник с разными радиусами углов (rockstar squircle
     * TL,TR,BR,BL — хотбар-панель 1,0,7,8). Радиусы в scaled-пикселях.
     * При ошибке компиляции — fallback на равномерный радиус (средний).
     */
    public static void drawRoundedRectCorners(GameContext ctx, float x, float y, float w, float h,
                                              float rTL, float rTR, float rBR, float rBL,
                                              int c1, int c2, int c3, int c4, float smoothness) {
        try {
            if (cornersShader == null && !cornersFailed) {
                cornersShader = new ShaderUtil(Shaders.VERT, Shaders.ROUND_CORNERS);
                if (cornersShader.programId() == 0) {
                    cornersFailed = true;
                    cornersShader = null;
                    utils.etc.Log.error("Render", "corners shader failed, fallback uniform", null);
                }
            }
            if (cornersShader == null) {
                float avg = (rTL + rTR + rBR + rBL) * 0.25f;
                drawRoundedRectShader(ctx, x, y, w, h, avg, c1, c2, c3, c4, smoothness);
                return;
            }
            float scale = GuiScale.get(ctx);
            float ex = x * scale, ey = y * scale;
            float ew = w * scale, eh = h * scale;
            float cap = Math.min(ew, eh) / 2f;
            float q1 = Math.min(rTL * scale, cap);
            float q2 = Math.min(rTR * scale, cap);
            float q3 = Math.min(rBR * scale, cap);
            float q4 = Math.min(rBL * scale, cap);

            int prevProgram = GL11.glGetInteger(GL_CURRENT_PROGRAM);

            ShaderUtil sh = cornersShader;
            sh.start();
            sh.uniform4F("rect", ex, ey, ew, eh);
            sh.uniform4F("radii", q1, q2, q3, q4);
            sh.uniformF("RectSmoothness", smoothness * scale);
            sh.uniformF("fbHeight", (float) ctx.fbHeight);
            sh.uniform4F("color1",
                ((c1 >> 16) & 0xFF) / 255f, ((c1 >> 8) & 0xFF) / 255f, (c1 & 0xFF) / 255f, ((c1 >> 24) & 0xFF) / 255f);
            sh.uniform4F("color2",
                ((c2 >> 16) & 0xFF) / 255f, ((c2 >> 8) & 0xFF) / 255f, (c2 & 0xFF) / 255f, ((c2 >> 24) & 0xFF) / 255f);
            sh.uniform4F("color3",
                ((c3 >> 16) & 0xFF) / 255f, ((c3 >> 8) & 0xFF) / 255f, (c3 & 0xFF) / 255f, ((c3 >> 24) & 0xFF) / 255f);
            sh.uniform4F("color4",
                ((c4 >> 16) & 0xFF) / 255f, ((c4 >> 8) & 0xFF) / 255f, (c4 & 0xFF) / 255f, ((c4 >> 24) & 0xFF) / 255f);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x, y + h);
            GL11.glVertex2f(x + w, y + h);
            GL11.glVertex2f(x + w, y);
            GL11.glEnd();

            GL11.glEnable(GL11.GL_TEXTURE_2D);
            // blend НЕ выключаем (кэш GlStateManager, см. drawRoundedRectShader)
            sh.stop();
            GL20.glUseProgram(prevProgram); // безусловно: и 0, и чужую программу
        } catch (Throwable t) {
            utils.etc.Log.error("Render", "corners rect failed", t);
        }
    }

    private static ShaderUtil borderShader() {
        if (borderShader == null) {
            borderShader = new ShaderUtil(Shaders.VERT, Shaders.BORDER);
        }
        return borderShader;
    }

    /** Скруглённая рамка (полоса по периметру). width — в scaled-пикселях. */
    public static void drawRoundedBorder(GameContext ctx, float x, float y, float w, float h,
                                         float radius, float width, int color) {
        try {
            float scale = GuiScale.get(ctx);
            float ex = x * scale, ey = y * scale;
            float ew = w * scale, eh = h * scale;
            float er = Math.min(radius * scale, Math.min(ew, eh) / 2f);

            ShaderUtil sh = borderShader();
            sh.start();
            sh.uniform4F("rect", ex, ey, ew, eh);
            sh.uniformF("radius", er);
            sh.uniformF("borderWidth", Math.max(1f, width * scale));
            sh.uniformF("RectSmoothness", 0.6f * scale);
            sh.uniformF("fbHeight", (float) ctx.fbHeight);
            sh.uniform4F("color",
                ((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f, ((color >> 24) & 0xFF) / 255f);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x, y + h);
            GL11.glVertex2f(x + w, y + h);
            GL11.glVertex2f(x + w, y);
            GL11.glEnd();

            GL11.glEnable(GL11.GL_TEXTURE_2D);
            // blend НЕ выключаем (кэш GlStateManager, см. 13.4.5)
            sh.stop();
        } catch (Throwable t) {
            utils.etc.Log.error("Render", "rounded border failed", t);
        }
    }

    private static float texU, texV;

    private static void setTex(float u, float v) {
        texU = u; texV = v;
        // в fixed pipeline gl_MultiTexCoord0 берётся из glTexCoord — через GL11.glTexCoord2f
        GL11.glTexCoord2f(u, v);
    }

    /** Обрезка рендера областью (для прокрутки меню). */
    public static void scissorStart(float x, float y, float w, float h, int screenHeight) {
        float scale = Math.max(1f, screenHeight / 240f); // примерная оценка gui scale
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(Math.round(x * scale), Math.round(screenHeight - (y + h) * scale),
            Math.round(w * scale), Math.round(h * scale));
    }

    public static void scissorEnd() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    // ===== Depth-проба (общая для CustomSky/Ambience) =====

    private static java.nio.FloatBuffer probeBuf;
    private static long lastProbe;
    private static boolean lastLive;
    private static boolean probeLogged;

    /**
     * Живой ли depth в текущем FBO: проба 64x8 в центре (glReadPixels
     * DEPTH_COMPONENT) раз в 3с; живой = есть значения < 1.0. Игра очищает
     * depth перед рендером руки — после этого (и при MSAA aa>=2) проба мертва,
     * и любые far-plane трюки (CustomSky/Ambience) рисовать нельзя.
     * Нулевой буфер = шим не заполнил (как glGetFloat) — вердикт не меняем.
     */
    public static boolean depthProbeLive(int fbW, int fbH) {
        long now = System.currentTimeMillis();
        if (now - lastProbe < 3000L) return lastLive;
        lastProbe = now;
        try {
            if (probeBuf == null) {
                probeBuf = java.nio.ByteBuffer.allocateDirect(64 * 8 * 4)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            }
            probeBuf.clear();
            GL11.glReadPixels(Math.max(0, fbW / 2 - 32), Math.max(0, fbH / 2 - 4),
                64, 8, 0x1902 /*GL_DEPTH_COMPONENT*/, 0x1406 /*GL_FLOAT*/, probeBuf);
            float min = 1f, max = 0f;
            for (int i = 0; i < 64 * 8; i++) {
                float v = probeBuf.get(i);
                if (v < min) min = v;
                if (v > max) max = v;
            }
            if (max <= 0f && min >= 1f) return lastLive; // буфер не заполнен
            boolean live = min < 0.9999f;
            if (!probeLogged) {
                probeLogged = true;
                Log.info("Render", "depth probe: " + (live ? "live" : "DEAD")
                    + " min=" + min + " max=" + max
                    + (live ? "" : " — far-plane эффекты (sky/tint) недоступны"));
            }
            lastLive = live;
        } catch (Throwable t) {
            Log.error("Render", "depth probe failed", t);
        }
        return lastLive;
    }
}