package rustme;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.imageio.ImageIO;

import utils.etc.Log;

/**
 * Наша текстура-скин: реализует игровой интерфейс iiIiliiIiI (TextureObject).
 * PNG читается из встроенного payload (AssetData, Base64-чанки как у шрифтов),
 * GL-текстура создаётся лениво при первом loadTexture.
 *
 * loadTexture(iIIiiIiIiI) зовётся TextureManager'ом (lIliliiIiI.lliIilliII)
 * на ГЛАВНОМ потоке при регистрации — там и делаем glGenTextures/glTexImage2D.
 * GL-вызовы через org.lwjglx.GL11 (тот же shim, что у шрифтов MsdfFont).
 *
 * iIlIllliil(ResourceManager) может вызываться повторно (перезагрузка ресурсов) —
 * поэтому тело идемпотентно: если texId уже есть, обновляем содержимое.
 */
public class SvoTexture implements iiIiliiIiI {

    private static final String ASSET_NAME = "putin";

    private static boolean pngLoaded;
    private static byte[] pngBytes;

    private int texId = -1;
    private boolean failed;

    /** Достаёт PNG из встроенного payload (один раз). */
    private static byte[] getPng() {
        if (!pngLoaded) {
            pngLoaded = true;
            try {
                pngBytes = utils.etc.AssetData.get(ASSET_NAME);
            } catch (Throwable t) {
                Log.error("SvoTexture", "asset decode failed", t);
            }
        }
        return pngBytes;
    }

    private void upload() {
        try {
            byte[] png = getPng();
            if (png == null) throw new java.io.IOException("asset not embedded: " + ASSET_NAME);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) throw new java.io.IOException("ImageIO returned null");
            int w = img.getWidth(), h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < argb.length; i++) {
                int px = argb[i];
                buf.put((byte) ((px >> 16) & 0xFF)); // R
                buf.put((byte) ((px >> 8) & 0xFF));  // G
                buf.put((byte) (px & 0xFF));         // B
                buf.put((byte) ((px >> 24) & 0xFF)); // A (прозрачность скина)
            }
            buf.flip();

            if (texId < 0) {
                texId = GL11.glGenTextures();
            }
            int prevTex = GL11.glGetInteger(0x8069);     // GL_TEXTURE_BINDING_2D
            int prevProgram = GL11.glGetInteger(0x8B8D); // GL_CURRENT_PROGRAM
            if (prevProgram != 0) GL20.glUseProgram(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            // скин — nearest без мипов, как ванильные skins/<...>.png
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            if (prevProgram != 0) GL20.glUseProgram(prevProgram);
        } catch (Throwable t) {
            failed = true;
            Log.error("SvoTexture", "upload failed", t);
        }
    }

    // --- iiIiliiIiI (TextureObject) ---

    @Override
    public void iIlIllliil(iIIiiIiIiI resourceManager) {
        // TextureManager зовёт при регистрации и при reloadResources
        if (!failed) upload();
    }

    @Override
    public int iliiIlliil() {
        return texId;
    }

    @Override
    public void IliiIlliil(boolean animate, boolean interpolate) {
        // no-op: скин не анимируется
    }

    @Override
    public void lIiiIlliil() {
        // releaseGlId: не критично (текстура живёт до выключения игры)
    }

    // --- GL-обёртки через lwjglx shim (как в MsdfFont) ---
    private static final class GL11 {
        static final int GL_TEXTURE_2D = 0x0DE1;
        static final int GL_RGBA = 0x1908;
        static final int GL_UNSIGNED_BYTE = 0x1401;
        static final int GL_NEAREST = 0x2600;
        static final int GL_CLAMP = 0x2900;
        static final int GL_TEXTURE_MIN_FILTER = 0x2801;
        static final int GL_TEXTURE_MAG_FILTER = 0x2800;
        static final int GL_TEXTURE_WRAP_S = 0x2802;
        static final int GL_TEXTURE_WRAP_T = 0x2803;

        static int glGenTextures() { return org.lwjglx.opengl.GL11.glGenTextures(); }
        static void glBindTexture(int t, int id) { org.lwjglx.opengl.GL11.glBindTexture(t, id); }
        static void glTexParameteri(int t, int p, int v) { org.lwjglx.opengl.GL11.glTexParameteri(t, p, v); }
        static void glTexImage2D(int t, int lvl, int ic, int w, int h, int b, int f, int ty, ByteBuffer px) {
            org.lwjglx.opengl.GL11.glTexImage2D(t, lvl, ic, w, h, b, f, ty, px);
        }
        static int glGetInteger(int pname) { return org.lwjglx.opengl.GL11.glGetInteger(pname); }
    }

    private static final class GL20 {
        static void glUseProgram(int p) { org.lwjgl.opengl.GL20.glUseProgram(p); }
    }
}
