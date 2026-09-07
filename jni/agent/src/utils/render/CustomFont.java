package utils.render;

import org.lwjgl.opengl.GL20;
import org.lwjglx.opengl.GL11;
import org.lwjglx.opengl.GL13;
import utils.etc.GameContext;
import utils.etc.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Кастомный гладкий шрифт HUD — как в expensive (MsdfFont/MsdfGlyph):
 * предзапечённый MSDF-атлас + GLSL-шейдер (медиана RGB → расстояние →
 * smoothstep), гладкий на любом guiScale.
 *
 * Два шрифта: HUD_FONT (sf_semibold — ArrayList, expensive-трюк шага
 * width*(size-1)) и WM_FONT (medium — ватермарка, настоящий advance +
 * 0.025em трекинг + left bearing, как у rockstar).
 *
 * UV: quad через фактические MV×PR (glTexCoord2f через lwjglx в GLSL не
 * доходит, ZNANIA 12.3 п.8). GL_ALPHA_TEST на время отрисовки выключается;
 * blend после нас остаётся включённым (кэш GlStateManager).
 */
public final class CustomFont {

    private static final int GL_ACTIVE_TEXTURE = 0x84E0;
    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static final int GL_CURRENT_PROGRAM = 0x8B8D;

    /** true = рисовать диагностическим шейдером (R=сырой MSDF, G=alpha, B=uv-полосы). */
    public static boolean DEBUG_SHADER = false;

    private static ShaderUtil shader;
    private static boolean shaderTried;
    private static boolean logged;

    private CustomFont() {}

    private static MsdfFont ensureFont(MsdfFont f) {
        return f.ensureLoaded() ? f : null;
    }

    /** Ленивая инициализация шейдера + атласа (только GL-поток). */
    private static ShaderUtil ensureShader(MsdfFont f) {
        f.ensureLoaded();
        if (shaderTried) return shader;
        shaderTried = true;
        try {
            shader = new ShaderUtil(Shaders.VERT, DEBUG_SHADER ? Shaders.MSDF_DEBUG : Shaders.MSDF);
            if (shader.programId() == 0) {
                shader = null;
                Log.error("Font", "msdf shader program is 0 (link failed?), fallback to game font", null);
            }
        } catch (Throwable t) {
            shader = null;
            Log.error("Font", "msdf init failed, HUD falls back to game font", t);
        }
        return shader;
    }

    /** Шаг глифа по перу. */
    private static float glyphAdvance(MsdfFont f, float[] g, char c, float size) {
        if (f.properAdvance) {
            return g[7] * size + 0.025f * size; // advance + трекинг (rockstar)
        }
        float adv = g[4] * (size - 1f);         // expensive: width*(size-1)
        if (c == ' ') adv += g[7] * size;
        return adv;
    }

    /** Ширина строки в scaled-px (HUD-шрифт). */
    public static float getWidth(String s, float size) {
        return getWidth(s, size, HUD_FONT);
    }

    /** Ширина строки в scaled-px указанным шрифтом. */
    public static float getWidth(String s, float size, MsdfFont f) {
        if (s == null || s.isEmpty()) return 0f;
        if (ensureShader(f) == null || !f.available()) return s.length() * 6f;
        float w = 0f;
        for (int i = 0; i < s.length(); i++) {
            float[] g = f.glyph(s.charAt(i));
            if (g != null) w += glyphAdvance(f, g, s.charAt(i), size);
            else w += size * 0.35f;
        }
        return w;
    }

    /** Высота ячейки текста (lineHeight метрик) в scaled-px (HUD-шрифт). */
    public static float cellHeight(float size) {
        return cellHeight(size, HUD_FONT);
    }

    public static float cellHeight(float size, MsdfFont f) {
        return f.LINE_HEIGHT * size;
    }

    /** Шрифт ArrayList (sf_semibold). */
    public static final MsdfFont HUD_FONT = MsdfFont.get("sf_semibold");
    /** Шрифт ватермарки (medium rockstar). */
    public static final MsdfFont WM_FONT = MsdfFont.get("medium");

    /**
     * Гладкая MSDF-строка (HUD-шрифт). x,y — ЛЕВЫЙ ВЕРХ строки.
     * Возвращает ширину строки в scaled-px.
     */
    public static float drawString(String s, float x, float y, int color, boolean shadow, float size) {
        return drawString(s, x, y, color, shadow, size, HUD_FONT);
    }

    /**
     * Гладкая MSDF-строка указанным шрифтом. x,y — ЛЕВЫЙ ВЕРХ строки
     * (baseline внутри через ascender). Тень — тот же глиф чёрным, offset 1px.
     */
    public static float drawString(String s, float x, float y, int color, boolean shadow, float size, MsdfFont f) {
        if (s == null || s.isEmpty()) return 0f;
        if (ensureShader(f) == null || shader == null || !f.available()) return 0f;

        int fbHeight = GameContext.get().fbHeight;
        float baseline = y + f.ASCENDER * size;

        int prevProgram = GL11.glGetInteger(GL_CURRENT_PROGRAM);
        if (prevProgram != 0) GL20.glUseProgram(0);
        int prevUnit = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
        if (prevUnit != GL_TEXTURE0) GL13.glActiveTexture(GL_TEXTURE0);
        int prevTex = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
        // GL_ALPHA_TEST режет полупрозрачное — MSDF-градиент обрезается в ступеньки
        boolean prevAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        if (prevAlphaTest) GL11.glDisable(GL11.GL_ALPHA_TEST);

        try {
            GL20.glUseProgram(shader.programId());

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            f.bind();

            shader.uniformI("atlas", 0);
            shader.uniformF("thickness", 0f);
            shader.uniformF("smoothness", 0.5f);
            shader.uniformF("fbHeight", (float) fbHeight);

            if (shadow) {
                setColor(color, true);
                walkString(f, s, x + 1f, baseline + 1f, size);
            }
            setColor(color, false);
            walkString(f, s, x, baseline, size);

            f.unbind(prevTex);
            // blend НЕ выключаем: GlStateManager игры кэширует состояние
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } catch (Throwable t) {
            Log.error("Font", "msdf draw failed", t);
        } finally {
            if (prevAlphaTest) GL11.glEnable(GL11.GL_ALPHA_TEST);
            if (prevProgram != 0) GL20.glUseProgram(prevProgram);
            if (prevUnit != GL_TEXTURE0) GL13.glActiveTexture(prevUnit);
        }
        return getWidth(s, size, f);
    }

    private static void setColor(int color, boolean shadowPass) {
        float a = ((color >>> 24) & 0xFF) / 255f;
        float r = ((color >>> 16) & 0xFF) / 255f;
        float g = ((color >>> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        if (shadowPass) {
            r = 0f; g = 0f; b = 0f;
            a *= 0.55f;
        }
        shader.uniform4F("color", r, g, b, a);
    }

    /** Линейная интерполяция ARGB. */
    private static int lerpColor(int c1, int c2, float t) {
        int a1 = (c1 >>> 24) & 0xFF, r1 = (c1 >>> 16) & 0xFF, g1 = (c1 >>> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >>> 24) & 0xFF, r2 = (c2 >>> 16) & 0xFF, g2 = (c2 >>> 8) & 0xFF, b2 = c2 & 0xFF;
        int a = (int) (a1 + (a2 - a1) * t);
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Градиентный текст (как drawGradientText vanquish): цвет каждого глифа =
     * линейная интерполяция colorLeft → colorRight по позиции в строке.
     * x,y — ЛЕВЫЙ ВЕРХ строки. Без тени (как в vanquish).
     * Возвращает ширину строки в scaled-px.
     */
    public static float drawGradientString(String s, float x, float y, int colorLeft, int colorRight,
                                           float size, MsdfFont f) {
        if (s == null || s.isEmpty()) return 0f;
        if (ensureShader(f) == null || shader == null || !f.available()) return 0f;

        int fbHeight = GameContext.get().fbHeight;
        float baseline = y + f.ASCENDER * size;
        float totalW = getWidth(s, size, f);

        int prevProgram = GL11.glGetInteger(GL_CURRENT_PROGRAM);
        if (prevProgram != 0) GL20.glUseProgram(0);
        int prevUnit = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
        if (prevUnit != GL_TEXTURE0) GL13.glActiveTexture(GL_TEXTURE0);
        int prevTex = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
        boolean prevAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        if (prevAlphaTest) GL11.glDisable(GL11.GL_ALPHA_TEST);

        try {
            GL20.glUseProgram(shader.programId());

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            f.bind();

            shader.uniformI("atlas", 0);
            shader.uniformF("thickness", 0f);
            shader.uniformF("smoothness", 0.5f);
            shader.uniformF("fbHeight", (float) fbHeight);

            float pen = x;
            for (int i = 0; i < s.length(); i++) {
                float[] g = f.glyph(s.charAt(i));
                float adv;
                if (g != null) {
                    float w = g[4] * size;
                    float h = g[5] * size;
                    float gx = f.properAdvance ? pen + g[8] * size : pen;
                    float gy = baseline - g[6] * size - 1f;

                    float qw = w * GuiScale.get(GameContext.get());
                    float qh = h * GuiScale.get(GameContext.get());

                    if (qw >= 0.5f && qh >= 0.5f) {
                        shader.uniform4F("quad", gx * GuiScale.get(GameContext.get()), gy * GuiScale.get(GameContext.get()), qw, qh);
                        shader.uniform4F("uvRect", g[0], g[1], g[2], g[3]);
                        float uvW = g[2] - g[0];
                        float screenPxPerAtlasPx = qw / Math.max(uvW * f.ATLAS_WIDTH, 1e-4f);
                        shader.uniformF("pxRange", Math.max(f.DISTANCE_RANGE * screenPxPerAtlasPx, 1f));

                        // градиент по центру глифа
                        float t = totalW > 0f ? ((pen + w * 0.5f) - x) / totalW : 0f;
                        if (t < 0f) t = 0f;
                        if (t > 1f) t = 1f;
                        int gc = lerpColor(colorLeft, colorRight, t);
                        float fa = ((gc >>> 24) & 0xFF) / 255f;
                        shader.uniform4F("color",
                            ((gc >>> 16) & 0xFF) / 255f, ((gc >>> 8) & 0xFF) / 255f,
                            (gc & 0xFF) / 255f, fa);

                        drawQuad(gx, gy, w, h);
                    }
                    adv = glyphAdvance(f, g, s.charAt(i), size);
                } else {
                    adv = size * 0.35f;
                }
                pen += adv;
            }

            f.unbind(prevTex);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } catch (Throwable t) {
            Log.error("Font", "msdf gradient draw failed", t);
        } finally {
            if (prevAlphaTest) GL11.glEnable(GL11.GL_ALPHA_TEST);
            if (prevProgram != 0) GL20.glUseProgram(prevProgram);
            if (prevUnit != GL_TEXTURE0) GL13.glActiveTexture(prevUnit);
        }
        return getWidth(s, size, f);
    }

    /**
     * Проход по строке: на каждый глиф — свой квад и свои uniform'ы.
     * properAdvance (medium): квад со left bearing, перо = advance*size + 0.025em;
     * sf_*: квад от пера, перо = width*(size-1) (+ advance у пробела).
     */
    private static void walkString(MsdfFont f, String s, float x, float baseline, float size) {
        GameContext ctx = GameContext.get();
        float scale = GuiScale.get(ctx);
        dumpMatricesOnce();
        float pen = x;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            float[] g = f.glyph(c);
            float adv;
            if (g != null) {
                float w = g[4] * size;
                float h = g[5] * size;
                float gx = f.properAdvance ? pen + g[8] * size : pen;
                float gy = baseline - g[6] * size - 1f;

                float qw = w * scale;
                float qh = h * scale;

                if (qw >= 0.5f && qh >= 0.5f) {
                    shader.uniform4F("quad", gx * scale, gy * scale, qw, qh);
                    shader.uniform4F("uvRect", g[0], g[1], g[2], g[3]);
                    // Один атласный px глифа занимает qw/(uvW*ATLAS_W) экранных px;
                    // кламп на 1px AA (max(...,1.0) в ui_batch.fsh мода).
                    float uvW = g[2] - g[0];
                    float screenPxPerAtlasPx = qw / Math.max(uvW * f.ATLAS_WIDTH, 1e-4f);
                    shader.uniformF("pxRange", Math.max(f.DISTANCE_RANGE * screenPxPerAtlasPx, 1f));
                    drawQuad(gx, gy, w, h);
                }
                adv = glyphAdvance(f, g, c, size);
            } else {
                adv = size * 0.35f;
            }
            pen += adv;
        }
    }

    // ===== одноразовая диагностика матриц =====

    private static final java.nio.FloatBuffer MV = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final java.nio.FloatBuffer PR = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final int GL_MODELVIEW_MATRIX = 0x0BA6;
    private static final int GL_PROJECTION_MATRIX = 0x0BA7;
    private static boolean matLogged;

    private static float mv(int i) { return MV.get(i); }

    private static float pr(int i) { return PR.get(i); }

    private static void dumpMatricesOnce() {
        if (matLogged) return;
        matLogged = true;
        MV.clear(); PR.clear();
        GL11.glGetFloat(GL_MODELVIEW_MATRIX, MV);
        GL11.glGetFloat(GL_PROJECTION_MATRIX, PR);
        Log.info("Font", "MV=[" + mv(0) + "," + mv(5) + "," + mv(12) + "," + mv(13) + "," + mv(15)
            + "] PR=[" + pr(0) + "," + pr(5) + "," + pr(12) + "," + pr(13) + "," + pr(15) + "]");
    }

    private static void drawQuad(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
    }

    /** Доступность MSDF-шрифта (fallback HUD на игровой шрифт). */
    public static boolean available() {
        return ensureShader(HUD_FONT) != null && HUD_FONT.available();
    }
}
