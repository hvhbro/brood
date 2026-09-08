package utils.render;

import org.lwjglx.opengl.GL13;
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
 * IconData: fill белый, alpha=coverage). Тонировка через glColor.
 *
 * Fixed pipeline: bind атласа, glColor4ub, текстурный QUAD (texcoord через
 * lwjglx в шейдер НЕ доходит — 13.4.2 — поэтому именно фиксированный
 * конвейер; GL_ALPHA_TEST на время отрисовки выключается, как у шрифта).
 */
public final class IconRender {

    private static int texId;
    private static boolean tried;
    private static boolean ok;
    private static boolean logged;

    private static final int GL_ACTIVE_TEXTURE = 0x84E0;
    private static final int GL_TEXTURE0 = 0x84C0;
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
            ok = texId != 0;
            if (!logged) {
                logged = true;
                Log.info("Icons", "atlas " + w + "x" + h + ", " + IconData.NAMES.length + " icons, ok=" + ok);
            }
        } catch (Throwable t) {
            Log.error("Icons", "init failed", t);
        }
        return ok;
    }

    /** Иконка rock: name из IconData.NAMES, size в scaled-пикселях. */
    public static void drawIcon(String name, float x, float y, float size, int argb) {
        if (!ensure()) return;
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
            float u1 = u0 + cellW, v1 = v0 + cellH;

            GameContext ctx = GameContext.get();
            int prevProgram = GL11.glGetInteger(GL_CURRENT_PROGRAM);
            if (prevProgram != 0) GL20.glUseProgram(0);
            int prevUnit = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
            if (prevUnit != GL_TEXTURE0) GL13.glActiveTexture(GL_TEXTURE0);
            int prevTex = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
            boolean prevAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            if (prevAlphaTest) GL11.glDisable(GL11.GL_ALPHA_TEST);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glColor4ub(
                (byte) ((argb >> 16) & 0xFF),
                (byte) ((argb >> 8) & 0xFF),
                (byte) (argb & 0xFF),
                (byte) ((argb >> 24) & 0xFF));
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(u0, v0);
            GL11.glVertex2f(x, y);
            GL11.glTexCoord2f(u0, v1);
            GL11.glVertex2f(x, y + size);
            GL11.glTexCoord2f(u1, v1);
            GL11.glVertex2f(x + size, y + size);
            GL11.glTexCoord2f(u1, v0);
            GL11.glVertex2f(x + size, y);
            GL11.glEnd();
            GL11.glColor4f(1f, 1f, 1f, 1f);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            if (prevAlphaTest) GL11.glEnable(GL11.GL_ALPHA_TEST);
            if (prevUnit != GL_TEXTURE0) GL13.glActiveTexture(prevUnit);
            if (prevProgram != 0) GL20.glUseProgram(prevProgram);
        } catch (Throwable t) {
            Log.error("Icons", "draw failed", t);
        }
    }
}
