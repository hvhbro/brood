package utils.render;

import org.lwjgl.opengl.GL20;
import org.lwjglx.opengl.GL11;
import utils.etc.GameContext;
import utils.etc.Log;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Рендер SVG-иконок rock (растеризованы tools/build_icons.py в атлас
 * IconData: fill белый, alpha=coverage). Тонировка через uniform color.
 *
 * Рисование ТОЛЬКО через GL20-шейдер (Shaders.ICON), как MSDF-шрифт:
 * fixed-pipeline texcoord через lwjglx ненадёжен (13.4.2 — s/t в квад
 * не попадает; у друга на его сборке иконки так и не рисовались), а
 * gl_FragCoord-путь у нас доказан (шрифт). Вершинный ftransform + QUAD
 * в SCALED-координатах; quad/uvRect — uniform'ы в физ.пикселях.
 * GL_ALPHA_TEST на время отрисовки выключается (режет градиент, 13.4.4).
 */
public final class IconRender {

    private static int texId;
    private static boolean tried;
    private static boolean ok;
    private static boolean logged;
    private static ShaderUtil iconShader;

    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_ACTIVE_TEXTURE = 0x84E0;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static final int GL_CURRENT_PROGRAM = 0x8B8D;

    private IconRender() {}

    private static boolean ensure() {
        if (tried) return ok;
        tried = true;
        try {
            byte[] png = IconData.getAtlas();
            if (png == null) {
                Log.error("Icons", "atlas bytes null", null);
                return false;
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                Log.error("Icons", "atlas decode failed", null);
                return false;
            }
            int w = img.getWidth(), h = img.getHeight();
            int[] px = new int[w * h];
            img.getRGB(0, 0, w, h, px, 0, w);
            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            for (int p : px) {
                buf.put((byte) ((p >> 16) & 0xFF));
                buf.put((byte) ((p >> 8) & 0xFF));
                buf.put((byte) (p & 0xFF));
                buf.put((byte) ((p >> 24) & 0xFF));
            }
            buf.flip();
            texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            iconShader = new ShaderUtil(Shaders.VERT, Shaders.ICON);
            ok = texId != 0 && iconShader.programId() != 0;
            if (!ok) Log.error("Icons", "shader compile failed", null);
            if (!logged) {
                logged = true;
                Log.info("Icons", "atlas " + w + "x" + h + ", " + IconData.NAMES.length
                    + " icons, shader=" + iconShader.programId() + ", ok=" + ok);
            }
        } catch (Throwable t) {
            Log.error("Icons", "init failed", t);
        }
        return ok;
    }

    /** Иконка rock: name из IconData.NAMES, x/y/size в scaled-пикселях, argb-тонировка. */
    public static void drawIcon(String name, float x, float y, float size, int argb) {
        if (!ensure() || iconShader == null || iconShader.programId() == 0) return;
        int idx = -1;
        for (int i = 0; i < IconData.NAMES.length; i++) {
            if (IconData.NAMES[i].equals(name)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        try {
            int cols = IconData.COLS;
            int rows = IconData.ATLAS_H / IconData.CELL;
            float cellW = 1f / cols, cellH = 1f / rows;
            float u0 = (idx % cols) * cellW, v0 = (idx / cols) * cellH;

            GameContext ctx = GameContext.get();
            float scale = GuiScale.get(ctx);

            int prevProgram = GL11.glGetInteger(GL_CURRENT_PROGRAM);
            int prevTex = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
            boolean prevAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            if (prevAlphaTest) GL11.glDisable(GL11.GL_ALPHA_TEST);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            // активный юнит -> 0 (через lwjglx GL13; реальная прокси в нативный LWJGL3)
            int prevUnit = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
            if (prevUnit != GL_TEXTURE0) org.lwjglx.opengl.GL13.glActiveTexture(GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);

            iconShader.start();
            iconShader.uniformI("atlas", 0);
            // вершины SCALED (матрица guiScale жива), uniform'ы — ФИЗИЧЕСКИЕ пиксели
            iconShader.uniform4F("quad", x * scale, y * scale, size * scale, size * scale);
            iconShader.uniform4F("uvRect", u0, v0, u0 + cellW, v0 + cellH);
            iconShader.uniformF("fbHeight", (float) ctx.fbHeight);
            iconShader.uniform4F("color",
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >> 24) & 0xFF) / 255f);

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x, y + size);
            GL11.glVertex2f(x + size, y + size);
            GL11.glVertex2f(x + size, y);
            GL11.glEnd();

            iconShader.stop();
            GL20.glUseProgram(prevProgram); // безусловно: и 0, и чужую программу
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            if (prevUnit != GL_TEXTURE0) org.lwjglx.opengl.GL13.glActiveTexture(prevUnit);
            if (prevAlphaTest) GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } catch (Throwable t) {
            Log.error("Icons", "draw failed", t);
        }
    }
}
