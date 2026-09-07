package utils.render;

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

    private RenderUtil() {}

    // ===== Матрицы =====

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
        if (r < 0.5f) { drawRect(ctx, x, y, w, h, color); return; }
        // центральная часть
        drawRect(ctx, x + r, y, w - 2 * r, h, color);
        // боковые части
        drawRect(ctx, x, y + r, r, h - 2 * r, color);
        drawRect(ctx, x + w - r, y + r, r, h - 2 * r, color);
        // углы: ступеньки по диагонали (r/2 ступень)
        int steps = 2;
        float step = r / steps;
        for (int i = 0; i < steps; i++) {
            float inset = r - (i + 1) * step;
            float yy = y + i * step;
            drawRect(ctx, x + r - inset, yy, inset * 2 + (w - 2 * r), step, color);
            drawRect(ctx, x + r - inset, y + h - (i + 1) * step, inset * 2 + (w - 2 * r), step, color);
        }
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
        } catch (Throwable t) {
            utils.etc.Log.error("Render", "roundRect shader failed", t);
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
}
